// Copyright 2026 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include "common/fastmem_arena.h"
#include "common/logging/log.h"

#if defined(__linux__) && defined(__LP64__)
#define CITRA_FASTMEM_ARENA_SUPPORTED 1
#include <cerrno>
#include <cstdint>
#include <fstream>
#include <string>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>
#else
#define CITRA_FASTMEM_ARENA_SUPPORTED 0
#endif

#if defined(__ANDROID__)
#include <android/log.h>
// The regular LOG_* backend can be filtered/unavailable this early on Android; these one-shot
// state lines must always be visible in logcat for on-device diagnosis.
#define ARENA_ANDROID_LOG(...) __android_log_print(ANDROID_LOG_INFO, "citra", __VA_ARGS__)
#else
#define ARENA_ANDROID_LOG(...)
#endif

namespace Common {

#if CITRA_FASTMEM_ARENA_SUPPORTED

namespace {

constexpr std::size_t HUGE_PAGE_SIZE = 2ULL * 1024 * 1024;

/// Current kernel policy for THP on shared memory (memfd/tmpfs), e.g. "always [advise] never".
std::string ReadShmemThpPolicy() {
    std::ifstream f("/sys/kernel/mm/transparent_hugepage/shmem_enabled");
    std::string line;
    if (!f || !std::getline(f, line)) {
        return "unavailable";
    }
    return line;
}

/// Hints the kernel to back a mapping with 2 MiB pages. Only meaningful when the host address,
/// file offset and length are all 2 MiB aligned, and when the kernel allows shmem THP
/// (shmem_enabled = advise/always). Failure is harmless; the mapping stays on 4 KiB pages.
void AdviseHugePages(void* addr, std::size_t size, std::size_t file_offset) {
    const auto start = reinterpret_cast<std::uintptr_t>(addr);
    if ((start % HUGE_PAGE_SIZE) != 0 || (file_offset % HUGE_PAGE_SIZE) != 0 ||
        size < HUGE_PAGE_SIZE) {
        return;
    }
    const std::size_t aligned_size = size & ~(HUGE_PAGE_SIZE - 1);
    madvise(addr, aligned_size, MADV_HUGEPAGE);
}

// 4 GiB of guest address space plus a guard region so that the largest possible guest access
// starting at 0xFFFFFFFF still lands inside the reservation instead of unrelated mappings.
constexpr u64 GUEST_ADDRESS_SPACE_SIZE = 1ULL << 32;
constexpr u64 ARENA_GUARD_SIZE = 64 * 1024;

int CreateBackingFile(std::size_t size) {
    // memfd_create has no libc wrapper before API 30, but the syscall exists on every kernel
    // this app can run on (minSdk 24 implies kernel >= 3.18).
    const int fd = static_cast<int>(syscall(__NR_memfd_create, "citra_fastmem", 1 /* MFD_CLOEXEC */));
    if (fd < 0) {
        return -1;
    }
    if (ftruncate(fd, static_cast<off_t>(size)) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

} // Anonymous namespace

FastmemArena::FastmemArena(std::size_t backing_size_) {
    backing_fd = CreateBackingFile(backing_size_);
    if (backing_fd < 0) {
        LOG_WARNING(Common_Memory, "Fastmem arena disabled: memfd_create failed");
        ARENA_ANDROID_LOG("[Fastmem] arena disabled: memfd_create failed (errno=%d)", errno);
        return;
    }

    void* backing =
        mmap(nullptr, backing_size_, PROT_READ | PROT_WRITE, MAP_SHARED, backing_fd, 0);
    if (backing == MAP_FAILED) {
        LOG_WARNING(Common_Memory, "Fastmem arena disabled: backing mmap failed");
        ARENA_ANDROID_LOG("[Fastmem] arena disabled: backing mmap failed (errno=%d)", errno);
        close(backing_fd);
        backing_fd = -1;
        return;
    }

    // Over-reserve by one huge page so the arena base itself can be 2 MiB aligned; without that,
    // MADV_HUGEPAGE could never apply to any guest mapping.
    const std::size_t reserve_size = GUEST_ADDRESS_SPACE_SIZE + ARENA_GUARD_SIZE + HUGE_PAGE_SIZE;
    void* arena = mmap(nullptr, reserve_size, PROT_NONE,
                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    if (arena == MAP_FAILED) {
        LOG_WARNING(Common_Memory, "Fastmem arena disabled: 4 GiB reservation failed");
        ARENA_ANDROID_LOG("[Fastmem] arena disabled: 4 GiB reservation failed (errno=%d)", errno);
        munmap(backing, backing_size_);
        close(backing_fd);
        backing_fd = -1;
        return;
    }

    backing_size = backing_size_;
    backing_base = static_cast<u8*>(backing);
    arena_raw = static_cast<u8*>(arena);
    arena_base = reinterpret_cast<u8*>(
        (reinterpret_cast<std::uintptr_t>(arena) + HUGE_PAGE_SIZE - 1) & ~(HUGE_PAGE_SIZE - 1));
    arena_reserved_size = reserve_size;
    // NOTE: MADV_HUGEPAGE on this PROT_NONE reservation is useless: every Map() replaces the VMA
    // with a fresh MAP_FIXED mapping, which drops the hint. The hint is applied per mapping below.
    ARENA_ANDROID_LOG("[Fastmem] shmem THP policy: %s", ReadShmemThpPolicy().c_str());
    LOG_INFO(Common_Memory, "Fastmem arena ready: backing={} MiB arena_base={}",
             backing_size / (1024 * 1024), static_cast<void*>(arena_base));
    ARENA_ANDROID_LOG("[Fastmem] arena ready: backing=%zu MiB arena_base=%p",
                      backing_size / (1024 * 1024), static_cast<void*>(arena_base));
}

FastmemArena::~FastmemArena() {
    if (arena_raw != nullptr) {
        munmap(arena_raw, arena_reserved_size);
    }
    if (backing_base != nullptr) {
        munmap(backing_base, backing_size);
    }
    if (backing_fd >= 0) {
        close(backing_fd);
    }
}

void FastmemArena::Map(u64 guest_addr, std::size_t backing_offset, std::size_t size) {
    if (!IsValid()) {
        return;
    }
    void* result = mmap(arena_base + guest_addr, size, PROT_READ | PROT_WRITE,
                        MAP_SHARED | MAP_FIXED, backing_fd, static_cast<off_t>(backing_offset));
    if (result == MAP_FAILED) {
        LOG_ERROR(Common_Memory, "Fastmem arena map failed: guest={:#x} size={:#x}", guest_addr,
                  size);
        return;
    }
    AdviseHugePages(result, size, backing_offset);
}

void FastmemArena::Unmap(u64 guest_addr, std::size_t size) {
    if (!IsValid()) {
        return;
    }
    void* result = mmap(arena_base + guest_addr, size, PROT_NONE,
                        MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE | MAP_FIXED, -1, 0);
    if (result == MAP_FAILED) {
        LOG_ERROR(Common_Memory, "Fastmem arena unmap failed: guest={:#x} size={:#x}", guest_addr,
                  size);
    }
}

void FastmemArena::UnmapAll() {
    Unmap(0, GUEST_ADDRESS_SPACE_SIZE);
}

#else

FastmemArena::FastmemArena(std::size_t) {}
FastmemArena::~FastmemArena() = default;
void FastmemArena::Map(u64, std::size_t, std::size_t) {}
void FastmemArena::Unmap(u64, std::size_t) {}
void FastmemArena::UnmapAll() {}

#endif

} // namespace Common

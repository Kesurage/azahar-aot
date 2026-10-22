// Copyright 2026 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#pragma once

#include <cstddef>
#include "common/common_types.h"

namespace Common {

/**
 * A contiguous 4 GiB virtual-address-space mirror of the emulated 32-bit guest address space,
 * used as a Dynarmic fastmem arena. Guest RAM backings are allocated from one shared-memory
 * file so mapped guest pages can be mirrored into the arena at their guest virtual address:
 * host_address = ArenaBase() + guest_vaddr. Unmapped (or rasterizer-cached) guest pages stay
 * PROT_NONE in the arena, so JIT accesses fault and Dynarmic falls back to the page table.
 *
 * Only implemented for 64-bit Linux/Android hosts; elsewhere IsValid() is always false and
 * callers must keep using the page-table path.
 */
class FastmemArena {
public:
    /// Creates a backing file of `backing_size` bytes and reserves the 4 GiB arena.
    explicit FastmemArena(std::size_t backing_size);
    ~FastmemArena();

    FastmemArena(const FastmemArena&) = delete;
    FastmemArena& operator=(const FastmemArena&) = delete;

    /// True if both the backing file and the arena reservation were successfully created.
    bool IsValid() const {
        return backing_base != nullptr;
    }

    /// Base of the always-mapped RW view of the backing file (guest RAM lives here).
    u8* BackingBase() const {
        return backing_base;
    }

    /// Base of the 4 GiB guest-address mirror.
    u8* ArenaBase() const {
        return arena_base;
    }

    /// Mirrors `size` bytes of the backing file at `backing_offset` to guest address
    /// `guest_addr` inside the arena. Addresses/sizes must be page-aligned.
    void Map(u64 guest_addr, std::size_t backing_offset, std::size_t size);

    /// Returns `size` bytes at guest address `guest_addr` to the inaccessible (PROT_NONE)
    /// state so JIT accesses there fault into the fallback path.
    void Unmap(u64 guest_addr, std::size_t size);

    /// Resets the entire arena to the inaccessible state.
    void UnmapAll();

private:
    int backing_fd = -1;
    std::size_t backing_size = 0;
    u8* backing_base = nullptr;
    u8* arena_raw = nullptr; ///< Start of the actual reservation (arena_base is 2 MiB aligned)
    u8* arena_base = nullptr;
    std::size_t arena_reserved_size = 0;
};

} // namespace Common

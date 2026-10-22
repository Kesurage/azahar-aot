// Copyright 2013 Dolphin Emulator Project / 2014 Citra Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#include <algorithm>
#include <cerrno>
#include <fstream>
#include <string>
#include <vector>
#include <fmt/format.h>

#include "common/error.h"
#include "common/logging/log.h"
#include "common/thread.h"
#ifdef __APPLE__
#include <mach/mach.h>
#elif defined(_WIN32)
#include <windows.h>
#include "common/string_util.h"
#else
#if defined(__Bitrig__) || defined(__DragonFly__) || defined(__FreeBSD__) || defined(__OpenBSD__)
#include <pthread_np.h>
#else
#include <pthread.h>
#endif
#include <sched.h>
#endif
#ifndef _WIN32
#include <unistd.h>
#endif

#ifdef __FreeBSD__
#define cpu_set_t cpuset_t
#endif

namespace Common {

#ifdef _WIN32

void SetCurrentThreadPriority(ThreadPriority new_priority) {
    auto handle = GetCurrentThread();
    int windows_priority = 0;
    switch (new_priority) {
    case ThreadPriority::Low:
        windows_priority = THREAD_PRIORITY_BELOW_NORMAL;
        break;
    case ThreadPriority::Normal:
        windows_priority = THREAD_PRIORITY_NORMAL;
        break;
    case ThreadPriority::High:
        windows_priority = THREAD_PRIORITY_ABOVE_NORMAL;
        break;
    case ThreadPriority::VeryHigh:
        windows_priority = THREAD_PRIORITY_HIGHEST;
        break;
    case ThreadPriority::Critical:
        windows_priority = THREAD_PRIORITY_TIME_CRITICAL;
        break;
    default:
        windows_priority = THREAD_PRIORITY_NORMAL;
        break;
    }
    SetThreadPriority(handle, windows_priority);
}

#else

void SetCurrentThreadPriority(ThreadPriority new_priority) {
    pthread_t this_thread = pthread_self();

    const auto scheduling_type = SCHED_OTHER;
    s32 max_prio = sched_get_priority_max(scheduling_type);
    s32 min_prio = sched_get_priority_min(scheduling_type);
    u32 level = std::max(static_cast<u32>(new_priority) + 1, 4U);

    struct sched_param params;
    if (max_prio > min_prio) {
        params.sched_priority = min_prio + ((max_prio - min_prio) * level) / 4;
    } else {
        params.sched_priority = min_prio - ((min_prio - max_prio) * level) / 4;
    }

    pthread_setschedparam(this_thread, scheduling_type, &params);
}

#endif

#ifdef _MSC_VER

// Sets the debugger-visible name of the current thread.
void SetCurrentThreadName(const char* name) {
    SetThreadDescription(GetCurrentThread(), UTF8ToUTF16W(name).data());
}

#else // !MSVC_VER, so must be POSIX threads

// MinGW with the POSIX threading model does not support pthread_setname_np
#if !defined(_WIN32) || defined(_MSC_VER)
void SetCurrentThreadName(const char* name) {
#ifdef __APPLE__
    pthread_setname_np(name);
#elif defined(__Bitrig__) || defined(__DragonFly__) || defined(__FreeBSD__) || defined(__OpenBSD__)
    pthread_set_name_np(pthread_self(), name);
#elif defined(__NetBSD__)
    pthread_setname_np(pthread_self(), "%s", (void*)name);
#elif defined(__linux__)
    // Linux limits thread names to 15 characters and will outright reject any
    // attempt to set a longer name with ERANGE.
    std::string truncated(name, std::min(strlen(name), static_cast<std::size_t>(15)));
    if (int e = pthread_setname_np(pthread_self(), truncated.c_str())) {
        errno = e;
        LOG_ERROR(Common, "Failed to set thread name to '{}': {}", truncated, GetLastErrorMsg());
    }
#else
    pthread_setname_np(pthread_self(), name);
#endif
}
#endif

#if defined(_WIN32)
void SetCurrentThreadName(const char*) {
    // Do Nothing on MingW
}
#endif

#endif

#if defined(__linux__)

namespace {

/// Returns each core's relative performance, or an empty vector if unavailable. Prefers the
/// scheduler's cpu_capacity, falling back to cpuinfo_max_freq.
std::vector<u64> ReadCoreCapacities() {
    std::vector<u64> caps;
    const long n = sysconf(_SC_NPROCESSORS_CONF);
    for (long i = 0; i < n && i < CPU_SETSIZE; ++i) {
        u64 value = 0;
        for (const char* file : {"cpu_capacity", "cpufreq/cpuinfo_max_freq"}) {
            std::ifstream f(fmt::format("/sys/devices/system/cpu/cpu{}/{}", i, file));
            if (f >> value && value != 0) {
                break;
            }
            value = 0;
        }
        caps.push_back(value);
    }
    return caps;
}

} // namespace

bool SetCurrentThreadAffinity(CpuAffinityMode mode) {
    if (mode == CpuAffinityMode::Default) {
        return false;
    }
    const std::vector<u64> caps = ReadCoreCapacities();
    if (caps.empty() || std::find(caps.begin(), caps.end(), 0) != caps.end()) {
        LOG_WARNING(Common, "CPU affinity: core topology unavailable, leaving default");
        return false;
    }
    const u64 max_cap = *std::max_element(caps.begin(), caps.end());
    const u64 min_cap = *std::min_element(caps.begin(), caps.end());
    if (max_cap == min_cap) {
        return false; // homogeneous CPU, nothing to choose between
    }

    // Tiers: prime = top capacity; little = lowest capacity; big = everything in between + prime.
    cpu_set_t set;
    CPU_ZERO(&set);
    int selected = 0;
    for (std::size_t i = 0; i < caps.size(); ++i) {
        bool pick = false;
        switch (mode) {
        case CpuAffinityMode::PrimeOnly:
            pick = caps[i] == max_cap;
            break;
        case CpuAffinityMode::LittleOnly:
            pick = caps[i] == min_cap;
            break;
        case CpuAffinityMode::BigAndPrime:
            pick = caps[i] != min_cap;
            break;
        default:
            break;
        }
        if (pick) {
            CPU_SET(static_cast<int>(i), &set);
            ++selected;
        }
    }
    if (selected == 0) {
        return false;
    }
    // sched_setaffinity(0) targets the calling thread on Linux/Android.
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        LOG_WARNING(Common, "CPU affinity: sched_setaffinity failed (errno={})", errno);
        return false;
    }
    LOG_INFO(Common, "CPU affinity: pinned thread to {} core(s) (mode {})", selected,
             static_cast<s32>(mode));
    return true;
}

#else

bool SetCurrentThreadAffinity(CpuAffinityMode) {
    return false;
}

#endif

} // namespace Common

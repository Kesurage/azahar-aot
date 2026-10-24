#include "arm_aot.h"

#include <filesystem>

#include <algorithm>
#include <atomic>
#include <functional>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <map>
#include <mutex>
#include <set>
#include <type_traits>
#include <vector>
#include <fmt/format.h>

#include "aot_library.h"
#include "common/arch.h"
#include "common/file_util.h"
#include "common/logging/log.h"
#include "common/settings.h"
#include "core/arm/dyncom/arm_dyncom.h"
#include "core/arm/exclusive_monitor.h"
#if CITRA_ARCH(x86_64) || CITRA_ARCH(arm64)
#include "core/arm/dynarmic/arm_dynarmic.h"
#endif
#include "core/core.h"
#include "core/core_timing.h"
#include "core/hle/kernel/kernel.h"
#include "core/hle/kernel/svc.h"
#include "core/hle/kernel/thread.h"
#include "core/hle/service/ldr_ro/ldr_ro.h"
#include "core/loader/loader.h"
#include "core/memory.h"

namespace Aot {

namespace {

constexpr u32 CPSR_N = 1u << 31, CPSR_Z = 1u << 30, CPSR_C = 1u << 29, CPSR_V = 1u << 28,
              CPSR_Q = 1u << 27, CPSR_T = 1u << 5, CPSR_GE_MASK = 0xFu << 16;
constexpr u32 CPSR_MISC_MASK = ~(CPSR_N | CPSR_Z | CPSR_C | CPSR_V | CPSR_Q | CPSR_T | CPSR_GE_MASK);
// Longest basic block seen so far is 1603 instructions; when the time slice is smaller than
// the next block the backend lends the code this much extra budget (see Run()).
constexpr s32 kBlockAllowance = 8192;

// Where the generated code was missing, for the next 3dsrecomp build (same file and format as
// Zakuro's: `<library>.hints`, one hex address a line, odd for Thumb; 3dsrecomp's build reads
// the file next to the library it is replacing). Shared by the cores.
struct Hints {
    std::mutex mutex;
    std::string path;
    std::set<u32> seen;
    size_t saved = 0;
    void Load(const std::string& hints_path) {
        std::scoped_lock lock{mutex};
        path = hints_path;
        seen.clear();
        std::ifstream in(path);
        for (std::string line; std::getline(in, line);) {
            if (line.empty() || line[0] == '#')
                continue;
            try {
                seen.insert(static_cast<u32>(std::stoul(line, nullptr, 16)));
            } catch (...) {
            }
        }
        saved = seen.size();
    }
    void Add(u32 address) {
        std::scoped_lock lock{mutex};
        if (!path.empty())
            seen.insert(address);
    }
    void Save() {
        std::scoped_lock lock{mutex};
        if (path.empty() || seen.size() == saved)
            return;
        const std::string partial = path + ".new";
        {
            std::ofstream out(partial, std::ios::trunc);
            out << "# where Azahar-AOT ran this title's code on the fallback CPU, odd for Thumb, "
                   "which the next 3dsrecomp build recompiles\n";
            for (u32 a : seen)
                out << fmt::format("{:08X}\n", a);
            if (!out)
                return;
        }
        std::error_code ec;
        std::filesystem::rename(partial, path, ec);
        if (ec)
            LOG_WARNING(Core_ARM11, "AOT: could not write {}: {}", path, ec.message());
        else
            saved = seen.size();
    }
};
Hints hints;
// Set by the app (SetHintsPath): where the hints go when the library itself is somewhere the
// player can't reach (Android: a copy in the app's private storage).
std::string hints_path_override;

struct Global {
    std::string dir;
    std::string path; // exact library, if set
    bool resolved = false;
    std::unique_ptr<Library> library;
    // CRO modules loaded before the library was resolved
    std::map<std::string, u32> modules;
};
Global g;

// AZAHAR_AOT_SVC_CONTINUE=0 returns to the core loop after every system call, as before; the
// default carries on with the slice unless the kernel asked for a reschedule.
bool SvcContinue() {
    static const bool on = [] {
        const char* e = std::getenv("AZAHAR_AOT_SVC_CONTINUE");
        return !(e && e[0] == '0');
    }();
    return on;
}

// Skip-idle (ARM_AOT::SpinSkip) is a runtime toggle: the app's settings call Aot::SetSkipIdle.
// The environment variable AZAHAR_AOT_IDLE=0 still
// forces it off whatever the toggle says (handy for a hang that needs ruling out).
std::atomic<bool> skip_idle_option{true};

// told once a library is chosen (the port installs its hooks there)
std::function<void()> library_loaded;

bool SpinEnabled() {
    static const bool env_allows = [] {
        const char* e = std::getenv("AZAHAR_AOT_IDLE");
        return !(e && e[0] == '0');
    }();
    return env_allows && skip_idle_option.load(std::memory_order_relaxed);
}

// Slice ends in a row that must match before slices are skipped, how many are skipped before
// one runs to check again, and how many slice ends back a match counts.
constexpr u32 kSpinNeeded = 3;
constexpr u64 kSpinFresh = 16;
// A watch gives up after this many slices without the registers coming back to where it began
// (a loop returns within kSpinFresh slice ends: that is what made it a candidate), or when it
// has written more distinct bytes than this.
constexpr u32 kIdleWatchMax = 64;
// ... and a proof needs this many slices in a row that ended in states already seen.
constexpr u32 kIdleSettled = 8;
constexpr u32 kIdleCycleMax = 32;
constexpr std::size_t kIdleMaxBytes = 8192;
constexpr u32 kIdleBackoffMin = 8, kIdleBackoffMax = 512;
// Stack the loop's own calls push and pop again, below sp: not looked at when checking writes.
constexpr u32 kIdleStackScratch = 0x800;
// Where the I/O registers of the GPU, LCD and so on are mapped: a store there is not undoable.
constexpr u32 kIoStart = 0x1EC00000, kIoEnd = 0x1F000000;

// A page table with no page in it, which the write side of the context points at while a loop is
// watched: every store then goes to the host callbacks, where it is logged.
u8* const* NoPages() {
    static const std::vector<u8*> pages(1u << 20, nullptr);
    return pages.data();
}

std::string LibraryDir() {
    if (!g.dir.empty())
        return g.dir;
    if (const char* env = std::getenv("AZAHAR_AOT_DIR"))
        return env;
    return FileUtil::GetUserPath(FileUtil::UserPath::UserDir) + "aot";
}

} // namespace

} // namespace Aot

// The C contract of the generated code; included after Azahar's headers because it defines
// short macros (C_EQ, LIKELY, ...).
#include "recomp.h"

namespace Aot {

namespace {

ARM_AOT& Self(Context* ctx) {
    return *static_cast<ARM_AOT*>(ctx->user);
}

uint8_t HRead8(Context* c, uint32_t a) {
    return static_cast<uint8_t>(Self(c).SlowRead(a, 1));
}
uint16_t HRead16(Context* c, uint32_t a) {
    return static_cast<uint16_t>(Self(c).SlowRead(a, 2));
}
uint32_t HRead32(Context* c, uint32_t a) {
    return Self(c).SlowRead(a, 4);
}
void HWrite8(Context* c, uint32_t a, uint8_t v) {
    Self(c).SlowWrite(a, v, 1);
}
void HWrite16(Context* c, uint32_t a, uint16_t v) {
    Self(c).SlowWrite(a, v, 2);
}
void HWrite32(Context* c, uint32_t a, uint32_t v) {
    Self(c).SlowWrite(a, v, 4);
}
void HInterpret(Context* c, uint32_t address, uint32_t opcode) {
    Self(c).InterpretOne(address, opcode);
}
Code HLookup(Context* c, uint32_t address) {
    return reinterpret_cast<Code>(Self(c).LookupCode(address));
}

const Host kHost{HRead8, HRead16, HRead32, HWrite8, HWrite16, HWrite32, HInterpret, HLookup};

} // namespace

// ── construction ────────────────────────────────────────────────────────────────────────

ARM_AOT::ARM_AOT(Core::System& system_, Memory::MemorySystem& memory_, u32 core_id,
                 std::shared_ptr<Core::Timing::Timer> timer_, Core::ExclusiveMonitor* monitor)
    : ARM_Interface(core_id, timer_), system(system_), memory(memory_),
      svc_context(std::make_unique<Kernel::SVCContext>(system_)), ctx(std::make_unique<Context>()) {
#if CITRA_ARCH(x86_64) || CITRA_ARCH(arm64)
    if (!monitor) {
        own_monitor = Core::MakeExclusiveMonitor(memory, 1);
        monitor = own_monitor.get();
    }
    fallback = std::make_unique<Core::ARM_Dynarmic>(system, memory, core_id, timer_, *monitor);
#else
    (void)monitor;
    fallback = std::make_unique<Core::ARM_DynCom>(system, memory, USER32MODE, core_id, timer_);
#endif
    *ctx = Context{};
    ctx->host = &kHost;
    ctx->user = this;
    ctx->vfp = vfp.data();
    ctx->fpscr = &fpscr;
    SetPageTable(memory.GetCurrentPageTable());
}

ARM_AOT::~ARM_AOT() = default;

void ARM_AOT::Resolve() {
    resolved = true;
    if (!g.resolved) {
        g.resolved = true;
        u64 id = 0;
        system.GetAppLoader().ReadProgramId(id);
        // The library is named after the title the code was recompiled from; an update or
        // DLC title id (0004000E...) falls back to the base game's.
        std::vector<u64> candidates{id};
        if ((id >> 32) != 0x00040000)
            candidates.push_back((id & 0xFFFFFFFFull) | 0x0004000000000000ull);
        std::string path_override = g.path;
        if (path_override.empty())
          if (const char* env = std::getenv("AZAHAR_AOT_LIBRARY"))
            path_override = env;
        std::string last_error;
        for (u64 candidate : candidates) {
            const std::string path =
                !path_override.empty()
                    ? path_override
                    : fmt::format("{}/{:016X}.{}", LibraryDir(), candidate,
#if defined(_WIN32)
                                  "dll"
#elif defined(__APPLE__)
                                  "dylib"
#else
                                  "so"
#endif
                    );
            // plain stat: Azahar's FileUtil on Android treats paths as relative to the user
            // folder, which would hide a library staged in the app's private storage
            std::error_code exists_ec;
            if (!std::filesystem::exists(path, exists_ec)) {
                LOG_INFO(Core_ARM11, "AOT: no library at {}", path);
                continue;
            }
            std::string error;
            auto lib = Library::Open(path, error);
            if (!lib) {
                LOG_ERROR(Core_ARM11, "AOT: cannot use {}: {}", path, error);
                continue;
            }
            const auto read = [this](u32 a, u8* out, size_t n) {
                memory.ReadBlock(a, out, n);
            };
            const size_t stale = lib->Check(0, 0, read);
            LOG_INFO(Core_ARM11, "AOT: running recompiled code from {} ({}){}", path,
                     lib->Describe(),
                     stale ? fmt::format(", {} functions changed since recompiling, they run on "
                                         "the fallback CPU",
                                         stale)
                           : "");
            if (lib->Generation() < 3)
                LOG_WARNING(Core_ARM11, "AOT: older 3dsrecomp generation; recompile for speed");
            if (!lib->ChecksItself())
                LOG_WARNING(Core_ARM11, "AOT: library cannot detect changed code (mods/updates "
                                        "will not be noticed)");
            for (const auto& [name, base] : g.modules)
                lib->Place(name, base, read);
            hints.Load(!hints_path_override.empty()
                           ? hints_path_override
                           : std::filesystem::path(path).replace_extension(".hints").string());
            LOG_INFO(Core_ARM11, "AOT: skip idle {}", skip_idle_option.load() ? "on" : "off");
            g.library = std::move(lib);
            if (library_loaded)
                library_loaded();
            break;
        }
        if (!g.library)
            LOG_INFO(Core_ARM11, "AOT: no recompiled library for title {:016X} in {}, using the "
                                 "regular CPU", id, LibraryDir());
    }
    if (!g.library) {
        // Hand the state over once, then forward everything.
        ThreadContext tc;
        ToThreadContext(tc);
        fallback->LoadContext(tc);
        fallback->SetCP15Register(CP15_THREAD_UPRW, tpidrurw);
        fallback->SetCP15Register(CP15_THREAD_URO, tpidruro);
        fallback->SetVFPSystemReg(VFP_FPEXC, fpexc);
        delegate_all = true;
    }
}

// ── execution ───────────────────────────────────────────────────────────────────────────

void ARM_AOT::Run() {
    if (break_flag)
        return;
    if (!resolved)
        Resolve();
    ++stats.runs;
    MaybeReportStats();
    if (delegate_all) {
        fallback->Run();
        return;
    }
    Library& lib = *g.library;
    const s64 slice = GetTimer().GetDowncount();
    if (slice <= 0)
        return;
    if (SpinSkip(slice))
        return;
    slice_clean = true;
    granted = std::min<s64>(slice, 0x7FFFFFFF - kBlockAllowance);
    ctx->budget = static_cast<s32>(granted);
    // Another core may have written memory since this one last ran: don't let an old
    // reservation make a store-exclusive succeed.
    ctx->exclusive = 0;
    reschedule = false;
    ctx->tls = tpidruro;

    while (ctx->budget > 0 && !break_flag) {
        Code code = lib.Lookup(ctx->r[15] | (ctx->thumb ? 1u : 0u));
        if (!code) {
            if (!RunFallbackBatch())
                break;
            continue;
        }
        const s32 before = ctx->budget;
        ctx->exit = EXIT_NONE;
        ctx->depth = 0;
        code(ctx.get());
        switch (ctx->exit) {
        case EXIT_SVC:
            // A system call may block, switch threads or reprogram timers. When the kernel
            // asked for a reschedule (or the slice is spent) end the slice and let the core
            // loop decide what runs next; otherwise carry on from r15, which an entry covers.
            if (!HandleSvc())
                return;
            break;
        case EXIT_BUDGET:
            if (ctx->budget == before) {
                // The next block is bigger than what is left of the slice. Lend the code
                // enough to run it; the overshoot is charged to the timer afterwards.
                slice_clean = false;
                ctx->budget += kBlockAllowance;
                granted += kBlockAllowance;
                ctx->exit = EXIT_NONE;
                ctx->depth = 0;
                Code again = lib.Lookup(ctx->r[15] | (ctx->thumb ? 1u : 0u));
                if (again)
                    again(ctx.get());
                if (ctx->exit == EXIT_SVC) {
                    if (!HandleSvc())
                        return;
                } else if (ctx->exit == EXIT_BUDGET && !RunFallbackBatch())
                    goto done;
            } else {
                goto done; // slice used up
            }
            break;
        default:
            break; // EXIT_NONE / EXIT_UNWIND: continue from r15 (lookup or fallback)
        }
    }
done:
    SpinObserve();
    SyncTicks();
}

// ── idle-loop skipping ──────────────────────────────────────────────────────────────────

ARM_AOT::Snapshot ARM_AOT::TakeSnapshot() const {
    Snapshot s;
    for (int i = 0; i < 16; ++i)
        s.r[i] = ctx->r[i];
    for (int i = 0; i < 32; ++i)
        s.vfp[i] = vfp[i];
    s.flags = (ctx->n ? 1u : 0) | (ctx->z ? 2u : 0) | (ctx->c ? 4u : 0) | (ctx->v ? 8u : 0) |
              (ctx->q ? 16u : 0) | (ctx->thumb ? 32u : 0) | ((ctx->ge & 0xFu) << 8);
    s.fpscr = fpscr;
    s.tls = ctx->tls;
    return s;
}

void ARM_AOT::SpinReset() {
    spin_filled = spin_next = 0;
    spin_streak = 0;
    slice_clean = false;
    IdleStop(false);
}

void ARM_AOT::IdleBegin(Idle mode) {
    idle = mode;
    idle_touched.clear();
    if (mode == Idle::Watching)
        idle_old.clear();
    idle_slices = 0;
    idle_quiet = 0;
    idle_failed = false;
    // from here every store takes the slow path (SlowWrite), which logs it
    ctx->write_pages = NoPages();
    if (mode == Idle::Watching)
        ++stats.idle_watches;
}

void ARM_AOT::IdleStop(bool failed) {
    if (idle == Idle::Off && !failed)
        return;
    if (idle != Idle::Off && failed)
        ++stats.idle_failures;
    idle = Idle::Off;
    idle_old.clear();
    idle_touched.clear();
    idle_cycle.clear();
    ctx->write_pages = ctx->read_pages;
    if (failed) {
        idle_backoff = std::clamp(idle_backoff * 2, kIdleBackoffMin, kIdleBackoffMax);
        idle_cooldown = idle_backoff;
    }
}

void ARM_AOT::NoteIdleWrite(u32 address, u32 size) {
    if (address >= kIoStart && address < kIoEnd) {
        idle_failed = true;
        return;
    }
    for (u32 i = 0; i < size; ++i) {
        const u32 a = address + i;
        if (idle == Idle::Checking) {
            if (idle_touched.size() >= kIdleMaxBytes) {
                idle_failed = true;
                return;
            }
            idle_touched.insert(a);
        } else if (!idle_old.contains(a)) {
            if (idle_old.size() >= kIdleMaxBytes) {
                idle_failed = true;
                return;
            }
            idle_old.emplace(a, memory.Read8(a));
        }
    }
}

int ARM_AOT::IdleFind(const Snapshot& s) const {
    for (std::size_t i = 0; i < idle_cycle.size(); ++i) {
        if (idle_cycle[i].state == s)
            return static_cast<int>(i);
    }
    return -1;
}

bool ARM_AOT::IdleMatches(std::size_t k) {
    const u32 sp = ctx->r[13];
    const u32 low = sp > kIdleStackScratch ? sp - kIdleStackScratch : 0;
    const auto& known = idle_cycle[k].bytes;
    for (const auto& [address, old] : idle_old) {
        if (address >= low && address < sp)
            continue;
        const auto it = known.find(address);
        if (memory.Read8(address) != (it != known.end() ? it->second : old))
            return false;
    }
    return true;
}

/// A slice ended. Off: a slice that ran generated code from start to end, with no system call,
/// no fallback CPU and no reschedule, and ended with the registers an earlier slice ended with,
/// makes the thread a candidate (it goes round a loop that keeps nothing in registers from turn
/// to turn). It is then watched, see the header, before anything is skipped.
void ARM_AOT::SpinObserve() {
    if (!SpinEnabled()) {
        IdleStop(false);
        return;
    }
    if (!slice_clean || break_flag || reschedule) {
        // the slice was not the loop alone; a watch it spoiled counts as failed
        if (IdleLogging())
            IdleStop(true);
        SpinReset();
        return;
    }
    ++spin_serial;
    Snapshot now = TakeSnapshot();
    switch (idle) {
    case Idle::Watching: {
        ++idle_slices;
        if (idle_failed || idle_slices > kIdleWatchMax) {
            IdleStop(true);
            return;
        }
        const int k = IdleFind(now);
        if (k < 0) {
            // a state the loop ends slices in (slice lengths differ, so there are several), with
            // the bytes it has written so far as they are in it
            if (idle_cycle.size() >= kIdleCycleMax) {
                IdleStop(true);
                return;
            }
            CycleState state{now, {}};
            for (const auto& [address, old] : idle_old)
                state.bytes.emplace(address, memory.Read8(address));
            idle_cycle.push_back(std::move(state));
            idle_quiet = 0;
            return;
        }
        // a state seen before must find the bytes as they were in it, or the loop's memory
        // depends on more than its registers
        if (!IdleMatches(static_cast<std::size_t>(k))) {
            IdleStop(true);
            return;
        }
        ++idle_quiet;
        if (k == 0 && idle_quiet >= kIdleSettled) {
            // back where the watch began, no new state for a while, nothing changed: the loop's
            // whole cycle is behind us
            ctx->write_pages = ctx->read_pages;
            idle = Idle::Proven;
            idle_backoff = 0;
            ++stats.idle_proofs;
        }
        return;
    }
    case Idle::Checking: {
        // the slice after a skip. A store to a byte the watch never saw written is a change.
        if (idle_failed) {
            IdleStop(true);
            return;
        }
        const u32 sp = ctx->r[13];
        const u32 low = sp > kIdleStackScratch ? sp - kIdleStackScratch : 0;
        for (const u32 address : idle_touched) {
            if (!idle_old.contains(address) && !(address >= low && address < sp)) {
                IdleStop(true);
                return;
            }
        }
        const int k = IdleFind(now);
        if (k < 0) {
            // an end the watch had not seen: not skipped on that alone (a loop counting in a
            // register never repeats); watched again from here
            IdleStop(false);
            return;
        }
        if (!IdleMatches(static_cast<std::size_t>(k))) {
            IdleStop(true);
            return;
        }
        ctx->write_pages = ctx->read_pages;
        idle = Idle::Proven;
        return;
    }
    case Idle::Proven:
        return; // the slice was skipped, nothing ran
    case Idle::Off:
        break;
    }
    if (idle_cooldown)
        --idle_cooldown;
    for (std::size_t i = 0; i < spin_filled; ++i) {
        if (spin_serial - spin_ring[i].serial <= kSpinFresh && spin_ring[i] == now) {
            spin_ring[i].serial = spin_serial;
            ++spin_streak;
            if (spin_streak >= kSpinNeeded && idle_cooldown == 0 &&
                !system.Kernel().GetThreadManager(GetID()).HaveReadyThreads()) {
                idle_cycle.clear();
                idle_cycle.push_back(CycleState{now, {}});
                IdleBegin(Idle::Watching);
            }
            return;
        }
    }
    spin_streak = 0;
    now.serial = spin_serial;
    spin_ring[spin_next] = now;
    spin_next = (spin_next + 1) % kSpinRing;
    spin_filled = std::min(spin_filled + 1, kSpinRing);
}

/// The slice after a proven one's is the next event's: skipping this one costs the loop nothing,
/// unless another thread could run.
bool ARM_AOT::SpinSkip(s64 slice) {
    if (!SpinEnabled() || idle != Idle::Proven)
        return false;
    if (system.Kernel().GetThreadManager(GetID()).HaveReadyThreads()) {
        IdleStop(false);
        return false;
    }
    ++stats.spin_slices;
    const s64 ticks = std::min<s64>(slice, 0x7FFFFFFF - kBlockAllowance);
    GetTimer().AddTicks(static_cast<u64>(ticks));
    stats.spin_ticks += static_cast<u64>(ticks);
    IdleBegin(Idle::Checking); // the next slice runs, logged
    return true;
}

namespace {
/// The 3DS system calls a game makes most, by number (others show as hex only).
const char* SvcName(u32 n) {
    static const char* const names[] = {
        "?", "ControlMemory", "QueryMemory", "ExitProcess", "GetProcessAffinityMask",
        "SetProcessAffinityMask", "GetProcessIdealProcessor", "SetProcessIdealProcessor",
        "CreateThread", "ExitThread", "SleepThread", "GetThreadPriority", "SetThreadPriority",
        "GetThreadAffinityMask", "SetThreadAffinityMask", "GetThreadIdealProcessor",
        "SetThreadIdealProcessor", "GetCurrentProcessorNumber", "Run", "CreateMutex",
        "ReleaseMutex", "CreateSemaphore", "ReleaseSemaphore", "CreateEvent", "SignalEvent",
        "ClearEvent", "CreateTimer", "SetTimer", "CancelTimer", "ClearTimer", "CreateMemoryBlock",
        "MapMemoryBlock", "UnmapMemoryBlock", "CreateAddressArbiter", "ArbitrateAddress",
        "CloseHandle", "WaitSynchronization1", "WaitSynchronizationN", "SignalAndWait",
        "DuplicateHandle", "GetSystemTick", "GetHandleInfo", "GetSystemInfo", "GetProcessInfo",
        "GetThreadInfo", "ConnectToPort", "SendSyncRequest1", "SendSyncRequest2",
        "SendSyncRequest3", "SendSyncRequest4", "SendSyncRequest", "OpenProcess", "OpenThread",
        "GetProcessId", "GetProcessIdOfThread", "GetThreadId", "GetResourceLimit",
        "GetResourceLimitLimitValues", "GetResourceLimitCurrentValues", "GetThreadContext",
        "Break", "OutputDebugString"};
    return n < std::size(names) ? names[n] : "?";
}
} // namespace

bool ARM_AOT::HandleSvc() {
    SpinReset();
    SyncTicks();
    ++stats.svcs;
    const u32 number = ctx->svc & 0xFF;
    const auto started = std::chrono::steady_clock::now();
    svc_context->CallSVC(ctx->svc);
    const u64 ns = static_cast<u64>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - started)
            .count());
    ++detail.svc_calls[number];
    detail.svc_ns[number] += ns;
    if (!SvcContinue() || reschedule || break_flag)
        return false;
    // The call may have programmed a timer event, so take the slice again.
    const s64 slice = GetTimer().GetDowncount();
    if (slice <= 0)
        return false;
    granted = std::min<s64>(slice, 0x7FFFFFFF - kBlockAllowance);
    ctx->budget = static_cast<s32>(granted);
    ++stats.svcs_continued;
    return true;
}

void ARM_AOT::MaybeReportStats() {
    using Clock = std::chrono::steady_clock;
    const auto now = Clock::now();
    if (last_report == Clock::time_point{}) {
        last_report = now;
        return;
    }
    const double secs = std::chrono::duration<double>(now - last_report).count();
    if (secs < 5.0)
        return;
    const Stats& s = stats;
    const Stats& p = last_stats;
    const auto rate = [&](u64 a, u64 b) { return static_cast<double>(a - b) / secs; };
    LOG_INFO(Core_ARM11,
             "AOT stats core {}: {:.0f} runs/s, {:.0f} svc/s ({:.0f}% continued), "
             "{:.0f} fallback batches/s, {:.0f} fallback steps/s, fallback {:.1f}% of wall time, "
             "{:.0f} interpret/s, {:.0f} lookups/s",
             GetID(), rate(s.runs, p.runs), rate(s.svcs, p.svcs),
             s.svcs == p.svcs ? 0.0
                              : 100.0 * static_cast<double>(s.svcs_continued - p.svcs_continued) /
                                    static_cast<double>(s.svcs - p.svcs),
             rate(s.fallback_batches, p.fallback_batches), rate(s.fallback_steps, p.fallback_steps),
             100.0 * static_cast<double>(s.fallback_ns - p.fallback_ns) / (secs * 1e9),
             rate(s.interpret_one, p.interpret_one), rate(s.lookups, p.lookups));
    // where the time goes: system calls by number (calls, share of wall time, mean cost) and the
    // addresses that went to the fallback CPU
    if (s.svcs != p.svcs) {
        std::array<u32, 256> order;
        for (u32 i = 0; i < order.size(); ++i)
            order[i] = i;
        std::sort(order.begin(), order.end(),
                  [&](u32 a, u32 b) { return detail.svc_ns[a] > detail.svc_ns[b]; });
        u64 total_ns = 0;
        for (u64 ns : detail.svc_ns)
            total_ns += ns;
        std::string top;
        for (u32 i = 0; i < 6 && detail.svc_calls[order[i]] != 0; ++i) {
            const u32 n = order[i];
            top += fmt::format("{}{:#04x} {} {:.0f}/s {:.1f}% {:.2f}us each", top.empty() ? "" : "; ", n,
                               SvcName(n), static_cast<double>(detail.svc_calls[n]) / secs,
                               100.0 * static_cast<double>(detail.svc_ns[n]) / (secs * 1e9),
                               static_cast<double>(detail.svc_ns[n]) / static_cast<double>(detail.svc_calls[n]) / 1e3);
        }
        LOG_INFO(Core_ARM11, "AOT svc core {}: {:.1f}% of wall time inside system calls; top by time: {}",
                 GetID(), 100.0 * static_cast<double>(total_ns) / (secs * 1e9), top);
    }
    if (!detail.fallback.empty()) {
        std::vector<std::pair<u32, Detail::Fallback>> entries(detail.fallback.begin(), detail.fallback.end());
        std::sort(entries.begin(), entries.end(),
                  [](const auto& a, const auto& b) { return a.second.batches > b.second.batches; });
        std::string top;
        for (std::size_t i = 0; i < entries.size() && i < 6; ++i) {
            const auto& [pc, f] = entries[i];
            top += fmt::format("{}{:08X}{} {:.0f}/s {:.1f} steps each", top.empty() ? "" : "; ", pc & ~1u,
                               (pc & 1) ? " (thumb)" : "", static_cast<double>(f.batches) / secs,
                               static_cast<double>(f.steps) / static_cast<double>(f.batches));
        }
        LOG_INFO(Core_ARM11, "AOT fallback core {}: {} distinct entry addresses; top by batches: {}",
                 GetID(), entries.size(), top);
    }
    if (s.idle_watches != p.idle_watches || s.idle_failures != p.idle_failures) {
        LOG_INFO(Core_ARM11, "AOT idle watch core {}: {} loops watched, {} proved idle, {} watches or checks failed",
                 GetID(), s.idle_watches - p.idle_watches, s.idle_proofs - p.idle_proofs,
                 s.idle_failures - p.idle_failures);
    }
    if (s.spin_slices != p.spin_slices) {
        LOG_INFO(Core_ARM11, "AOT idle core {}: {:.0f} spin-loop slices skipped/s ({:.1f}% of slices), "
                             "{:.1f} M ticks/s not run",
                 GetID(), rate(s.spin_slices, p.spin_slices),
                 100.0 * static_cast<double>(s.spin_slices - p.spin_slices) /
                     static_cast<double>(std::max<u64>(1, s.runs - p.runs)),
                 rate(s.spin_ticks, p.spin_ticks) / 1e6);
    }
    hints.Save();
    detail = Detail{};
    last_stats = stats;
    last_report = now;
}

void ARM_AOT::SyncTicks() {
    const s64 used = granted - ctx->budget;
    if (used > 0)
        GetTimer().AddTicks(static_cast<u64>(used));
    granted = ctx->budget = 0;
}

/// Runs code the library has no entry for on the fallback CPU until execution reaches code the
/// library has, a system call asks for a reschedule, or the slice ends. Returns false when Run()
/// should return.
bool ARM_AOT::RunFallbackBatch() {
    const auto started = std::chrono::steady_clock::now();
    SpinReset();
    ++stats.fallback_batches;
    const u32 entry_pc = ctx->r[15] | (ctx->thumb ? 1u : 0u);
    // the library has no code here: a function for the next build to find
    if (g.library->InExecutable(entry_pc & ~1u))
        hints.Add(entry_pc);
    u64 batch_steps = 0;
    SyncTicks(); // the fallback charges the timer itself
    PushToFallback();
    in_fallback = true;
    bool keep_going = true;
    while (true) {
        fallback->Step();
        ++stats.fallback_steps;
        ++batch_steps;
        if (reschedule || break_flag || GetTimer().GetDowncount() <= 0) {
            keep_going = false;
            break;
        }
        const u32 pc = fallback->GetPC() | ((fallback->GetCPSR() & CPSR_T) ? 1u : 0u);
        if (g.library->Lookup(pc))
            break;
    }
    in_fallback = false;
    PullFromFallback();
    if (keep_going) {
        granted = std::min<s64>(GetTimer().GetDowncount(), 0x7FFFFFFF - kBlockAllowance);
        ctx->budget = static_cast<s32>(granted);
    }
    auto& entry = detail.fallback[entry_pc];
    ++entry.batches;
    entry.steps += batch_steps;
    stats.fallback_ns += static_cast<u64>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - started)
            .count());
    return keep_going;
}

void ARM_AOT::Step() {
    if (!resolved)
        Resolve();
    if (delegate_all || break_flag) {
        if (delegate_all)
            fallback->Step();
        return;
    }
    // Single-stepping (debugger): the fallback CPU is exact and cheap enough for this.
    SyncTicks();
    PushToFallback();
    in_fallback = true;
    fallback->Step();
    in_fallback = false;
    PullFromFallback();
}

void ARM_AOT::PushToFallback() {
    ThreadContext tc;
    ToThreadContext(tc);
    fallback->LoadContext(tc);
    fallback->SetCP15Register(CP15_THREAD_UPRW, tpidrurw);
    fallback->SetCP15Register(CP15_THREAD_URO, tpidruro);
}

void ARM_AOT::PullFromFallback() {
    ThreadContext tc;
    fallback->SaveContext(tc);
    FromThreadContext(tc);
    tpidrurw = fallback->GetCP15Register(CP15_THREAD_UPRW);
    tpidruro = fallback->GetCP15Register(CP15_THREAD_URO);
    ctx->tls = tpidruro;
    ctx->exclusive = 0;
}

// ── host callbacks ──────────────────────────────────────────────────────────────────────

u32 ARM_AOT::SlowRead(u32 addr, u32 size) {
    if (size == 1)
        return memory.Read8(addr);
    if ((addr & Memory::CITRA_PAGE_MASK) > Memory::CITRA_PAGE_SIZE - size) {
        u32 v = 0;
        for (u32 i = 0; i < size; ++i)
            v |= static_cast<u32>(memory.Read8(addr + i)) << (8 * i);
        return v;
    }
    return size == 2 ? memory.Read16(addr) : memory.Read32(addr);
}

void ARM_AOT::SlowWrite(u32 addr, u32 value, u32 size) {
    if (IdleLogging()) [[unlikely]]
        NoteIdleWrite(addr, size);
    if (size == 1) {
        memory.Write8(addr, static_cast<u8>(value));
    } else if ((addr & Memory::CITRA_PAGE_MASK) > Memory::CITRA_PAGE_SIZE - size) {
        for (u32 i = 0; i < size; ++i)
            memory.Write8(addr + i, static_cast<u8>(value >> (8 * i)));
    } else if (size == 2) {
        memory.Write16(addr, static_cast<u16>(value));
    } else {
        memory.Write32(addr, value);
    }
}

void* ARM_AOT::LookupCode(u32 address) {
    ++stats.lookups;
    return reinterpret_cast<void*>(g.library->Lookup(address));
}

/// The generated code left one instruction to the interpreter (an unusual coprocessor access,
/// say). Run it on the fallback CPU with the code's state, then take the state back. If
/// execution did not simply move to the next instruction, make the code return to the host.
void ARM_AOT::InterpretOne(u32 address, u32 /*opcode*/) {
    // The registers in the Context are current here (the code stores them before calling).
    // The code has already charged this instruction to its budget; the fallback CPU also
    // charges the timer, a double count of a few ticks for a rare instruction.
    ctx->r[15] = address;
    SpinReset();
    const bool thumb = ctx->thumb != 0;
    ++stats.interpret_one;
    ++stats.fallback_steps;
    PushToFallback();
    in_fallback = true;
    fallback->Step();
    in_fallback = false;
    PullFromFallback();
    const u32 next = address + (thumb ? 2 : 4);
    if (ctx->r[15] != next || (ctx->thumb != 0) != thumb || reschedule) {
        ctx->exit = EXIT_UNWIND;
    }
}

// ── memory / caches ─────────────────────────────────────────────────────────────────────

void ARM_AOT::SetPageTable(const std::shared_ptr<Memory::PageTable>& table) {
    SpinReset();
    page_table = table;
    // Pages whose pointer is null (I/O, GPU-cached memory, unmapped) go through the host
    // callbacks, which is exactly what the page table's own convention means.
    u8* const* pages = table ? table->GetPointerArray().data() : nullptr;
    ctx->read_pages = pages;
    ctx->write_pages = IdleLogging() ? NoPages() : pages;
    fallback->SetPageTable(table);
}

std::shared_ptr<Memory::PageTable> ARM_AOT::GetPageTable() const {
    return page_table;
}

void ARM_AOT::ClearInstructionCache() {
    fallback->ClearInstructionCache();
}

void ARM_AOT::InvalidateCacheRange(u32 start, std::size_t length) {
    SpinReset(); // code changed
    // Recompiled code is fixed; changed code is caught by hash checks when CROs load. The
    // fallback CPU's JIT cache still has to hear about it.
    fallback->InvalidateCacheRange(start, length);
}

void ARM_AOT::ClearExclusiveState() {
    ctx->exclusive = 0;
    fallback->ClearExclusiveState();
}

void ARM_AOT::PrepareReschedule() {
    reschedule = true;
    fallback->PrepareReschedule();
}

// ── registers: ours, or the fallback's while it holds the state ─────────────────────────

void ARM_AOT::SetPC(u32 addr) {
    SpinReset();
    if (InFallback())
        return fallback->SetPC(addr);
    ctx->r[15] = addr;
}

u32 ARM_AOT::GetPC() const {
    return InFallback() ? fallback->GetPC() : ctx->r[15];
}

u32 ARM_AOT::GetReg(int i) const {
    return InFallback() ? fallback->GetReg(i) : ctx->r[i];
}

void ARM_AOT::SetReg(int i, u32 v) {
    SpinReset();
    if (InFallback())
        return fallback->SetReg(i, v);
    ctx->r[i] = v;
}

u32 ARM_AOT::GetVFPReg(int i) const {
    if (InFallback())
        return fallback->GetVFPReg(i);
    return i < 32 ? vfp[i] : 0;
}

void ARM_AOT::SetVFPReg(int i, u32 v) {
    if (InFallback())
        return fallback->SetVFPReg(i, v);
    if (i < 32)
        vfp[i] = v;
}

u32 ARM_AOT::GetVFPSystemReg(VFPSystemRegister reg) const {
    if (InFallback())
        return fallback->GetVFPSystemReg(reg);
    return reg == VFP_FPSCR ? fpscr : reg == VFP_FPEXC ? fpexc : 0;
}

void ARM_AOT::SetVFPSystemReg(VFPSystemRegister reg, u32 v) {
    if (InFallback())
        return fallback->SetVFPSystemReg(reg, v);
    if (reg == VFP_FPSCR)
        fpscr = v;
    else if (reg == VFP_FPEXC)
        fpexc = v;
}

u32 ARM_AOT::GetCPSR() const {
    if (InFallback())
        return fallback->GetCPSR();
    return (ctx->n ? CPSR_N : 0) | (ctx->z ? CPSR_Z : 0) | (ctx->c ? CPSR_C : 0) |
           (ctx->v ? CPSR_V : 0) | (ctx->q ? CPSR_Q : 0) | ((ctx->ge & 0xFu) << 16) |
           (ctx->thumb ? CPSR_T : 0) | cpsr_misc;
}

void ARM_AOT::SetCPSR(u32 cpsr) {
    if (InFallback())
        return fallback->SetCPSR(cpsr);
    ctx->n = (cpsr & CPSR_N) != 0;
    ctx->z = (cpsr & CPSR_Z) != 0;
    ctx->c = (cpsr & CPSR_C) != 0;
    ctx->v = (cpsr & CPSR_V) != 0;
    ctx->q = (cpsr & CPSR_Q) != 0;
    ctx->ge = (cpsr >> 16) & 0xF;
    ctx->thumb = (cpsr & CPSR_T) != 0;
    cpsr_misc = cpsr & CPSR_MISC_MASK;
}

u32 ARM_AOT::GetCP15Register(CP15Register reg) const {
    if (InFallback())
        return fallback->GetCP15Register(reg);
    return reg == CP15_THREAD_UPRW ? tpidrurw : reg == CP15_THREAD_URO ? tpidruro : 0;
}

void ARM_AOT::SetCP15Register(CP15Register reg, u32 v) {
    if (InFallback())
        return fallback->SetCP15Register(reg, v);
    if (reg == CP15_THREAD_UPRW)
        tpidrurw = v;
    else if (reg == CP15_THREAD_URO)
        tpidruro = ctx->tls = v;
}

void ARM_AOT::ToThreadContext(ThreadContext& tc) const {
    for (int i = 0; i < 16; ++i)
        tc.cpu_registers[i] = ctx->r[i];
    tc.cpsr = (ctx->n ? CPSR_N : 0) | (ctx->z ? CPSR_Z : 0) | (ctx->c ? CPSR_C : 0) |
              (ctx->v ? CPSR_V : 0) | (ctx->q ? CPSR_Q : 0) | ((ctx->ge & 0xFu) << 16) |
              (ctx->thumb ? CPSR_T : 0) | cpsr_misc;
    for (int i = 0; i < 64; ++i)
        tc.fpu_registers[i] = i < 32 ? vfp[i] : 0;
    tc.fpscr = fpscr;
    tc.fpexc = fpexc;
}

void ARM_AOT::FromThreadContext(const ThreadContext& tc) {
    for (int i = 0; i < 16; ++i)
        ctx->r[i] = tc.cpu_registers[i];
    ctx->n = (tc.cpsr & CPSR_N) != 0;
    ctx->z = (tc.cpsr & CPSR_Z) != 0;
    ctx->c = (tc.cpsr & CPSR_C) != 0;
    ctx->v = (tc.cpsr & CPSR_V) != 0;
    ctx->q = (tc.cpsr & CPSR_Q) != 0;
    ctx->ge = (tc.cpsr >> 16) & 0xF;
    ctx->thumb = (tc.cpsr & CPSR_T) != 0;
    cpsr_misc = tc.cpsr & CPSR_MISC_MASK;
    for (int i = 0; i < 32; ++i)
        vfp[i] = tc.fpu_registers[i];
    fpscr = tc.fpscr;
    fpexc = tc.fpexc;
}

void ARM_AOT::SaveContext(ThreadContext& tc) {
    if (InFallback())
        return fallback->SaveContext(tc);
    ToThreadContext(tc);
}

void ARM_AOT::LoadContext(const ThreadContext& tc) {
    SpinReset(); // another thread's registers
    if (InFallback())
        return fallback->LoadContext(tc);
    FromThreadContext(tc);
}

// ── installation ────────────────────────────────────────────────────────────────────────

void SetLibraryDir(const std::string& dir) {
    g.dir = dir;
}

void* LibrarySymbol(const char* name) {
    return g.library ? g.library->Symbol(name) : nullptr;
}

size_t RecheckCode() {
    if (!g.library)
        return 0;
    auto& memory = Core::System::GetInstance().Memory();
    const size_t stale = g.library->Check(0, 0, [&](u32 a, u8* out, size_t n) { memory.ReadBlock(a, out, n); });
    LOG_INFO(Core_ARM11, "AOT: code patched in memory, {} functions now run on the fallback CPU", stale);
    return stale;
}

void SetLibraryLoadedCallback(std::function<void()> callback) {
    library_loaded = std::move(callback);
}

void NoteHostWork(void* context) {
    if (context)
        Self(static_cast<Context*>(context)).NoteHostWork();
}

void SetSkipIdle(bool enabled) {
    skip_idle_option = enabled;
}

void SetHintsPath(const std::string& path) {
    hints_path_override = path;
}

void SetLibraryPath(const std::string& path) {
    g.path = path;
    // a new game: look again (the process outlives a single game on Android)
    g.resolved = false;
    g.library.reset();
    g.modules.clear();
}

std::shared_ptr<Core::ARM_Interface> MakeCore(Core::System& system, Memory::MemorySystem& memory,
                                              u32 core_id,
                                              std::shared_ptr<Core::Timing::Timer> timer) {
    return std::make_shared<ARM_AOT>(system, memory, core_id, std::move(timer));
}

void RegisterCROObserver() {
    Service::LDR::SetCROObserver([](Core::System& sys, const std::string& name, u32 base) {
        if (base) {
            g.modules[name] = base;
        } else {
            g.modules.erase(name);
        }
        if (!g.library)
            return;
        auto& mem = sys.Memory();
        size_t stale = 0;
        const bool known = g.library->Place(
            name, base, [&](u32 a, u8* out, size_t n) { mem.ReadBlock(a, out, n); }, &stale);
        if (known && base) {
            LOG_INFO(Core_ARM11, "AOT: recompiled code for {} placed at {:08X}{}", name, base,
                     stale ? fmt::format(", {} changed functions run on the fallback CPU", stale)
                           : "");
        }
    });
}

namespace {
// The CPU-factory hook differs between the Azahar patches: ours (patches/0001) passes the shared
// exclusive monitor as a 5th argument, the MH4U Android patch doesn't. Take whichever the
// tree's CPUFactory accepts (a template, so the other branch is never instantiated).
template <typename SystemT>
void SetFactory(SystemT& system) {
    auto with_monitor = [](Core::System& sys, Memory::MemorySystem& mem, u32 core_id,
                           std::shared_ptr<Core::Timing::Timer> timer,
                           Core::ExclusiveMonitor& monitor) -> std::shared_ptr<Core::ARM_Interface> {
        return std::make_shared<ARM_AOT>(sys, mem, core_id, std::move(timer), &monitor);
    };
    auto without_monitor = [](Core::System& sys, Memory::MemorySystem& mem, u32 core_id,
                              std::shared_ptr<Core::Timing::Timer> timer)
        -> std::shared_ptr<Core::ARM_Interface> {
        return std::make_shared<ARM_AOT>(sys, mem, core_id, std::move(timer));
    };
    if constexpr (std::is_constructible_v<typename SystemT::CPUFactory, decltype(with_monitor)>) {
        system.SetCPUFactory(with_monitor);
    } else {
        system.SetCPUFactory(without_monitor);
    }
}
} // namespace

void Install(Core::System& system) {
    g = Global{g.dir, g.path};
    SetFactory(system);
    RegisterCROObserver();
}

} // namespace Aot

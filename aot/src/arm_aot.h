// Azahar CPU backend that runs 3dsrecomp's ahead-of-time compiled code (ABI 4) and hands
// everything else to Azahar's own CPU (dynarmic, or the interpreter where there is no JIT).
//
//   * Code the library has (the executable and loaded CRO modules, unless a mod/update changed
//     it): runs natively as C compiled by 3dsrecomp.
//   * Anything else (code the analysis missed, generated code, stale functions): runs on the
//     fallback CPU one batch at a time, until execution reaches recompiled code again.
//   * Kernel, services, GPU, audio, network: Azahar. System calls leave the AOT code through
//     EXIT_SVC and go to Azahar's SVC handler on the emulation thread's own stack, so no
//     fibers are needed (unlike the older MH4U port) and save states work.
//   * With no library for the title the backend forwards everything to the fallback CPU, so it
//     is never slower than stock Azahar.
#pragma once

#include <array>
#include <chrono>
#include <unordered_map>
#include <functional>
#include <memory>
#include <string>

#include "core/arm/arm_interface.h"

struct Context; // recomp.h

namespace Core {
class System;
class ExclusiveMonitor;
} // namespace Core
namespace Kernel {
class SVCContext;
}
namespace Memory {
class MemorySystem;
struct PageTable;
} // namespace Memory

namespace Aot {

class Library;

class ARM_AOT final : public Core::ARM_Interface {
public:
    /// `monitor` is the core-shared exclusive monitor for the dynarmic fallback; frontends whose
    /// CPU factory doesn't pass one (e.g. the MH4U port's) leave it null and the backend makes
    /// its own (cross-core store-exclusive atomicity is then not modelled in the fallback).
    ARM_AOT(Core::System& system, Memory::MemorySystem& memory, u32 core_id,
            std::shared_ptr<Core::Timing::Timer> timer, Core::ExclusiveMonitor* monitor = nullptr);
    ~ARM_AOT() override;

    void Run() override;
    void Step() override;
    void ClearInstructionCache() override;
    void InvalidateCacheRange(u32 start_address, std::size_t length) override;
    void ClearExclusiveState() override;
    void SetPageTable(const std::shared_ptr<Memory::PageTable>& page_table) override;
    void SetPC(u32 addr) override;
    u32 GetPC() const override;
    u32 GetReg(int index) const override;
    void SetReg(int index, u32 value) override;
    u32 GetVFPReg(int index) const override;
    void SetVFPReg(int index, u32 value) override;
    u32 GetVFPSystemReg(VFPSystemRegister reg) const override;
    void SetVFPSystemReg(VFPSystemRegister reg, u32 value) override;
    u32 GetCPSR() const override;
    void SetCPSR(u32 cpsr) override;
    u32 GetCP15Register(CP15Register reg) const override;
    void SetCP15Register(CP15Register reg, u32 value) override;
    void SaveContext(ThreadContext& ctx) override;
    void LoadContext(const ThreadContext& ctx) override;
    void PrepareReschedule() override;
    bool HasSingleInstructionBreakAccuracy() override {
        return false;
    }

    // Callbacks of recomp.h's Host, called by the generated code.
    u32 SlowRead(u32 address, u32 size);
    void SlowWrite(u32 address, u32 value, u32 size);
    void InterpretOne(u32 address, u32 opcode);
    void* LookupCode(u32 address);

protected:
    std::shared_ptr<Memory::PageTable> GetPageTable() const override;

private:
    /// True when state lives in the fallback CPU: no library, or it is mid-step.
    bool InFallback() const {
        return delegate_all || in_fallback;
    }
    void Resolve();
    bool RunFallbackBatch();
    void SyncTicks();
    /// Runs the system call in ctx->svc. Returns true when Run() can carry on with the same
    /// slice (no reschedule, no break, time left), false when it has to return.
    bool HandleSvc();
    /// Logs the counters below, as rates since the last report, every few seconds.
    void MaybeReportStats();
    void ToThreadContext(ThreadContext& tc) const;
    void FromThreadContext(const ThreadContext& tc);
    void PullFromFallback();
    void PushToFallback();

    // ── spin-loop skipping ──
    // A thread that polls memory in a loop (a flag, a timer) runs its whole time slice in
    // generated code and ends it in the same place with the same registers, every time. Such a
    // slice is skipped (charged to the timer, not run) a few times in a row, then one runs for
    // real to check the loop is still unchanged. Zakuro finds these loops by logging the loop's
    // memory writes; generated code writes memory directly, so here the registers at the end
    // of consecutive slices have to match instead (see SpinObserve for what that rules out).
    struct Snapshot {
        std::array<u32, 16> r{};
        std::array<u32, 32> vfp{};
        u32 flags = 0; // N Z C V Q, thumb, GE
        u32 fpscr = 0;
        u32 tls = 0;
        u64 serial = 0; // the slice end it was last seen at
        bool operator==(const Snapshot& o) const {
            return r == o.r && vfp == o.vfp && flags == o.flags && fpscr == o.fpscr && tls == o.tls;
        }
    };
    static constexpr std::size_t kSpinRing = 8;
    Snapshot TakeSnapshot() const;
    /// Called when a slice ended in generated code only: counts it if it ended as an earlier one did.
    void SpinObserve();
    /// Called at the start of a slice: true when it is skipped.
    bool SpinSkip(s64 slice);
    /// Anything that changes the thread or what its registers hold from outside the code.
    void SpinReset();
    std::array<Snapshot, kSpinRing> spin_ring{};
    std::size_t spin_filled = 0;
    std::size_t spin_next = 0;
    u64 spin_serial = 0;
    u32 spin_streak = 0;  // slice ends in a row that matched one seen lately
    u32 spin_skipped = 0; // slices skipped since one last ran
    bool slice_clean = false; // this slice ran generated code only: no system call, no fallback

    Core::System& system;
    Memory::MemorySystem& memory;
    std::unique_ptr<Kernel::SVCContext> svc_context;
    std::unique_ptr<Core::ExclusiveMonitor> own_monitor;
    std::unique_ptr<Core::ARM_Interface> fallback;
    std::shared_ptr<Memory::PageTable> page_table;

    std::unique_ptr<Context> ctx;
    std::array<u32, 64> vfp{};
    u32 fpscr = 0;
    u32 fpexc = 0;
    u32 tpidrurw = 0;
    u32 tpidruro = 0;
    u32 cpsr_misc = 0x10; // mode, masks, E ... (everything but NZCVQ, GE and T)

    bool resolved = false;
    bool delegate_all = false; // no library for this title
    bool in_fallback = false;
    bool reschedule = false;
    s64 granted = 0; // budget handed to the code in this Run, for tick accounting

    // Counters for finding out where the time goes (logged as "AOT stats").
    struct Stats {
        u64 runs = 0;              // calls to Run()
        u64 svcs = 0;              // system calls
        u64 svcs_continued = 0;    // ... after which Run() went on instead of returning
        u64 fallback_batches = 0;  // RunFallbackBatch() calls
        u64 fallback_steps = 0;    // instructions stepped on the fallback CPU (batches + InterpretOne)
        u64 fallback_ns = 0;       // wall time inside RunFallbackBatch()
        u64 interpret_one = 0;     // instructions the generated code left to the interpreter
        u64 lookups = 0;           // indirect-branch lookups from generated code (host->lookup)
        u64 spin_slices = 0;       // slices skipped as a spin loop's
        u64 spin_ticks = 0;        // ... and the ticks they were charged
    };
    Stats stats;
    Stats last_stats;
    std::chrono::steady_clock::time_point last_report{};

    // What the last report interval spent on, by system call number and by the address that went
    // to the fallback CPU (cleared by each report).
    struct Detail {
        std::array<u64, 256> svc_calls{};
        std::array<u64, 256> svc_ns{};  // wall time inside CallSVC
        struct Fallback {
            u64 batches = 0;
            u64 steps = 0;
        };
        std::unordered_map<u32, Fallback> fallback;  // entry address (bit 0: Thumb) -> batches, steps
    };
    Detail detail;
};

/// Directory searched for `<TITLEID>.so` (3dsrecomp's naming). Default: $AZAHAR_AOT_DIR.
void SetLibraryDir(const std::string& dir);

/// Use exactly this library file instead of searching the directory (must be loadable: on
/// Android, inside the app's private storage). Empty = search.
void SetLibraryPath(const std::string& path);

/// Where to write the hints (addresses the library had no code for; 3dsrecomp's next build takes
/// them as functions). Default: `<library>.hints` next to the library. On Android the library is
/// a private copy, so the app points this at `<Azahar user folder>/aot/<TITLEID>.hints`.
void SetHintsPath(const std::string& path);

/// Skip-idle toggle (spin-loop skipping). On by default; the app's settings switch calls this.
void SetSkipIdle(bool enabled);

/// An exported symbol of the library in use (null before one is chosen, or when it has none).
void* LibrarySymbol(const char* name);

/// Hashes the executable's code in memory again and marks the functions that changed as stale, so
/// they run on the fallback CPU from now on: for code a frontend has patched in guest memory
/// (the port's wide render). Returns how many functions are stale, or 0 with no library.
size_t RecheckCode();

/// Called once, on the emulation thread, when a library has been chosen for the game and before
/// its code runs; the port installs its hooks there.
void SetLibraryLoadedCallback(std::function<void()> callback);

/// Reports CRO module loads/unloads to the library (needs patches/0002 in Azahar).
void RegisterCROObserver();

/// For frontends with their own CPU-factory hook (the MH4U app): the factory body.
std::shared_ptr<Core::ARM_Interface> MakeCore(Core::System& system, Memory::MemorySystem& memory,
                                              u32 core_id,
                                              std::shared_ptr<Core::Timing::Timer> timer);

/// Makes `system` use ARM_AOT for its CPU cores (needs patches/0001's SetCPUFactory) and
/// registers the CRO observer. Call before System::Load().
void Install(Core::System& system);

} // namespace Aot

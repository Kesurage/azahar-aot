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
    };
    Stats stats;
    Stats last_stats;
    std::chrono::steady_clock::time_point last_report{};
};

/// Directory searched for `<TITLEID>.so` (3dsrecomp's naming). Default: $AZAHAR_AOT_DIR.
void SetLibraryDir(const std::string& dir);

/// Use exactly this library file instead of searching the directory (must be loadable: on
/// Android, inside the app's private storage). Empty = search.
void SetLibraryPath(const std::string& path);

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

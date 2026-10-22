#include "arm_aot.h"

#include <algorithm>
#include <cstdlib>
#include <map>
#include <type_traits>
#include <vector>
#include <fmt/format.h>

#include "aot_library.h"
#include "common/arch.h"
#include "common/file_util.h"
#include "common/logging/log.h"
#include "core/arm/dyncom/arm_dyncom.h"
#include "core/arm/exclusive_monitor.h"
#if CITRA_ARCH(x86_64) || CITRA_ARCH(arm64)
#include "core/arm/dynarmic/arm_dynarmic.h"
#endif
#include "core/core.h"
#include "core/core_timing.h"
#include "core/hle/kernel/svc.h"
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

struct Global {
    std::string dir;
    std::string path; // exact library, if set
    bool resolved = false;
    std::unique_ptr<Library> library;
    // CRO modules loaded before the library was resolved
    std::map<std::string, u32> modules;
};
Global g;

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
            if (!FileUtil::Exists(path))
                continue;
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
            g.library = std::move(lib);
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
    if (delegate_all) {
        fallback->Run();
        return;
    }
    Library& lib = *g.library;
    const s64 slice = GetTimer().GetDowncount();
    if (slice <= 0)
        return;
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
        case EXIT_SVC: {
            SyncTicks();
            const u32 number = ctx->svc;
            svc_context->CallSVC(number);
            // A system call may block, switch threads or reprogram timers; like the JIT
            // backend, end the slice and let the core loop decide what runs next.
            return;
        }
        case EXIT_BUDGET:
            if (ctx->budget == before) {
                // The next block is bigger than what is left of the slice. Lend the code
                // enough to run it; the overshoot is charged to the timer afterwards.
                ctx->budget += kBlockAllowance;
                granted += kBlockAllowance;
                ctx->exit = EXIT_NONE;
                ctx->depth = 0;
                Code again = lib.Lookup(ctx->r[15] | (ctx->thumb ? 1u : 0u));
                if (again)
                    again(ctx.get());
                if (ctx->exit == EXIT_SVC) {
                    SyncTicks();
                    svc_context->CallSVC(ctx->svc);
                    return;
                }
                if (ctx->exit == EXIT_BUDGET && !RunFallbackBatch())
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
    SyncTicks();
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
    SyncTicks(); // the fallback charges the timer itself
    PushToFallback();
    in_fallback = true;
    bool keep_going = true;
    while (true) {
        fallback->Step();
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
    const bool thumb = ctx->thumb != 0;
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
    page_table = table;
    // Pages whose pointer is null (I/O, GPU-cached memory, unmapped) go through the host
    // callbacks, which is exactly what the page table's own convention means.
    u8* const* pages = table ? table->GetPointerArray().data() : nullptr;
    ctx->read_pages = pages;
    ctx->write_pages = pages;
    fallback->SetPageTable(table);
}

std::shared_ptr<Memory::PageTable> ARM_AOT::GetPageTable() const {
    return page_table;
}

void ARM_AOT::ClearInstructionCache() {
    fallback->ClearInstructionCache();
}

void ARM_AOT::InvalidateCacheRange(u32 start, std::size_t length) {
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
    if (InFallback())
        return fallback->LoadContext(tc);
    FromThreadContext(tc);
}

// ── installation ────────────────────────────────────────────────────────────────────────

void SetLibraryDir(const std::string& dir) {
    g.dir = dir;
}

void SetLibraryPath(const std::string& path) {
    g.path = path;
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

#include "hooks_aot.h"

#include <algorithm>
#include <cstring>
#include <iterator>
#include <vector>

#include "common/logging/log.h"
#include "core/core.h"
#include "core/memory.h"
#include "hooks.h"

#ifdef MH4U_AOT
#include "arm_aot.h"
#endif

extern "C" {
#include "recomp.h"
}

namespace Port::HooksAot {

namespace {

constexpr u32 kHookAbi = 1;

// Must match AotHookCall in overrides/mh4u/mh4u_hooks.c (tools/gen_hook_overrides.py).
struct AotHookCall {
    u32 abi, index;
    u32 r[16];
    u32 n, z, c, v;
    u8* const* write_pages;
    void* context;
    void (*original)(void*);
    u32 ran;
    u32 reserved;
    void (*run_original)(AotHookCall*);
    void (*call_guest)(AotHookCall*, u32 target);
};
using AotHookFn = void (*)(AotHookCall*);

// config/tunables.txt (USA v1.3.0): instructions whose immediate operand is a size the wide render
// changes. The port's own libraries read these from a table; for a 3dsrecomp library the
// instruction is patched in guest memory, and the functions holding them then run on the fallback
// CPU (Aot::RecheckCode).
struct TunableSpot {
    u32 address;
    u32 original;
    const char* name;
};
constexpr TunableSpot kTunables[] = {
    {0x005de49c, 400, "top_rows"}, // full-screen pass with an 8-pixel border: clamp (cmp)
    {0x005de4a0, 400, "top_rows"}, //   same clamp (movgt)
    {0x00af0c84, 400, "top_rows"}, // sCamera::Viewport::setupDraw, mode 0xa on the top screen
};
constexpr size_t kTunableCount = sizeof(kTunables) / sizeof(kTunables[0]);

// the value of a data-processing instruction's rotated 8-bit immediate; false if it has none
bool DecodeImmediate(u32 word, u32& value) {
    if (((word >> 25) & 7) != 1)
        return false;
    const u32 rotate = ((word >> 8) & 0xF) * 2;
    const u32 imm8 = word & 0xFF;
    value = rotate ? (imm8 >> rotate) | (imm8 << (32 - rotate)) : imm8;
    return true;
}

// the 12 bits (rotate, imm8) that make `value`; false if no rotated 8-bit immediate does
bool EncodeImmediate(u32 value, u32& imm12) {
    for (u32 rotate = 0; rotate < 16; ++rotate) {
        const u32 turn = rotate * 2;
        const u32 rolled = turn ? (value << turn) | (value >> (32 - turn)) : value;
        if (rolled <= 0xFF) {
            imm12 = (rotate << 8) | rolled;
            return true;
        }
    }
    return false;
}

std::vector<u32> hook_addrs;
std::vector<RHookFn> hook_fns;
AotHookFn* hook_table = nullptr; // the library's azahar_aot_hooks

// the context of the hook running on this thread (hooks nest: a hook can call guest code that
// reaches another)
thread_local const RCtx* top = nullptr;
u64 unwound_logged = 0; // hooks already warned about (bit per index)

void CopyIn(RCtx* ctx, const AotHookCall* hc, bool with_pc) {
    std::copy(std::begin(hc->r), std::begin(hc->r) + (with_pc ? 16 : 15), ctx->r);
    ctx->fn = hc->n;
    ctx->fz = hc->z;
    ctx->fc = hc->c;
    ctx->fv = hc->v;
}

void CopyOut(AotHookCall* hc, const RCtx* ctx, bool with_pc) {
    std::copy(std::begin(ctx->r), std::begin(ctx->r) + (with_pc ? 16 : 15), hc->r);
    hc->n = ctx->fn;
    hc->z = ctx->fz;
    hc->c = ctx->fc;
    hc->v = ctx->fv;
}

// what a hook gets as `original`: runs the library's function with the registers as the hook
// has set them
void Original(RCtx* ctx) {
    auto* hc = static_cast<AotHookCall*>(ctx->user);
    CopyOut(hc, ctx, false);
    hc->run_original(hc);
    if ((hc->reserved & 1) && hc->index < 64 && !(unwound_logged >> hc->index & 1)) {
        // the function was left early (slice ended or a system call): what the hook does after it
        // now works on half a result, and the rest runs without the hook
        unwound_logged |= 1ull << hc->index;
        LOG_WARNING(Core_ARM11, "hooks: hook {} ({:08X}) left its function early; its post-processing "
                                "for that call is incomplete", hc->index,
                    hc->index < hook_addrs.size() ? hook_addrs[hc->index] : 0);
    }
    hc->reserved &= ~1u;
    CopyIn(ctx, hc, true);
}

void HostHook(AotHookCall* hc) {
    const RHookFn fn = hc->index < hook_fns.size() ? hook_fns[hc->index] : nullptr;
    if (!fn) {
        hc->run_original(hc);
        return;
    }
#ifdef MH4U_AOT
    // a hook is the game's own work, not an idle loop's: a watch of one must not span it
    Aot::NoteHostWork(hc->context);
#endif
    RCtx ctx;
    std::memset(&ctx, 0, sizeof(ctx));
    CopyIn(&ctx, hc, true);
    ctx.user = hc;
    // the library's own page table, with the pages that reads and writes may use directly
    ctx.pages = const_cast<u8**>(hc->write_pages);
    const RCtx* outer = top;
    top = &ctx;
    fn(&ctx, &Original);
    top = outer;
    CopyOut(hc, &ctx, true);
}

int SetHook(u32 address, RHookFn fn) {
    for (size_t i = 0; i < hook_addrs.size(); ++i) {
        if (hook_addrs[i] == address) {
            hook_fns[i] = fn;
            hook_table[i] = fn ? &HostHook : nullptr;
            return 1;
        }
    }
    return 0;
}

} // namespace

void Install() {
    RCodeInfo info;
    std::memset(&info, 0, sizeof(info));
    hook_addrs.clear();
    hook_fns.clear();
    hook_table = nullptr;
#ifdef MH4U_AOT
    const auto* abi = static_cast<const u32*>(Aot::LibrarySymbol("azahar_aot_hook_abi"));
    const auto* size = static_cast<const u32*>(Aot::LibrarySymbol("azahar_aot_hook_call_size"));
    const auto* count = static_cast<const u32*>(Aot::LibrarySymbol("azahar_aot_hook_count"));
    const auto* addrs = static_cast<const u32*>(Aot::LibrarySymbol("azahar_aot_hook_addrs"));
    auto* table = static_cast<AotHookFn*>(Aot::LibrarySymbol("azahar_aot_hooks"));
    if (!abi || !size || !count || !addrs || !table) {
        LOG_WARNING(Core_ARM11,
                    "hooks: this 3dsrecomp library has no hook points, so the port's options (frame "
                    "rate, widescreen, touch overlay, screen filters, ...) do nothing. Build it "
                    "with --overrides overrides/mh4u (mh4u_hooks.c)");
    } else if (*abi != kHookAbi || *size != sizeof(AotHookCall)) {
        LOG_ERROR(Core_ARM11,
                  "hooks: the library's hook points are for another version of the port (abi {}, "
                  "call {} bytes; this build wants {}, {}). Rebuild the library",
                  *abi, *size, kHookAbi, sizeof(AotHookCall));
    } else {
        hook_addrs.assign(addrs, addrs + *count);
        hook_fns.assign(*count, nullptr);
        hook_table = table;
        info.hook_addrs = addrs;
        info.hook_count = *count;
        info.set_hook = &SetHook;
    }
#endif

    auto& memory = Core::System::GetInstance().Memory();

    // Wide render changes three instruction immediates (config/tunables.txt). A library built
    // from overrides/mh4u with a 3dsrecomp that knows RECOMP_TUNABLE reads them from variables it
    // exports, so the host sets a variable and the code stays recompiled. Any other library gets the
    // instructions patched in guest memory instead, which sends the two functions holding them to
    // the fallback CPU.
    const u32* lib_count = nullptr;
    const char* const* lib_names = nullptr;
    u32* const* lib_vars = nullptr;
#ifdef MH4U_AOT
    if (hook_table) {
        lib_count = static_cast<const u32*>(Aot::LibrarySymbol("azahar_aot_tunable_count"));
        lib_names = static_cast<const char* const*>(Aot::LibrarySymbol("azahar_aot_tunable_names"));
        lib_vars = static_cast<u32* const*>(Aot::LibrarySymbol("azahar_aot_tunable_vars"));
    }
#endif
    const bool lib_tunables = lib_count && lib_names && lib_vars && *lib_count > 0;

    std::vector<u32> lib_values;
    u32 tunable_values[kTunableCount];
    u32 tunable_addrs[kTunableCount];
    const char* tunable_names[kTunableCount];
    bool spots_ok = false;
    if (lib_tunables) {
        for (u32 i = 0; i < *lib_count; ++i)
            lib_values.push_back(*lib_vars[i]);
        info.tunables = lib_values.data();
        info.tunable_names = lib_names;
        info.tunable_count = *lib_count;
    } else if (hook_table) { // the hooks' ABI matched: the library is this game's
        spots_ok = true;
        for (size_t i = 0; i < kTunableCount; ++i) {
            u32 now = 0;
            const u32 word = memory.Read32(kTunables[i].address);
            const bool ok = DecodeImmediate(word, now) && now == kTunables[i].original;
            if (!ok) {
                LOG_WARNING(Core_ARM11,
                            "hooks: wide render's code spot {:08X} holds {:08X} (immediate {}), not the "
                            "v1.3.0 instruction with {}",
                            kTunables[i].address, word, now, kTunables[i].original);
                spots_ok = false;
            }
            tunable_values[i] = kTunables[i].original;
            tunable_addrs[i] = kTunables[i].address;
            tunable_names[i] = kTunables[i].name;
        }
        if (spots_ok) {
            info.tunables = tunable_values;
            info.tunable_addrs = tunable_addrs;
            info.tunable_names = tunable_names;
            info.tunable_count = kTunableCount;
        }
    }

    for (const auto& line : Hooks::Install(info)) {
        LOG_INFO(Core_ARM11, "hooks: {}", line);
    }

    if (lib_tunables) {
        for (u32 i = 0; i < *lib_count; ++i) {
            *lib_vars[i] = lib_values[i];
            LOG_INFO(Core_ARM11, "hooks: tunable {} = {} (read by the recompiled code)", lib_names[i],
                     lib_values[i]);
        }
    }

#ifdef MH4U_AOT
    if (spots_ok) {
        u32 patched = 0;
        for (size_t i = 0; i < kTunableCount; ++i) {
            if (tunable_values[i] == kTunables[i].original)
                continue;
            u32 imm12 = 0;
            if (!EncodeImmediate(tunable_values[i], imm12)) {
                LOG_ERROR(Core_ARM11, "hooks: {} can't be an instruction's immediate, spot {:08X} left alone",
                          tunable_values[i], kTunables[i].address);
                continue;
            }
            const u32 word = memory.Read32(kTunables[i].address);
            memory.Write32(kTunables[i].address, (word & ~0xFFFu) | imm12);
            ++patched;
        }
        if (patched) {
            LOG_INFO(Core_ARM11,
                     "hooks: wide render patched {} instructions in the game's code (this library has no "
                     "tunables: rebuild it with the current 3dsrecomp and overrides/mh4u to keep them "
                     "recompiled)",
                     patched);
            Aot::RecheckCode();
        }
    }
#endif
}

bool OwnsContext(const RCtx* ctx) {
    return ctx != nullptr && ctx == top;
}

u32 SlowRead(u32 address, u32 size) {
    auto& memory = Core::System::GetInstance().Memory();
    return size == 1 ? memory.Read8(address) : size == 2 ? memory.Read16(address) : memory.Read32(address);
}

void SlowWrite(u32 address, u32 value, u32 size) {
    auto& memory = Core::System::GetInstance().Memory();
    if (size == 1) {
        memory.Write8(address, static_cast<u8>(value));
    } else if (size == 2) {
        memory.Write16(address, static_cast<u16>(value));
    } else {
        memory.Write32(address, value);
    }
}

void Call(RCtx* ctx, u32 target) {
    auto* hc = static_cast<AotHookCall*>(ctx->user);
    CopyOut(hc, ctx, false);
    hc->call_guest(hc, target);
    CopyIn(ctx, hc, false);
}

} // namespace Port::HooksAot

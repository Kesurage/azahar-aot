// The port's hooks (hooks.cpp) on a library made by 3dsrecomp, run by the generic AOT backend.
//
// hooks.cpp is written for the port's own code libraries (RCtx, hook points listed in
// config/hooks.txt). A 3dsrecomp library has a different context, so the library carries
// overrides/mh4u/mh4u_hooks.c (made by tools/gen_hook_overrides.py from the same hooks.txt): one
// override per hook point that calls the function set in the library's azahar_aot_hooks[]. This
// side sets them, and gives hooks.cpp an RCtx made from the registers and flags of the call,
// copied back when the hook returns, so hooks.cpp runs unchanged.
#pragma once

#include "common/common_types.h"

struct RCtx;

namespace Port::HooksAot {

/// Installs the hooks on the library the AOT backend has just chosen (Aot::SetLibraryLoadedCallback).
/// Without hook points in the library, Hooks::Install still runs for the options, net sync and so
/// on, and the log says what is missing.
void Install();

/// Whether `ctx` is one made here for a hook that is running (hooks.cpp's memory access and
/// rc_call, which belong to the port's own backend, ask before using it).
bool OwnsContext(const RCtx* ctx);
u32 SlowRead(u32 address, u32 size);
void SlowWrite(u32 address, u32 value, u32 size);
/// Calls the guest function at `target` with the registers of `ctx` (r14 is where it returns to).
void Call(RCtx* ctx, u32 target);

} // namespace Port::HooksAot

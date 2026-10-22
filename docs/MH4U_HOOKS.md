# The MH4U port's options on a 3dsrecomp library

Frame-rate cap, widescreen, touch screen overlay, screen filters, the skip button and the other
options in the app's Settings are the port's **hooks** (`mh4u/port/src/hooks.cpp`). They attach to
hook points listed in `mh4u/config/hooks.txt`. A library made by 3dsrecomp has no hook points of
its own, so until now none of those options did anything on it.

## How it works now

1. `tools/gen_hook_overrides.py` turns `hooks.txt` into `overrides/mh4u/mh4u_hooks.c`: one
   `RECOMP_OVERRIDE(addr)` per hook point. Each hands the call to the host, or runs the original
   function when no hook is set.
2. Build the library with those overrides: set the workflow's **overrides** input to
   `overrides/mh4u` (or `--overrides overrides/mh4u` on the command line).
3. When the app loads the library, `mh4u/port/src/hooks_aot.cpp` sets the hooks in the library's
   `azahar_aot_hooks[]` and runs `hooks.cpp` unchanged: each call's registers and flags are put
   in an `RCtx` for the hook and copied back when it returns.

The log tells you what happened: `hooks: ...` lines (options in effect), or a warning that the
library has no hook points, or that its hook ABI differs (rebuild it).

## Rebuild when

- `hooks.txt` changes: run the generator again, commit the new `mh4u_hooks.c`, rebuild the library.
- The log says the library has no hook points or another ABI.

## Wide render

The wide picture (`wide_render`, 560x240 for 21:9, HUD at the edges) changes three instruction
immediates (`config/tunables.txt`). The port's own libraries read them from a table.

**Best: recompiled.** `overrides/mh4u/mh4u_hooks.c` marks the three instructions with
`RECOMP_TUNABLE(address, variable)`. A 3dsrecomp built from this repo (`recomp/src`) generates
those instructions as a read of that variable, which the app sets when the game starts; nothing runs
on the fallback CPU. The library has to be built with the whole folder, `--overrides overrides/mh4u`,
by this repo's 3dsrecomp.

**Otherwise: patched in memory.** For a library made without the marks, `hooks_aot.cpp` checks that
each instruction holds what v1.3.0 has there, patches it in guest memory and calls
`Aot::RecheckCode()`: the two functions holding them (the bordered full-screen pass and
`sCamera::Viewport::setupDraw`) are flagged as changed and run on Azahar's own CPU. If a check
fails (another game version, or a `code.ips` mod that changes those instructions) wide render turns
itself off and the log says which instruction held what.

Log lines to look for: `hooks: tunable top_rows = 560 (read by the recompiled code)` or
`hooks: wide render patched 3 instructions`, and `hooks: widescreen: top screen rendered 560x240`.

Hook addresses are USA v1.3.0. A library of another version must not be built with them.

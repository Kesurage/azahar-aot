# Architecture

```
 game ROM (your dump) ──► 3dsrecomp build ──► <TITLEID>.so   (C compiled with the NDK; PC-side step)
                                                   │ ABI 4: Entry tables, Context, Host callbacks
 ┌─────────────────────────── Azahar process ──────▼─────────────────────────────────────────┐
 │  ARM_AOT (aot/src/arm_aot.cpp)           ◄── Aot::Library: lookup, stale check, CRO place   │
 │    run recompiled code ──────────────► EXIT_SVC ──► Kernel::SVCContext (Azahar HLE kernel) │
 │    no code / stale / odd instruction ─► fallback ARM_Dynarmic (Azahar's own JIT)            │
 │  memory: Azahar PageTable pointers used directly as read/write page tables; null pages      │
 │          (MMIO, GPU-cached, unmapped) → Host callbacks → MemorySystem                       │
 │  GPU (video_core) · DSP/audio · services · NWM/UDS network rooms · Qt/Android frontend      │
 └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

## Who does what

- **CPU**: `ARM_AOT`. The generated functions are plain C calls; `Context` holds r0-r15, flags, VFP
  (pointing at the backend's own arrays), the budget (instructions) and the page tables.
- **GPU / network / services**: untouched Azahar. The backend only talks to them through memory
  (page table) and system calls, exactly like dynarmic does, so shader JIT, texture packs, custom
  resolution, rooms and Citra-compatible lobby keep working.
- **Fallback**: Azahar's `ARM_Dynarmic` (interpreter on non-x86/arm64). The backend copies state
  Context ⇄ `ThreadContext` and `Step()`s until the PC reaches recompiled code again, so a game
  never has to be fully recompiled (3dsrecomp reports ~99.8% coverage on the games it was tested on).
  It also runs the single instructions the recompiler leaves to an interpreter (`Host.interpret`).

## Control flow of `ARM_AOT::Run()`

1. First call: find `<TITLEID>.so` (title id from the loader; update/DLC ids fall back to the base game's
   `0004 0000 xxxx xxxx`), open it, hash the executable's code in memory against what the library was
   built from (`Library::Check`) → functions changed by an update or code mod are marked stale and run on
   the fallback. No library → forward everything to dynarmic.
2. Budget = timer downcount. Look up `pc|thumb`; call the code; on return:
   - `EXIT_SVC` → charge ticks, `SVCContext::CallSVC(n)`, **return** (slice ends, as with dynarmic, so the
     kernel can reschedule). Runs on the emulation thread's own stack: no fibers, Java/JNI calls inside
     services are fine, and **save states work** (state is just registers at an SVC boundary).
   - `EXIT_BUDGET` → slice over; if no progress was made (next block larger than the slice) lend
     8192 instructions of budget and charge the overshoot.
   - `EXIT_UNWIND`/lookup miss → fallback batch.
3. CRO modules: patched `ldr:ro` calls `Aot::CROObserver` after LoadCRO/UnloadCRO →
   `Library::Place(name, base)` writes the module base the code reads, then hash-checks it.

## Accounting notes
- Ticks: budget counts instructions; they are charged 1:1 to Azahar's timer (dynarmic uses per-class
  tick tables, so cycle-exact timing differs slightly — same trade-off as the MH4U port).
- Exclusive monitor: reservation cleared at each slice start and whenever state crosses to the fallback.
- Multi-core (N3DS): cores run in sequence on one host thread, as in Azahar.

## Mapping MH4U-Android onto this
- Its CPU backend (`mh4u/port/src/arm_recomp.cpp`, fibers + own ABI) is **replaced** by `ARM_AOT`.
- Its Python recompiler (`mh4u/tools/recomp`) is replaced by 3dsrecomp for the CPU path; MH4U-specific
  code mods become `--overrides` C files (`recomp/docs/overrides.md`, `RECOMP_OVERRIDE(addr)`), which is
  where its 60 fps / widescreen / HUD hooks (`mh4u/port/src/hooks.cpp`) have to be ported to.
- Reusable as-is from `mh4u/port/android`: Compose app, launcher, controls, multiplayer UI; and
  `mh4u/port/azahar/azahar-android.patch` (verified to apply to this Azahar tree) for the renderer/
  layout/network changes. It conflicts conceptually with `patches/0001` only in `core.cpp/core.h`
  (its `SetCPUFactory` has no ExclusiveMonitor parameter) — pick one when merging.
- Zakuro: use its `recompiled.rs` as the behavioural reference; its `hints` mechanism
  (`recomp/hints.txt`: addresses where runs fell back to the interpreter, fed into the next build) is a
  natural next feature: have `ARM_AOT` log fallback entry points in the same format.

# Status (honest)

**Verified here**
- `Aot::Library` (open, ABI check, binary-search lookup, Thumb entries, module placement, FNV-1a piece
  hashing, stale marking, cache invalidation) against a hand-built ABI-4 library: `aot/tests/run.sh` passes.
- `patches/0001-*.patch` applies cleanly to the bundled Azahar tree (`git apply --check`).
- `mh4u/port/azahar/azahar-android.patch` + `patches/0002` apply to it together; `0001`+`0002` apply to it for stock Azahar. (The PC patch `azahar.patch` does not apply: 2 hunks drift.)
- Both workflows parse as YAML; they have not run.
- `recomp.h` compiles as C++ (needed by the backend).

**Written but NOT compiled** (no Boost/fmt/Android NDK in this sandbox, Azahar's externals are empty submodules)
- `aot/src/arm_aot.cpp/.h`, the patch's C++ edits. Expect small compile errors / API drift on first build —
  checked by hand against `ARM_Interface`, `Memory::PageTable`, `SVCContext`, `FileUtil`, loader APIs.

**Not done / needs real game testing**
- No game was recompiled or run (no ROM; 3dsrecomp needs a decrypted dump + Rust 1.95 + C compiler).
- Fallback batching uses `ARM_Dynarmic::Step()` per instruction: fine at ~0.2% interpreted, slow if a game
  runs a lot of un-recompiled code (check logs for fallback counts; add hints to the next build).
- Android: library must live in the app's private dir; the default path `<UserDir>/aot` is a *virtual*
  path on Android — set `AZAHAR_AOT_DIR` (or call `Aot::SetLibraryDir`) with the real files path from JNI.
- Azahar's `Settings` UI has no AOT toggle yet; it is automatic when a library exists.
- Spin-loop skipping (`ARM_AOT::SpinSkip`, off with `AZAHAR_AOT_IDLE=0`) is written but NOT compiled or tried:
  watch the `AOT idle core` log line, and turn it off first if a game hangs or an input feels late.
- MH4U hooks (60 fps, widescreen, HUD) not ported to overrides yet.
- Stale-check/`recomp_origins` need libraries of generation ≥ 3 (current 3dsrecomp).

**CI notes**
- `library-aot` feeds 3dsrecomp a game image directly. 3dsrecomp refuses an *update* .cia and has no
  `--update` option: for a game whose code is in the update (MH4U v1.3.0) give it a decrypted game image that
  already contains the update's code; otherwise the stale-code check sends changed functions to the JIT.
- `library-aot` uses Rust channel `stable` from `recomp/rust-toolchain.toml` (README says 1.95+).
- The MH4U app, when it picks the AOT backend, has none of the MH4U hooks (60 fps/widescreen) until they are
  ported to 3dsrecomp overrides.
- `tools/recomp-update` (game + update → library) is **not compiled**: crates.io is unreachable from this sandbox. It is a transcription of Zakuro's `recompile.rs`/`loader.rs` logic against the same zakuro-fs API calls; CI will show any signature drift.

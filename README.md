# azahar-aot

One project from four:

| Folder | What it was | Role here |
|---|---|---|
| `azahar/` | AzaharPlus (with locale translations) | **The platform**: kernel, services, GPU (PICA200 → Vulkan/GL), DSP audio, network rooms/UDS, Android frontend. Also the *fallback CPU* (dynarmic). |
| `recomp/` | 3dsrecomp | **The AOT compiler**: turns a game's ARM/Thumb/VFP code (exe + CRO modules) into C → `<TITLEID>.so` (interface ABI 4, `recomp/docs/interface.md`). |
| `aot/` | **new** | **The glue**: `ARM_AOT`, an Azahar CPU backend that runs those libraries, and `Aot::Library`, the host side of the ABI (C++ port of `recomp/abi`). |
| `reference/zakuro/` | Zakuro | **Reference host** (Rust): how a complete emulator drives the ABI (`crates/zakuro-core/src/recompiled.rs`, `loader.rs`, `services/ldr_ro.rs`). Not built here. |
| `mh4u/` | MH4U-Android | **The app + game layer** (its Android app builds in your CI; now also loads AOT libraries): first AOT-on-Azahar port (own game-specific ABI), Android app, hooks, widescreen/60fps research. Its hooks become 3dsrecomp *overrides*. |

CPU = AOT. GPU, audio, network, filesystem, kernel = Azahar. Code AOT doesn't cover = Azahar's JIT.
See `docs/ARCHITECTURE.md` for how it works and `docs/STATUS.md` for what is and isn't verified.

## Build with GitHub Actions (same flow you already use for MH4U-Android)

Push this folder to your **private** repo (you can delete `azahar/` and `reference/`: CI pulls your
AzaharPlus fork, `klark231/AzaharPlus@locale-translations`, exactly like `mh4u/.github/workflows/android.yml`).

0. **Which file?** MH4U: its **update .cia** (`update.cia`) with workflow **library-mh4u** — as before, and the
   app picks this library first (it has the hooks). Any other game: its **base game** image (`game.3ds`/`.cia`/`.cxi`)
   with **library-aot**. If the game has an update, upload it too as `update.cia` (decrypted): library-aot then
   recompiles game + update together, the way Zakuro's Recompile button does (`tools/recomp-update`, because
   3dsrecomp's own command line refuses update files).
1. **Library** (any game other than MH4U): upload your decrypted dump as `game.3ds` / `game.cia` / `game.cxi` to a
   release named `game-data`, then run **library-aot** (`.github/workflows/library-aot.yml`).
   It builds 3dsrecomp, recompiles the game and compiles it with the NDK → artifact `aot-code-library`
   (`<TITLEID>.so`). MH4U's own recompiler workflow (`mh4u/.github/workflows/library.yml`) still works.
2. **APK**: run **android-aot** (`.github/workflows/android-aot.yml`). It applies
   `mh4u/port/azahar/azahar-android.patch` + `patches/0002-ldr-ro-cro-observer.patch` to your fork and
   builds the MH4U app, which now contains both backends.
3. **On the phone**: put the `.so` in the Azahar user folder under `aot/` (`aot/<TITLEID>.so`); the app copies
   it into its private storage itself. Start the game. Log line to look for:
   `running 3dsrecomp code from … (generic AOT backend)`.

Order of choice in the app: MH4U's own library (`recomp/<id>.so`, with its hooks) → 3dsrecomp library
(`aot/<id>.so`) → plain Azahar CPU. Env overrides: `AZAHAR_AOT_LIBRARY` (exact file), `AZAHAR_AOT_DIR`.

Stock Azahar instead of the MH4U app: `scripts/apply-patches.sh`, build with `-DAOT_DIR=<repo>/aot`.

Host test (no Azahar needed): `aot/tests/run.sh`.

Licence: GPL-3.0-or-later overall (Azahar and MH4U are GPL-3.0; 3dsrecomp and Zakuro are MIT).
No game data is included; build libraries from your own dump and don't share them.

# Android changes and validation

The Android APK uses Azahar for the emulated platform, graphics, audio and fallback CPU.
It can use either the MH4U-specific recompiled library or the generic 3dsrecomp AOT backend.
These changes improve that application; they do not turn it into a standalone native game,
provide a game dump or textures, or establish a 60 fps performance result.

## Changes in this revision

- Landscape Overlay layout has a persistent **Hide secondary screen / Show secondary screen**
  control, also available with a physical controller. Hiding retains the chosen position,
  size and opacity across app restarts. Newly enabled overlays use 60% opacity; existing
  configurations keep their values.
- The Android native layout switches to a single screen when the secondary screen is hidden.
  Screen options load before window creation, including when no recompiled library is present.
  Portrait retains its existing separate layout and does not show the landscape toggle.
- Display defaults now describe the native 5:3 top screen and disabled widescreen render path.
  Existing saved settings are preserved. Widescreen changes require a new game start.
- The custom overlay's resolution-scale dimensions use percentage units consistently.
  This corrects the screenshot/video-output layout calculation; it is not a measured Android
  rendering performance improvement.
- Adaptive resolution requires a positive finite speed sample before increasing resolution.
  Empty/reset or nonfinite performance windows are no longer counted as spare capacity.
- Generic AOT code invalidation guards stale compiled functions, including cached direct
  calls. Revalidation occurs after the guest writes at CPU dispatch, accommodating callers
  that invalidate before writing. This requires generation-3 origins metadata, as did the
  existing stale-code check. It is separate from the MH4U-specific backend.
- Android CI runs host regression checks and records the exact app commit, resolved Azahar
  commit, submodule revisions, patches and Actions run in `assets/build-info.json` before
  signing. APK filenames and SHA256 receipts identify each build. Failed checks or builds
  cannot publish the latest APK alias.

## Repeatable host checks

```sh
bash aot/tests/run.sh
sh scripts/test_native_checks.sh
KOTLINC=/path/to/kotlinc bash mh4u/port/android/tests/run.sh
bash scripts/test_framebuffer_layout.sh /path/to/unpatched-azahar
```

The AOT suite compiles a small ABI-compatible fixture, the governor suite compiles the actual
governor against simulated thermal/performance inputs, and the Kotlin suite compiles the real
INI and overlay preference helpers. The framebuffer suite applies the actual Android patch to
a temporary copy of the upstream layout source and compiles it against lightweight settings
stubs. These checks do not execute a game or validate the full Android UI.

## Build identity

Use the `android-aot` Actions run for the desired source commit. A successful run publishes an
APK named `mh4u-<commit-prefix>-<run-id>-<attempt>.apk`, its SHA256 file and its build receipt.
Main and `android-v*` tag runs also update `app-release.apk` in the `builds` release.
Branch-only manual runs retain their files as Actions artifacts.

Android App info displays version `0.1-<commit-prefix>`; unversioned local builds say `0.1-dev`.
The receipt records whether the configured release key was used. Do not assume that an APK
signed with a temporary debug key can update an installed release-key build.

## Required phone validation

No Infinix phone was connected during local validation. The following remain device checks:

1. Install the new signed APK as an update and confirm its displayed source version.
2. With a legally obtained local game and matching library, confirm from runtime logs which
   backend actually loads. The presence of AOT support in the APK is not proof it is in use.
3. In landscape Overlay layout, show, move and resize the secondary screen, change opacity,
   hide and show it, swap screens, rotate, and restart the game. Confirm touch input maps to
   the visible screen and the toggle is accessible with a controller connected.
4. Check an unstretched native-aspect image, then test optional widescreen settings after a
   restart. Use a recognizable circle or HUD element to check geometry.
5. Measure frame pacing, emulation speed and temperature during a repeatable sustained hunt
   on the Helio G100. Record renderer, resolution, backend, texture pack and game version.
   Test HD texture packs separately; no pack is included or verified by this build.

Until those checks run on the phone, smooth 60 fps, reduced heat, touch correctness and
HD-texture performance are unverified.

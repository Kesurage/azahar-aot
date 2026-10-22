#!/usr/bin/env bash
# Builds 3dsrecomp (the AOT compiler, Rust 1.95+) and, given a decrypted ROM, a game library.
#   scripts/build-recomp.sh                       # just build the tool
#   scripts/build-recomp.sh game.3ds              # library for this PC
#   ANDROID_NDK_HOME=... scripts/build-recomp.sh game.3ds android   # arm64 library for the phone
# Android: the library is compiled by the NDK's clang (CC), so it must be made on a PC (or in
# Termux with its own clang). Copy <TITLEID>.so into the app's *private* files/aot/ folder:
# shared storage is mounted noexec and dlopen() fails there.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
(cd "$root/recomp" && cargo build --release)
[ $# -ge 1 ] || exit 0
rom="$1"; target="${2:-host}"; out="$root/build/recomp-$target"; mkdir -p "$out"
if [ "$target" = android ]; then
    : "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
    export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android29-clang"
fi
"$root/recomp/target/release/3dsrecomp" build "$rom" "$out"
ls -l "$out"/*.so 2>/dev/null || ls -l "$out"

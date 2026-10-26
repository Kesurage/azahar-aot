#!/usr/bin/env bash
# Exercise the actual Android EGL window and base implementation against a failing fake driver.
# No Android SDK, GPU, game, or downloaded upstream source is needed.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

# CXXFLAGS may contain sanitizer flags; ordinary word splitting is intentional.
${CXX:-c++} ${CXXFLAGS:-} -std=c++20 -UNDEBUG -Wall -Wextra -Werror \
    -I"$repo/scripts/test_stubs/egl" -I"$repo/mh4u/port/android/jni" \
    "$repo/scripts/test_egl_startup.cpp" \
    "$repo/mh4u/port/android/jni/emu_window_android.cpp" \
    "$repo/mh4u/port/android/jni/emu_window_gl.cpp" \
    -o "$work/test_egl_startup"
"$work/test_egl_startup"

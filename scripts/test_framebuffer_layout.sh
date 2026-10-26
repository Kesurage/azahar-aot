#!/usr/bin/env bash
# Compile the actual Android-patched layout against lightweight settings/logging stubs.
# Usage: bash scripts/test_framebuffer_layout.sh [azahar-source]
# No source checkout is modified. No game files or Android SDK are required.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
source_dir=${1:-"$repo/azahar"}
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

mkdir -p "$work/src/core/frontend" "$work/src/common"
for file in core/frontend/framebuffer_layout.cpp core/frontend/framebuffer_layout.h \
            core/3ds.h common/math_util.h common/common_types.h; do
    cp "$source_dir/src/$file" "$work/src/$file"
done
if [[ -f "$source_dir/src/core/frontend/top_screen.h" ]]; then
    cp "$source_dir/src/core/frontend/top_screen.h" "$work/src/core/frontend/top_screen.h"
fi
patch_paths=(--include=src/core/frontend/framebuffer_layout.cpp
             --include=src/core/frontend/framebuffer_layout.h
             --include=src/core/frontend/top_screen.h)
layout_patch="$repo/mh4u/port/azahar/azahar-android.patch"
if git -C "$work" apply --check "${patch_paths[@]}" "$layout_patch" 2>/dev/null; then
    git -C "$work" apply "${patch_paths[@]}" "$layout_patch"
elif git -C "$work" apply --reverse --check "${patch_paths[@]}" "$layout_patch" 2>/dev/null; then
    : # The selected upstream already contains this exact patch, as the APK build also allows.
else
    git -C "$work" apply --check "${patch_paths[@]}" "$layout_patch"
fi

# CXX/CXXFLAGS may include sanitizer flags; ordinary word splitting is intentional here.
${CXX:-c++} ${CXXFLAGS:-} -std=c++20 -DANDROID -Wall -Wextra \
    -Wno-missing-field-initializers -pthread \
    -I"$repo/scripts/test_stubs/framebuffer" -I"$work/src" \
    -I"$repo/mh4u/port/android/jni" \
    "$repo/scripts/test_framebuffer_layout.cpp" "$work/src/core/frontend/framebuffer_layout.cpp" \
    -o "$work/test_framebuffer_layout"
"$work/test_framebuffer_layout"

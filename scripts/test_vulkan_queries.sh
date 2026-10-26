#!/usr/bin/env bash
# Test the real patched query chains using Vulkan-Hpp, without a GPU or Vulkan loader.
# Usage: bash scripts/test_vulkan_queries.sh [unpatched-azahar-source] [vulkan-include-dir]
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
source_dir=${1:-"$repo/azahar"}
headers=${2:-"$source_dir/externals/vulkan-headers/include"}
test -f "$headers/vulkan/vulkan.hpp"
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/src/video_core/renderer_vulkan"
cp "$source_dir/src/video_core/renderer_vulkan/vk_instance.cpp" \
   "$source_dir/src/video_core/renderer_vulkan/vk_common.h" \
   "$work/src/video_core/renderer_vulkan/"
if [[ -f "$source_dir/src/video_core/renderer_vulkan/vk_capability_queries.h" ]]; then
    cp "$source_dir/src/video_core/renderer_vulkan/vk_capability_queries.h" \
       "$work/src/video_core/renderer_vulkan/"
fi
query_patch="$repo/patches/0003-vulkan-capability-queries.patch"
if git -C "$work" apply --check "$query_patch" 2>/dev/null; then
    git -C "$work" apply "$query_patch"
elif git -C "$work" apply --reverse --check "$query_patch" 2>/dev/null; then
    : # Already present, matching the APK workflow's patch policy.
else
    git -C "$work" apply --check "$query_patch"
fi
${CXX:-c++} ${CXXFLAGS:-} -std=c++20 -UNDEBUG -Wall -Wextra -Werror \
    -I"$work/src" -isystem "$headers" \
    "$repo/scripts/test_vulkan_queries.cpp" -o "$work/test_vulkan_queries"
"$work/test_vulkan_queries"

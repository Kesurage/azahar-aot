#!/usr/bin/env bash
# Builds a fake ABI-4 library and runs the host-side checks of aot_library (no Azahar needed).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
python3 -I "$here/gen_hashes.py" "$tmp/hashes.h"
${CC:-gcc} -shared -fPIC -I"$here/../include" -I"$tmp" -o "$tmp/fake.so" "$here/fake_lib.c" -lm
${CXX:-g++} -std=c++17 -Wall -I"$here/../include" -I"$here/../src" \
    -o "$tmp/test" "$here/aot_library_test.cpp" "$here/../src/aot_library.cpp" -ldl
"$tmp/test" "$tmp/fake.so"

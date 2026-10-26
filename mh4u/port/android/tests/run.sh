#!/usr/bin/env bash
set -euo pipefail

# Plain JVM tests: no Android SDK, emulator, Gradle or game files required.
test_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
sources_dir="$test_dir/../app/src/main/java/org/mh4u/port"
test_build_dir=$(mktemp -d "${TMPDIR:-/tmp}/mh4u-android-tests.XXXXXX")
trap 'rm -rf -- "$test_build_dir"' EXIT

"${KOTLINC:-kotlinc}" \
    "$sources_dir/Ini.kt" \
    "$sources_dir/TouchOverlayPreferences.kt" \
    "$test_dir/TouchOverlayPreferencesTest.kt" \
    -include-runtime -d "$test_build_dir/frontend-tests.jar"
java -jar "$test_build_dir/frontend-tests.jar"

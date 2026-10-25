#!/bin/sh
# Builds and runs the two standalone checks of the Android app's native side:
#   scripts/test_merge_profile.cpp  - the settings upgrade (native.cpp's MergeProfile)
#   scripts/test_governor_sim.cpp   - the adaptive resolution governor, in real time (~35 s)
# Usage: sh scripts/test_native_checks.sh
set -e
cd "$(dirname "$0")/.."

echo "== MergeProfile =="
g++ -std=c++17 -Wall -o /tmp/merge_test scripts/test_merge_profile.cpp
/tmp/merge_test

echo
echo "== Governor simulation =="
STUBS=/tmp/gov_stubs
rm -rf $STUBS
mkdir -p $STUBS/common/logging $STUBS/core $STUBS/fmt
cat > $STUBS/fmt/format.h <<'EOF'
#pragma once
#include <string>
namespace fmt {
template<typename... A> std::string format(const char* f, const A&... a) { return f; }
}
EOF
cat > $STUBS/common/settings.h <<'EOF'
#pragma once
#include <cstdint>
using u32 = std::uint32_t;
namespace Settings {
struct Switchable {
    unsigned v = 0;
    Switchable& operator=(unsigned x) { v = x; return *this; }
    unsigned GetValue() const { return v; }
};
struct Values { Switchable resolution_factor; };
inline Values values;
}
EOF
cat > $STUBS/common/logging/log.h <<'EOF'
#pragma once
#define LOG_INFO(cat, ...) (void)0
#define LOG_WARNING(cat, ...) (void)0
#define LOG_CRITICAL(cat, ...) (void)0
EOF
cat > $STUBS/core/core.h <<'EOF'
#pragma once
EOF
cat > $STUBS/core/perf_stats.h <<'EOF'
#pragma once
namespace Core {
class System {
public:
    struct Results { double emulation_speed; };
    Results GetAndResetPerfStats();
};
}
double GovernorSimSpeed();
inline Core::System::Results Core::System::GetAndResetPerfStats() {
    return Results{GovernorSimSpeed()};
}
EOF
cat > $STUBS/config.h <<'EOF'
#pragma once
namespace App {
struct Config {
    bool adaptive_resolution = true;
    int resolution = 0;
};
}
EOF
g++ -std=c++17 -DGOVERNOR_THERMAL_ROOT='"/tmp/fake_thermal"' \
    -I$STUBS -I mh4u/port/android/jni -Wall \
    -o /tmp/gov_sim scripts/test_governor_sim.cpp mh4u/port/android/jni/governor.cpp
/tmp/gov_sim

// A standalone simulation of the adaptive resolution governor (port/android/jni/governor.cpp):
// the real file is compiled against stubs, with a made-up thermal tree and an injectable game
// speed, and the state machine is driven through a whole hunt: steady, behind, hot, recovered,
// and turned off. Real wall-clock timing (the governor polls every 1.5 s) - a ~30 s run.
//   g++ -std=c++17 -DGOVERNOR_THERMAL_ROOT='"/tmp/fake_thermal"' \
//       -o /tmp/gov_sim scripts/test_governor_sim.cpp mh4u/port/android/jni/governor.cpp
//   /tmp/gov_sim
#include <chrono>
#include <cstdio>
#include <fstream>
#include <filesystem>
#include <iostream>
#include <string>
#include <thread>

namespace fs = std::filesystem;

// ── the stubs: a shared tree at /tmp/gov_stubs, so the real governor.cpp compiles against it
// unchanged; the game's speed is injected through the hook below ──
double g_speed = 1.0;   // 1.0: the game keeps up
double GovernorSimSpeed() { return g_speed; }

#include "common/settings.h"
#include "core/perf_stats.h"
#include "config.h"
#include "governor.h"

// ── the driving ──
static int failures = 0;

void Check(const std::string& what, int got, int want) {
    const bool ok = got == want;
    std::cout << (ok ? "  ok  " : "  FAIL") << " " << what << ": " << got
              << (ok ? "" : " want " + std::to_string(want)) << "\n";
    if (!ok) {
        ++failures;
    }
}

int main() {
    // a phone's thermal tree: two zones of ours and a battery's to ignore
    const std::string root = "/tmp/fake_thermal";
    fs::remove_all(root);
    fs::create_directories(root + "/thermal_zone0");
    fs::create_directories(root + "/thermal_zone1");
    fs::create_directories(root + "/thermal_zone2");
    std::ofstream(root + "/thermal_zone0/type") << "gpu_thermal\n";
    std::ofstream(root + "/thermal_zone1/type") << "cpu_thermal\n";
    std::ofstream(root + "/thermal_zone2/type") << "battery_thermal\n";
    std::ofstream(root + "/thermal_zone0/temp") << "41000\n";
    std::ofstream(root + "/thermal_zone1/temp") << "39000\n";
    std::ofstream(root + "/thermal_zone2/temp") << "35000\n";  // ignored: the battery's

    Core::System system;
    App::Config config;  // adaptive on, native resolution

    // the call the game loop makes, over and over
    const auto loop = [&](std::chrono::milliseconds run) {
        const auto until = std::chrono::steady_clock::now() + run;
        while (std::chrono::steady_clock::now() < until) {
            Governor::Tick(system, config);
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
    };

    // 1. it comes on at the 3DS's own resolution: the run starts smooth, never at the
    // screen's own pixels (on a 1080x2640 phone that is over four times the game's pixels)
    std::cout << "the game starts (steady, cool):\n";
    loop(std::chrono::milliseconds(1700));
    const auto s0 = Governor::Get();
    Check("active", s0.active ? 1 : 0, 1);
    Check("starts at the 3DS's own (100)", s0.current, 100);
    Check("resolution_factor 100", static_cast<int>(Settings::values.resolution_factor.v), 100);
    Check("thermal read", s0.thermal >= 40.0 && s0.thermal < 42.0 ? 1 : 0, 1);
    Check("the battery's zone ignored", s0.zone.find("gpu") != std::string::npos ? 1 : 0, 1);

    // 2. cool and keeping up: four good polls and it climbs a step (no dwell for the first)
    std::cout << "cool and keeping up - the first climb:\n";
    loop(std::chrono::milliseconds(7500));  // four good polls (~6 s of polling)
    Check("climbed to 125", Governor::Get().current, 125);
    Check("resolution_factor 125", static_cast<int>(Settings::values.resolution_factor.v), 125);

    // 3. the game falls behind: two polls and it gives the step back
    std::cout << "the game falls behind (85% speed):\n";
    g_speed = 0.85;
    loop(std::chrono::milliseconds(3300));  // two polls
    Check("dropped back to 100 after 2 slow polls", Governor::Get().current, 100);

    // 4. it keeps up again, but the phone is hot now: heat drops a step at once (none to give:
    // it holds the 3DS's own, the floor)
    std::cout << "the game catches up, but hot now (gpu 61 C):\n";
    g_speed = 1.0;
    std::ofstream(root + "/thermal_zone0/temp") << "61000\n";
    loop(std::chrono::milliseconds(1700));
    Check("holds the floor (100)", Governor::Get().current, 100);

    // 5. cool and keeping up: after the dwell it climbs again, one step at a time
    std::cout << "cool (gpu 40 C) and keeping up - the climb back:\n";
    std::ofstream(root + "/thermal_zone0/temp") << "40000\n";
    loop(std::chrono::milliseconds(11000));  // dwell (10 s) + good polls
    Check("back up one step (125)", Governor::Get().current, 125);

    // 6. the player turns it off in the menu: what the settings say stands again
    std::cout << "turned off with a resolution of 2 picked:\n";
    config.adaptive_resolution = false;
    config.resolution = 2;
    Governor::Tick(system, config);
    Check("inactive", Governor::Get().active ? 0 : 1, 1);
    Check("the settings' 200 stands", static_cast<int>(Settings::values.resolution_factor.v), 200);

    // 7. the game stops: nothing lingers
    std::cout << "the game stops:\n";
    Governor::Stop();
    Check("state cleared (inactive)", Governor::Get().active ? 0 : 1, 1);

    std::cout << (failures ? "\nFAILURES: " + std::to_string(failures) : "\ngovernor simulation passes") << "\n";
    return failures ? 1 : 0;
}

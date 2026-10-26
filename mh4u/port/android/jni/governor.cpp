#include "governor.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <dirent.h>
#include <fmt/format.h>
#include <iterator>
#include <mutex>
#include <utility>

#include "common/logging/log.h"
#include "common/settings.h"
#include "config.h"
#include "core/core.h"
#include "core/perf_stats.h"

namespace Governor {

namespace {

using Clock = std::chrono::steady_clock;

// The ladder of internal resolutions, in % of the 3DS's own. It starts at 100: the pixels the
// game was drawn with - on a phone screen those are already stretched over the whole panel, and
// rendering them costs a fraction of what "the screen's own resolution" costs (on a 1080x2640
// panel that is over four times the 3DS's pixels, more than a mid-range GPU holds at 60 fps).
// When the game keeps up and the phone stays cool the ladder climbs a step at a time - each step
// worth a notch of sharpness - and gives the step back the moment the frame rate or the
// thermometer says otherwise. Never below 100: below that the picture only gets softer, the
// speed doesn't improve (the GPU is no longer the one behind).
constexpr int LADDER[] = {100, 125, 150, 175, 200, 250, 300};
constexpr std::size_t LADDER_BOTTOM = 0;

// The poll cadence, and the trend each decision needs.
constexpr auto POLL = std::chrono::milliseconds(1500);
constexpr int SLOW_POLLS_TO_DROP = 2;   // ~3 s behind before giving up a step of sharpness
constexpr int GOOD_POLLS_TO_RAISE = 4;  // ~6 s of headroom before taking it back
constexpr auto DWELL_AFTER_DROP = std::chrono::seconds(10); // the shell cools slower than the die

// The game is behind when its emulated speed is under this (the frame limiter's number is 1.0
// whenever the game keeps up, whatever fps it draws at: menus and quests read the same).
constexpr double SPEED_BEHIND = 0.93;
constexpr double SPEED_CAUGHT_UP = 0.999;

// °C. Down at or over HOT - phones begin to hold their GPU and CPU back around here, and a hot
// run only throttles harder - and up again only under COOL. Both wide enough for the zones the
// kernels actually name (the SoC's and CPU's read higher than the shell's; a skin zone is what
// the phone's own throttle listens to).
constexpr double HOT = 56.0;
constexpr double COOL = 46.0;

std::mutex g_mutex;
State g_state;
Clock::time_point g_last_poll;    ///< when the trend was last read
Clock::time_point g_last_change;  ///< when the resolution last moved
int g_slow = 0;                   ///< polls in a row the game was behind
int g_good = 0;                   ///< polls in a row it kept up and the phone stayed cool
std::size_t g_step = LADDER_BOTTOM;  ///< where on the ladder we are (0: the 3DS's own)

/// The zones whose heat is ours: the GPU's, the CPU's, the SoC's, the board's, the shell's.
/// The battery's sits at 30-45°C whatever the game does (charging moves it more than we do).
bool Interesting(const std::string& type) {
    const auto has = [&](const char* w) { return type.find(w) != std::string::npos; };
    return !(has("battery") || has("batt") || has("usb") || has("charger"));
}

/// The hottest zone that is ours to heat, in °C, and its name. -1 when nothing could be read
/// (a phone that hides them): the governor then leans on the game's speed alone.
/// (GOVERNOR_THERMAL_ROOT: the test harness reads a made-up tree instead of /sys.)
std::pair<double, std::string> ReadThermal() {
#ifndef GOVERNOR_THERMAL_ROOT
#define GOVERNOR_THERMAL_ROOT "/sys/class/thermal"
#endif
    DIR* dir = opendir(GOVERNOR_THERMAL_ROOT);
    if (!dir) {
        return {-1.0, {}};
    }
    double best = -1.0;
    std::string where;
    while (dirent* entry = readdir(dir)) {
        const std::string name = entry->d_name;
        if (name.compare(0, 12, "thermal_zone") != 0) {
            continue;
        }
        const std::string base = GOVERNOR_THERMAL_ROOT "/" + name + "/";
        std::string type;
        if (std::FILE* f = std::fopen((base + "type").c_str(), "r")) {
            char buf[72] = {};
            if (std::fgets(buf, sizeof buf, f)) {
                type = buf;
            }
            std::fclose(f);
        }
        std::transform(type.begin(), type.end(), type.begin(),
                       [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
        while (!type.empty() && (type.back() == '\n' || type.back() == '\r')) {
            type.pop_back();
        }
        if (!Interesting(type)) {
            continue;
        }
        long value = 0;
        if (std::FILE* f = std::fopen((base + "temp").c_str(), "r")) {
            const int got = std::fscanf(f, "%ld", &value);
            std::fclose(f);
            if (got != 1) {
                continue;
            }
        } else {
            continue;
        }
        // kernels report milli-degrees (45000) or degrees (45); anything over 1000 is milli
        const double c = value > 1000 ? value / 1000.0 : static_cast<double>(value);
        if (c > best) {
            best = c;
            where = type.empty() ? name : type;
        }
    }
    closedir(dir);
    return {best, where};
}

/// One step of the ladder, as the settings count it (0: the screen's own resolution).
void ApplyStep(std::size_t step) {
    Settings::values.resolution_factor = static_cast<u32>(LADDER[step]);
}

/// A resolution the settings asked for, in % of the 3DS's (0: the governor's own floor -
/// the 3DS's native pixels - when the governor is off and nothing else was picked).
void ApplyConfigured(int resolution) {
    Settings::values.resolution_factor =
        resolution == 0 ? 100u : static_cast<u32>(resolution) * 100;
}

/// Publishes what the poll read, with the lock already held (the poll path below).
void PublishLocked(double thermal, const std::string& zone) {
    g_state.current = LADDER[g_step];
    g_state.thermal = thermal;
    g_state.zone = zone;
}

/// A step taken while the lock is held: the setting moves, and so does what the HUD shows.
void TakeStep(std::size_t step) {
    g_step = step;
    ApplyStep(g_step);
    g_state.current = LADDER[g_step];
}

} // namespace

void Tick(Core::System& system, const App::Config& config) {
    const bool want = config.adaptive_resolution && config.resolution == 0;
    if (!want) {
        // off, or the player picked a resolution of their own: what the settings say stands
        std::scoped_lock lock(g_mutex);
        if (g_state.active) {
            g_state.active = false;
            g_state.current = 0;
            g_step = LADDER_BOTTOM;
            g_slow = g_good = 0;
            ApplyConfigured(config.resolution);
            LOG_INFO(Frontend, "adaptive resolution off");
        }
        return;
    }

    const auto now = Clock::now();
    {
        std::scoped_lock lock(g_mutex);
        if (!g_state.active) {
            g_state.active = true;
            g_step = LADDER_BOTTOM;      // the 3DS's own pixels: the run starts smooth
            g_slow = g_good = 0;
            g_last_poll = now - POLL;    // poll at once
            g_last_change = now - DWELL_AFTER_DROP;  // the first climb needs no dwell
            ApplyStep(g_step);
            g_state.current = LADDER[g_step];
            g_state.thermal = -1.0;
            g_state.zone.clear();
            LOG_INFO(Frontend, "adaptive resolution on: the 3DS's own pixels, climbing while "
                               "the game keeps up and the phone stays cool");
            return;
        }
        if (now - g_last_poll < POLL) {
            return;  // between polls: nothing to do
        }
        g_last_poll = now;
    }

    // the trend since the last poll: the game's own speed, and the phone's heat
    const double speed = system.GetAndResetPerfStats().emulation_speed;
    const auto [thermal, zone] = ReadThermal();

    // what the decision touches and what the HUD shows, under the one lock
    std::scoped_lock lock(g_mutex);
    PublishLocked(thermal, zone);

    // Empty/reset sampling windows are not evidence of spare GPU capacity. The HUD also
    // samples performance counters, so require a real finite sample before climbing.
    const bool valid_speed = std::isfinite(speed) && speed > 0.0;
    const bool behind = valid_speed && speed < SPEED_BEHIND;
    const bool caught_up = valid_speed && speed >= SPEED_CAUGHT_UP;
    const bool cool = thermal < 0.0 || thermal < COOL;  // unreadable: the speed alone decides
    const bool hot = thermal >= HOT;

    // down: the thermometer speaks at once (heat only builds while it is being watched); the
    // game's speed needs a second poll behind (one slow window can be a shader being built)
    std::string why;
    if (hot) {
        why = fmt::format("{} at {:.0f}°C", zone, thermal);
    } else if (behind && ++g_slow >= SLOW_POLLS_TO_DROP) {
        why = fmt::format("the game fell behind ({:.0f}% speed)", speed * 100.0);
    } else if (!behind) {
        g_slow = 0;
    }
    if (!why.empty()) {
        g_good = 0;
        if (g_step > LADDER_BOTTOM) {
            const int from = LADDER[g_step];
            TakeStep(g_step - 1);
            g_last_change = now;
            LOG_INFO(Frontend, "adaptive resolution: {}% -> {}% ({})",
                     from, LADDER[g_step], why);
        }
        return;
    }

    // up: headroom that lasts (four good polls in a row, cool). One step at a time, and not
    // again until the step before it had time to be felt by the game and the shell.
    if (caught_up && cool) {
        ++g_good;
    } else {
        g_good = 0;
    }
    if (g_step + 1 < std::size(LADDER) && g_good >= GOOD_POLLS_TO_RAISE &&
        now - g_last_change >= DWELL_AFTER_DROP) {
        const int from = LADDER[g_step];
        TakeStep(g_step + 1);
        g_last_change = now;
        g_good = 0;
        LOG_INFO(Frontend, "adaptive resolution: {}% -> {}% (kept up, {} at {:.0f}°C)",
                 from, LADDER[g_step], zone.empty() ? "no zone read" : zone, thermal);
    }
}

void Stop() {
    std::scoped_lock lock(g_mutex);
    g_state = {};
    g_step = LADDER_BOTTOM;
    g_slow = g_good = 0;
}

State Get() {
    std::scoped_lock lock(g_mutex);
    return g_state;
}

void Reassert(const App::Config& config) {
    std::scoped_lock lock(g_mutex);
    if (!g_state.active) {
        return;
    }
    if (config.adaptive_resolution && config.resolution == 0) {
        ApplyStep(g_step);  // still wanted: the governor's word stands
        return;
    }
    // turned off, or a resolution was picked: what the settings say stands, at once
    g_state.active = false;
    g_state.current = 0;
    g_state.thermal = -1.0;
    g_state.zone.clear();
    g_step = LADDER_BOTTOM;
    g_slow = g_good = 0;
    ApplyConfigured(config.resolution);
    LOG_INFO(Frontend, "adaptive resolution off");
}

} // namespace Governor

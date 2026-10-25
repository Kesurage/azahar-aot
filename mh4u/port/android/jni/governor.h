#pragma once

#include <string>

namespace Core {
class System;
}

namespace App {
struct Config;
}

/// The adaptive resolution governor: while the player asked for the screen's own internal
/// resolution (mh4u-app.ini [Display] resolution = 0) and left it on, it walks the internal
/// resolution down while the game falls behind or the phone runs hot, and back up once neither
/// has been true for a while. The game's speed (and with it a network room) never waits on it:
/// only the picture's own sharpness gives way, a step at a time, and it comes back by itself.
///
/// Threads: Tick runs on the emulation thread (the game loop calls it; it is rate-limited
/// inside, so calling it every iteration is cheap), Stop and Reassert on the same thread or
/// the UI thread, Get from anywhere (a copy under a mutex).
namespace Governor {

/// For the HUD and the log.
struct State {
    bool active = false;    ///< on: the governor holds the internal resolution
    int current = 0;        ///< the resolution it last set, in % of the 3DS's (0: from the screen)
    double thermal = -1.0;  ///< the hottest zone that is ours to heat, in °C (-1: none readable)
    std::string zone;       ///< which zone that was ("" when thermal is -1)
};

/// One step of the game loop. `config` is the settings as the thread that refreshes them holds
/// them (ApplyConfig on this same thread): turning the governor off in the menu is heard at the
/// next poll, and puts back what the settings say.
void Tick(Core::System& system, const App::Config& config);

/// The game stopped (or is about to): forget everything.
void Stop();

/// The current state, for the HUD.
State Get();

/// Applies the current step again, or stands down, as the settings now say they should:
/// called right after settings were applied to the running game (ApplyConfig wrote the
/// settings' resolution over the governor's word).
void Reassert(const App::Config& config);

} // namespace Governor

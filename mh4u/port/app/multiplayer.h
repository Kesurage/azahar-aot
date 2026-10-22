// The app's network room (Azahar's multiplayer, which carries MH4U's local play): join, host and
// leave, before the game starts (main.cpp, from mh4u-app.ini or --join/--host) or while it runs
// (the in-game menu's Multiplayer tab, ui/multiplayer_tab.cpp). Rooms can change while the game
// runs because Azahar's local-wireless service (nwm::UDS) asks the room member for its state on
// every packet; what it took at the start is the console's MAC address, so the app joins with
// that address as its preferred one (and gives the console the room's when the room assigns
// another), as Azahar's Qt frontend does.
//
// Threads: Join, Host and Leave return at once and do their work on a thread of their own;
// what the emulation thread must do afterwards (the console's MAC, saving the settings) waits
// for Tick, which the main loop calls. Status() can be read from any thread.
#pragma once

#include <string>
#include <vector>

namespace Core {
class System;
}

namespace App {

struct Config;

namespace Multiplayer {

/// Who joins: the nickname (empty: the console's user name) and the console's identity,
/// read from its settings on the calling thread.
struct JoinRequest {
    std::string host;
    int port = 24872;
    std::string password;
    std::string label;  ///< the room's name, if known (a lobby's list)
};

struct HostRequest {
    int port = 24872;
    std::string name = "MH4U";
    int slots = 4;
    std::string password;
    bool listed = false;  ///< in the lobby (needs config.lobby and config.token)
};

enum class Phase {
    Off,       ///< not in a room
    Busy,      ///< joining, hosting or leaving (Status::action says which)
    InRoom,
};

struct Member {
    std::string nickname;
    std::string username;  ///< checked by a lobby, if any
    std::string game;      ///< what it plays
    bool you = false;
    /// seconds since local-play traffic last came from it (beacons, game data), -1: none yet
    double heard = -1.0;
};

struct Address {
    std::string ip;
    std::string kind;  ///< invite.h NetworkKind: lan, tailscale, radmin, public, ...
};

struct Status {
    Phase phase = Phase::Off;
    std::string action;          ///< while Busy: "Joining 1.2.3.4:24872..."
    std::string error;           ///< the last thing that failed, for the player ("" none)
    std::string hint;            ///< what to try about it
    // in a room
    std::string room_name;
    std::string host;            ///< the address joined (127.0.0.1 when hosting)
    int port = 0;
    std::string password;        ///< the room's (for the invite)
    unsigned slots = 0;
    bool hosting = false;        ///< this PC runs the room
    bool moderator = false;
    std::string nickname;
    std::vector<Member> members;
    bool joined_in_game = false; ///< joined while the game ran: the player needs to know where to go
    std::string lobby_note;      ///< hosting: listed in the lobby, or why not
    // hosting: where friends reach this PC
    std::vector<Address> addresses;
    std::string public_address;  ///< as the lobby sees this PC ("" unknown)
    bool public_checked = false;
};

/// A line of the room's chat: what players wrote (Azahar's room chat) and who came and went.
struct ChatLine {
    unsigned long long seq = 0;  ///< 1, 2, 3...: the order, and what ChatSince goes by
    std::string nickname;        ///< empty for a system line
    std::string message;
    bool you = false;            ///< written by this player
    bool system = false;         ///< "x joined the room"
};

/// Before anything else, after Network::Init.
void Init(Core::System& system, Config& config);

/// After the game stopped: leaves, closes a hosted room, waits for a job that runs.
void Shutdown();

/// Before the game starts: the room mh4u-app.ini (or the command line) asks for, waited for.
/// False with a message for the player when that failed.
bool StartAtBoot(std::string& error);

/// While the game runs (or before): starts a job, unless one runs (false then).
bool Join(const JoinRequest& request);
bool Host(const HostRequest& request);
/// Leaves the room; `close` also closes a room this PC hosts (otherwise it stays open for the
/// others). The next start is then solo; Rejoin brings the last room back.
bool Leave(bool close);
/// The last room joined or hosted (mh4u-app.ini's last_room), again.
bool Rejoin();
/// A description of the last room ("" none).
std::string LastRoom();

/// The emulation thread, once per main-loop iteration: what jobs left for it.
void Tick();

/// Join this room `seconds` after the game started, through the same path as the menu's Join
/// button (--join-after; for tests).
void JoinLater(const JoinRequest& request, double seconds);

Status GetStatus();

/// Sends `text` to the room (cut to 255 bytes, control characters made spaces). False when not
/// in a room or when there is nothing to send. The room relays a message to every member except
/// its sender, so the sent line is added to the local chat here and shows up in ChatSince.
bool SendChat(const std::string& text);

/// The chat lines newer than `after` (0: all it keeps, the last 100). `newest` gets the number of
/// the last line there is. The chat starts empty each time the player enters or leaves a room.
/// Any thread.
std::vector<ChatLine> ChatSince(unsigned long long after, unsigned long long& newest);

/// The player's name in rooms: mh4u-app.ini's nickname, or the console's user name.
std::string Nickname();

/// Azahar's rule for nicknames: 4 to 20 letters, digits, spaces, '.', '_' or '-'.
bool ValidNickname(const std::string& name);

} // namespace Multiplayer
} // namespace App

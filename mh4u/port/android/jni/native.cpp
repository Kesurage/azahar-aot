// The Android app's native side (org.mh4u.port.Native): runs Azahar's core with the game's
// recompiled code as the CPU, on a Surface from Kotlin. Everything the PC app (port/app) does
// around the core is the same code here where it doesn't depend on SDL: the app's settings
// (config.cpp), rooms (multiplayer.cpp, invite.cpp, launcher/lobby.cpp) and the port's hooks
// and options (src/hooks.cpp). What differs is this file: the window, input, storage, the
// keyboard and the thread the game runs on.
#include <algorithm>
#include <atomic>
#include <cctype>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <queue>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include <android/native_window_jni.h>
#include <jni.h>
#include <json.hpp>

#include "common/android_utils.h"
#include "common/file_util.h"
#include "common/logging/backend.h"
#include "common/logging/filter.h"
#include "common/logging/log.h"
#include "common/scope_exit.h"
#include "common/settings.h"
#include "common/string_util.h"
#include "config.h"
#include "governor.h"
#ifdef MH4U_AOT
#include "arm_aot.h"
#endif
#include "core/cheats/cheats.h"
#include "core/cheats/gateway_cheat.h"
#include "core/core.h"
#include "core/frontend/applets/default_applets.h"
#include "core/hle/service/am/am.h"
#include "core/hle/service/cfg/cfg.h"
#include "core/loader/loader.h"
#include "common/thread.h"
#include "emu_window_android.h"
#include "emu_window_gl.h"
#include "frontend.h"
#include "hooks.h"
#include "input_common/main.h"
#include "input_map.h"
#include "invite.h"
#include "jni_env.h"
#include "keyboard_android.h"
#include "launcher/lobby.h"
#include "multiplayer.h"
#include "network/network.h"
#include "pad_device.h"
#include "video_core/gpu.h"
#include "video_core/renderer_base.h"

namespace App::Input {
// config.cpp's Apply() asks for the input profile; here the pad device builds it (pad_device.cpp)
void BuildProfile(const Config&) {
    Mh4uAndroid::Pad::Init();
}
std::string ControllerDescription() {
    return "controls: the app's pad (on-screen controls and any device Android lists)";
}
} // namespace App::Input

namespace {

using nlohmann::json;
using Mh4uAndroid::EmuWindow;

// ── state shared between the UI thread and the emulation thread ──
std::recursive_mutex surface_mutex;
std::condition_variable_any surface_cv;
ANativeWindow* g_surface = nullptr;
int g_surface_width = 0, g_surface_height = 0;  // its size as the activity reported it
std::unique_ptr<EmuWindow> g_window;

std::atomic<bool> g_stop{true};
std::atomic<bool> g_pause{false};
std::mutex g_run_mutex;  // one game at a time
std::mutex g_pause_mutex;
std::condition_variable g_pause_cv;

std::mutex g_task_mutex;
std::queue<std::function<void()>> g_tasks;  // for the emulation thread, once per loop

App::Config g_config;
std::string g_notice;  // the code library's note for the player (Port::BackendNotice)
std::mutex g_notice_mutex;
std::mutex g_window_mutex;  // g_window lives on the game thread; the UI thread presents with it
bool g_initialised = false;

void Post(std::function<void()> task) {
    std::scoped_lock lock(g_task_mutex);
    g_tasks.push(std::move(task));
}

void RunTasks() {
    std::queue<std::function<void()>> now;
    {
        std::scoped_lock lock(g_task_mutex);
        now.swap(g_tasks);
    }
    for (; !now.empty(); now.pop()) {
        now.front()();
    }
}

std::string Str(JNIEnv* env, jstring s) {
    if (!s) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) {
        env->ReleaseStringUTFChars(s, chars);
    }
    return out;
}

/// A Java string as real UTF-8 (GetStringUTFChars gives "modified" UTF-8, which writes an emoji
/// as two surrogates: fine for a file name, wrong for what goes to other players).
std::string Utf8Str(JNIEnv* env, jstring s) {
    if (!s) {
        return {};
    }
    const jsize n = env->GetStringLength(s);
    const jchar* c = env->GetStringChars(s, nullptr);
    std::string out;
    for (jsize i = 0; c && i < n; ++i) {
        std::uint32_t cp = c[i];
        if (cp >= 0xD800 && cp < 0xDC00 && i + 1 < n && c[i + 1] >= 0xDC00 && c[i + 1] < 0xE000) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (c[i + 1] - 0xDC00);
            ++i;
        } else if (cp >= 0xD800 && cp < 0xE000) {
            cp = 0xFFFD;  // a lone surrogate
        }
        if (cp < 0x80) {
            out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            out += static_cast<char>(0xC0 | cp >> 6);
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += static_cast<char>(0xE0 | cp >> 12);
            out += static_cast<char>(0x80 | (cp >> 6 & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | cp >> 18);
            out += static_cast<char>(0x80 | (cp >> 12 & 0x3F));
            out += static_cast<char>(0x80 | (cp >> 6 & 0x3F));
            out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    if (c) {
        env->ReleaseStringChars(s, c);
    }
    return out;
}

jstring JStr(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

/// A settings file made by an older build gets the new template: it carries the tuned defaults
/// (60 fps, the screen passes off, the renderer, the native resolution), and the player's own
/// values survive. Both the template and the file carry a "# profile:" line; a file whose line
/// differs (or that has none) is re-made: every "key = value" it had replaces the template's
/// same key, and its other settings keep their section (the app's [Android] options, a key an
/// older template listed). Keys in the keep list stay as the new template has them (the
/// profile's intent; the old value was the old profile's default).
std::string MergeProfile(const std::string& old_text, const std::string& new_template,
                         std::initializer_list<const char*> forced = {}) {
    const auto trim = [](const std::string& s) {
        const std::size_t b = s.find_first_not_of(" \t\r");
        if (b == std::string::npos) {
            return std::string{};
        }
        return s.substr(b, s.find_last_not_of(" \t\r") - b + 1);
    };
    const auto is_comment = [&](const std::string& line) {
        const std::size_t hash = line.find('#');
        return hash != std::string::npos &&
               line.substr(0, hash).find_first_not_of(" \t") == std::string::npos;
    };
    const auto is_header = [&](const std::string& line) {
        return line.size() >= 2 && line.front() == '[' && line.back() == ']';
    };

    // the old file: the value of every key in it (a key matches by name: these files have never
    // used one name in two sections), and, in their order, the settings the new template doesn't
    // list - each with the section it was written in
    std::map<std::string, std::string> values;
    std::vector<std::pair<std::string, std::string>> extras;  // (section, "key = value")
    std::string section;
    for (const std::string& raw : Common::SplitString(old_text, '\n')) {
        const std::string line = trim(raw);
        if (line.empty() || is_comment(line)) {
            continue;  // old comments go: the template's own tell the story now
        }
        if (is_header(line)) {
            section = line.substr(1, line.size() - 2);
            continue;
        }
        const std::size_t eq = line.find('=');
        if (eq == std::string::npos || line.substr(0, eq).find_first_not_of(" \t") == std::string::npos) {
            continue;  // no key before the =: not a setting the format knows
        }
        const std::string key = trim(line.substr(0, eq));
        const std::string value = trim(line.substr(eq + 1));
        if (key.empty() || value.empty()) {
            continue;
        }
        values[key] = value;
        extras.push_back({section, key + " = " + value});
    }

    // the template, with the player's value where it lists the key; a key it lists is spoken
    // for, so its extra copy below is skipped
    std::set<std::string> spoken_for;
    const auto forced_key = [&](const std::string& key) {
        return std::any_of(forced.begin(), forced.end(), [&](const char* f) { return f == key; });
    };
    std::string out;
    std::string out_section;  // the section out is inside
    for (const std::string& raw : Common::SplitString(new_template, '\n')) {
        const std::string line = trim(raw);
        if (is_header(line)) {
            // the section we were in ends: its settings that the template doesn't list go now,
            // while the section is still the one they belong to
            for (const auto& [sec, extra] : extras) {
                if (sec == out_section && !spoken_for.count(extra.substr(0, extra.find(" = ")))) {
                    out += extra + "\n";
                    spoken_for.insert(extra.substr(0, extra.find(" = ")));
                }
            }
            out_section = line.substr(1, line.size() - 2);
        }
        const std::size_t eq = line.find('=');
        if (eq != std::string::npos && line.find('#') > eq &&
            line.substr(0, eq).find_first_not_of(" \t") != std::string::npos && !is_comment(line)) {
            const std::string key = trim(line.substr(0, eq));
            const auto it = values.find(key);
            if (it != values.end()) {
                spoken_for.insert(key);
                if (!forced_key(key)) {
                    out += key + " = " + it->second + "\n";
                    continue;
                }
            }
        }
        out += raw + "\n";
    }

    // what no template section claims: the app's [Android] settings, or the whole set when the
    // file has no sections (mh4u.ini). Under the header they were in, so a reader finds them.
    bool wrote_kept_header = false;
    std::string emitted_section = out_section;
    for (const auto& [sec, extra] : extras) {
        const std::string key = extra.substr(0, extra.find(" = "));
        if (spoken_for.count(key)) {
            continue;
        }
        if (!wrote_kept_header) {
            out += "\n# your settings that the template doesn't list, kept:\n";
            wrote_kept_header = true;
        }
        if (!sec.empty() && sec != emitted_section) {
            out += "[" + sec + "]\n";
            emitted_section = sec;
        }
        out += extra + "\n";
    }
    return out;
}

/// Brings one settings file to the current profile (see MergeProfile). A file that is already
/// the current profile stays as it is.
void UpgradeProfile(const std::string& path, const std::string& new_template,
                    std::initializer_list<const char*> forced = {},
                    const char* marker = "# profile: g100-native") {
    std::string old_text;
    if (FileUtil::Exists(path)) {
        FileUtil::ReadFileToString(true, path, old_text);
        if (old_text.find(marker) != std::string::npos) {
            return;
        }
        LOG_INFO(Frontend, "{}: bringing it to the current profile", path);
    } else {
        LOG_INFO(Frontend, "{}: writing the current profile", path);
    }
    FileUtil::WriteStringToFile(true, path, MergeProfile(old_text, new_template, forced));
}

/// What Kotlin's screen options ask for that the PC app has no name for: portrait layouts, the
/// screen gap, the large screen's share, swapped and upright screens. "key=value" lines.
void ApplyScreenExtras(const std::string& text) {
    auto& v = Settings::values;
    std::size_t at = 0;
    while (at < text.size()) {
        const std::size_t end = std::min(text.find('\n', at), text.size());
        const std::string line = text.substr(at, end - at);
        at = end + 1;
        const std::size_t eq = line.find('=');
        if (eq == std::string::npos) {
            continue;
        }
        const std::string key = line.substr(0, eq);
        const std::string value = line.substr(eq + 1);
        if (key == "swap") {
            v.swap_screen = value == "1";
        } else if (key == "upright") {
            v.upright_screen = value == "1";
        } else if (key == "gap") {
            v.screen_gap = std::atoi(value.c_str());
        } else if (key == "large_proportion") {
            v.large_screen_proportion = static_cast<float>(std::atof(value.c_str()));
        } else if (key == "portrait") {
            v.portrait_layout_option =
                value == "original" ? Settings::PortraitLayoutOption::PortraitOriginal
                : value == "custom" ? Settings::PortraitLayoutOption::PortraitCustomLayout
                                    : Settings::PortraitLayoutOption::PortraitTopFullWidth;
        }
    }
}

/// The app's settings (mh4u-app.ini) as Azahar's, then what is Android's own. PC's Apply() does
/// the common part (renderer, filter, clock, sound level, the input profile).
void ApplyConfig(const App::Config& config) {
    App::Apply(config);
#ifdef MH4U_AOT
    Aot::SetSkipIdle(config.aot_skip_idle);
    Aot::SetInlineSvc(config.aot_fewer_unwinds);
#endif
    auto& v = Settings::values;
    // the renderer the player picked ([Display] renderer: vulkan, opengl; software is not
    // offered here: it has no GPU to help it and no window of its own on Android)
    if (config.renderer == "opengl") {
        v.graphics_api = Settings::GraphicsAPI::OpenGL;
    } else {
        v.graphics_api = Settings::GraphicsAPI::Vulkan;
    }
    // which cores the emulation and GPU worker threads run on (0: the OS decides; 1: the big
    // ones, 2: the fastest one only): on a big.LITTLE phone this keeps the game off the little
    // cores, where a frame is missed while the scheduler moves it
    v.cpu_affinity_mode = config.cpu_affinity;
    v.output_type = AudioCore::SinkType::Cubeb;       // Cubeb's Android backend (AAudio)
    v.use_vsync = config.vsync;
    // this Azahar counts the internal resolution in percent (100: native); the app's ini in
    // multiples of the 3DS's, 0 meaning the governor's: it starts at the 3DS's own pixels and
    // climbs while the phone can afford it (governor.cpp) - 0 here too until its first tick,
    // never the window's own scale
    v.resolution_factor =
        config.resolution == 0 ? 100u : static_cast<u32>(config.resolution) * 100;
    // layouts the PC app doesn't have (Azahar's own names)
    if (config.screens == "large") {
        v.layout_option = Settings::LayoutOption::LargeScreen;
    } else if (config.screens == "hybrid") {
        v.layout_option = Settings::LayoutOption::HybridScreen;
    } else if (config.screens == "windows") {
        v.layout_option = Settings::LayoutOption::CustomLayout;  // no second window on a phone
    }
}

// ── the game thread ──
Core::System::ResultStatus Run(const std::string& game, std::string& message) {
    std::scoped_lock run_lock(g_run_mutex);
    auto& system = Core::System::GetInstance();
    Governor::Stop();  // a clean start: no state from the run before

    // A new run starts "not stopped": g_stop begins true, so without this the wait below
    // returns at once, before the surface exists, and the run ends silently.
    g_stop = false;

    // the surface the window draws on: wait for the activity to hand one over
    std::unique_lock<std::recursive_mutex> surface_lock(surface_mutex);
    surface_cv.wait(surface_lock, [] { return g_surface != nullptr || g_stop.load(); });
    if (!g_surface) {
        return Core::System::ResultStatus::ShutdownRequested;
    }

    // the core re-creates its renderer on a save-state load: hold the surface for that
    system.RegisterOnInitCallback([](bool init_start) {
        if (init_start) {
            surface_mutex.lock();
            if (!g_surface) {
                std::unique_lock<std::recursive_mutex> wait(surface_mutex, std::adopt_lock);
                surface_cv.wait(wait, [] { return g_surface != nullptr; });
                wait.release();
            }
        } else {
            surface_mutex.unlock();
        }
    });

    g_config = App::Load();
    ApplyConfig(g_config);

    // the window for the renderer the settings picked: Vulkan off the system driver, or
    // OpenGL (GLES) with an EGL context. A failed EGL start falls back to Vulkan, so the
    // game still runs. On a big.LITTLE phone, keep this thread (the game and the renderer)
    // on the cores that can hold the frame rate.
    Common::SetCurrentThreadAffinity(
        static_cast<Common::CpuAffinityMode>(g_config.cpu_affinity));
    if (Settings::GetWorkingGraphicsAPI() == Settings::GraphicsAPI::OpenGL) {
        auto gl_window =
            std::make_unique<Mh4uAndroid::EmuWindowGL>(g_surface, g_surface_width, g_surface_height);
        if (gl_window->Valid()) {
            g_window = std::move(gl_window);
        } else {
            LOG_WARNING(Frontend, "OpenGL (GLES) did not start; using Vulkan");
            Settings::values.graphics_api = Settings::GraphicsAPI::Vulkan;
            g_window = std::make_unique<Mh4uAndroid::EmuWindow>(g_surface, g_surface_width,
                                                                 g_surface_height);
        }
    } else {
        g_window =
            std::make_unique<Mh4uAndroid::EmuWindow>(g_surface, g_surface_width, g_surface_height);
    }
    // the renderer is built on this thread: with OpenGL, work on the shared pbuffer context
    g_window->MakeCurrent();

    Frontend::RegisterDefaultApplets(system);
    system.RegisterSoftwareKeyboard(std::make_shared<Mh4uAndroid::Keyboard>());
    system.ApplySettings();

    if (std::string error; !App::Multiplayer::StartAtBoot(error)) {
        message = error + " The game starts without the room.";
    }
    LOG_INFO(Frontend, "{}", Port::PrepareBackendFor(system, game));
    {
        std::scoped_lock lock(g_notice_mutex);
        g_notice = Port::BackendNotice();  // the activity shows it once the game is up
    }

    Settings::LogSettings();
    const auto status = system.Load(*g_window, game);
    surface_lock.unlock();
    if (status != Core::System::ResultStatus::Success) {
        message = system.GetStatusDetails();
        std::scoped_lock lock(g_window_mutex);
        g_window.reset();
        return status;
    }

    g_pause = false;
    system.RegisterCoreLoopThreadId();
    SCOPE_EXIT({
        std::scoped_lock lock(surface_mutex);
        std::scoped_lock window_lock(g_window_mutex);
        g_window->DoneCurrent();
        system.Shutdown();
        g_window.reset();
        Governor::Stop();
    });

    while (!g_stop) {
        RunTasks();
        App::Multiplayer::Tick();
        if (g_pause) {
            const float volume = Settings::values.volume.GetValue();
            SCOPE_EXIT({ Settings::values.volume = volume; });
            Settings::values.volume = 0;
            std::unique_lock lock(g_pause_mutex);
            g_pause_cv.wait(lock, [] { return !g_pause || g_stop; });
            continue;
        }
        // the adaptive resolution governor (a no-op between its polls): while the player asked
        // for the app's own resolution it starts at the 3DS's real pixels, climbing a step when
        // the game keeps up and the phone stays cool, giving the step back when either fails.
        // Behind the pause check: a paused game's speed reads as behind, and the thermometer is
        // the only thing it should listen to while idle.
        Governor::Tick(system, g_config);
        const auto result = system.RunLoop();
        if (result == Core::System::ResultStatus::Success) {
            continue;
        }
        if (result == Core::System::ResultStatus::ShutdownRequested) {
            return result;
        }
        message = system.GetStatusDetails();
        return result;
    }
    return Core::System::ResultStatus::Success;
}

json MemberJson(const App::Multiplayer::Member& m) {
    return {{"nickname", m.nickname}, {"username", m.username}, {"game", m.game},
            {"you", m.you},           {"heard", m.heard}};
}

json StatusJson() {
    const auto s = App::Multiplayer::GetStatus();
    json j;
    j["phase"] = s.phase == App::Multiplayer::Phase::Off    ? "off"
                 : s.phase == App::Multiplayer::Phase::Busy ? "busy"
                                                            : "room";
    j["action"] = s.action;
    j["error"] = s.error;
    j["hint"] = s.hint;
    j["room_name"] = s.room_name;
    j["host"] = s.host;
    j["port"] = s.port;
    j["password"] = s.password;
    j["slots"] = s.slots;
    j["hosting"] = s.hosting;
    j["moderator"] = s.moderator;
    j["nickname"] = s.nickname;
    j["joined_in_game"] = s.joined_in_game;
    j["lobby_note"] = s.lobby_note;
    j["public_address"] = s.public_address;
    j["public_checked"] = s.public_checked;
    j["members"] = json::array();
    for (const auto& m : s.members) {
        j["members"].push_back(MemberJson(m));
    }
    j["addresses"] = json::array();
    for (const auto& a : s.addresses) {
        j["addresses"].push_back({{"ip", a.ip}, {"kind", a.kind}});
    }
    j["invite"] = s.phase == App::Multiplayer::Phase::InRoom && s.hosting && !s.addresses.empty()
                      ? App::Invite::Format(s.addresses.front().ip, s.port, s.password)
                      : std::string{};
    return j;
}

} // namespace

#define FN(ret, name, ...) extern "C" JNIEXPORT ret JNICALL Java_org_mh4u_port_Native_##name(JNIEnv* env, jclass, ##__VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    Mh4uAndroid::SetVm(vm);
    JNIEnv* env = nullptr;
    vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    // the core's file access calls static methods of this class (storage root, raw paths)
    jclass storage = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("org/mh4u/port/Storage")));
    AndroidUtils::InitJNI(env, storage);
    Mh4uAndroid::InitKeyboardJni();
    return JNI_VERSION_1_6;
}

/// Once, before anything else. `privateDir` is where the code library is staged (app storage
/// that can execute; the user folder can't).
FN(void, init, jstring private_dir) {
    if (g_initialised) {
        return;
    }
    setenv("MH4U_PRIVATE_DIR", Str(env, private_dir).c_str(), 1);
    Common::Log::Initialize("mh4u_log.txt");
    Common::Log::Start();
    Common::Log::Filter filter;
    filter.ParseFilterString(Settings::values.log_filter.GetValue());
    Common::Log::SetGlobalFilter(filter);
    InputCommon::Init();
    Network::Init();
    g_config = App::Load();
    // Bring the player's settings files to the current profile: the old ones (from the build
    // before, or written by an older template) carry a fixed 2x resolution, 30 fps and every
    // screen pass on. The player's own values survive the upgrade. The app file's template is
    // built from the defaults - not from the file being upgraded - so the profile's own
    // choices (the native resolution, the governor, OpenGL) reach a player whose file predates
    // them; what they set themselves is kept, except the forced keys.
    UpgradeProfile(Port::OptionsPath(), Port::OptionsTemplate());
    UpgradeProfile(App::ConfigPath(), App::Template(App::Defaults()),
                   {"renderer", "resolution", "adaptive_resolution"}, "# app profile: g100-native");
    g_config = App::Load();
    App::Multiplayer::Init(Core::System::GetInstance(), g_config);
    g_initialised = true;
}

FN(void, surfaceChanged, jobject surface, jint width, jint height) {
    std::scoped_lock lock(surface_mutex);
    g_surface_width = width;
    g_surface_height = height;
    if (g_surface) {
        ANativeWindow_release(g_surface);
        g_surface = nullptr;
    }
    g_surface = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    if (!g_surface) {
        return;
    }
    surface_cv.notify_all();
    auto& system = Core::System::GetInstance();
    if (g_window && g_window->OnSurfaceChanged(g_surface, width, height) && system.IsPoweredOn()) {
        system.GPU().Renderer().NotifySurfaceChanged(false);
    }
}

FN(void, surfaceDestroyed) {
    std::scoped_lock lock(surface_mutex);
    if (g_surface) {
        ANativeWindow_release(g_surface);
        g_surface = nullptr;
        if (g_window) {
            g_window->OnSurfaceChanged(nullptr);
        }
    }
}

/// One Choreographer frame on the UI thread: with the OpenGL renderer this presents the frame
/// the emulation thread has drawn (the Vulkan renderer presents on the emu thread itself and
/// needs nothing here). Cheap when there is nothing to do.
FN(void, doFrame) {
    std::scoped_lock lock(g_window_mutex);
    if (g_stop.load() || !g_window) {
        return;
    }
    g_window->TryPresenting();
}

/// Runs the game on the calling thread until it stops. Returns "" on a normal end, else the
/// text for the player (why it didn't start, or what the port wants said). The code library's
/// notice comes back through `notice()` below.
FN(jstring, run, jstring game) {
    std::string message;
    const auto status = Run(Str(env, game), message);
    using Status = Core::System::ResultStatus;
    std::string out;
    switch (status) {
    case Status::Success:
    case Status::ShutdownRequested:
        out = "";
        break;
    case Status::ErrorGetLoader:
    case Status::ErrorLoader_ErrorInvalidFormat:
        out = "This file isn't a 3DS game the app can open. Choose your decrypted Monster Hunter "
              "4 Ultimate (.3ds, .cci or .cxi).";
        break;
    case Status::ErrorLoader_ErrorEncrypted:
        out = "The game file is encrypted. The port needs a decrypted dump of your own cartridge.";
        break;
    case Status::ErrorSystemFiles:
        out = "Some 3DS system files are missing.";
        break;
    default:
        out = message.empty() ? "The game stopped." : message;
    }
    return JStr(env, out);
}

FN(void, stop) {
    if (g_stop.exchange(true)) {
        return;
    }
    g_pause = false;
    g_pause_cv.notify_all();
    surface_cv.notify_all();
}

FN(void, setPaused, jboolean paused) {
    g_pause = paused;
    if (!paused) {
        g_pause_cv.notify_all();
    }
}

FN(jboolean, isRunning) {
    return !g_stop;
}

FN(void, touch, jint x, jint y, jboolean down) {
    if (g_window) {
        g_window->OnTouch(x, y, down);
    }
}

FN(void, touchMove, jint x, jint y) {
    if (g_window) {
        g_window->OnTouchMoved(x, y);
    }
}

/// The two screens' rectangles in window pixels (top, bottom): left, top, right, bottom each;
/// then the window's size. For the touch overlay editor and the on-screen controls.
FN(jfloatArray, layout) {
    jfloatArray out = env->NewFloatArray(10);
    if (!g_window) {
        return out;
    }
    const auto& l = g_window->GetFramebufferLayout();
    const float v[10] = {
        static_cast<float>(l.top_screen_enabled ? l.top_screen.left : 0),
        static_cast<float>(l.top_screen_enabled ? l.top_screen.top : 0),
        static_cast<float>(l.top_screen_enabled ? l.top_screen.right : 0),
        static_cast<float>(l.top_screen_enabled ? l.top_screen.bottom : 0),
        static_cast<float>(l.bottom_screen_enabled ? l.bottom_screen.left : 0),
        static_cast<float>(l.bottom_screen_enabled ? l.bottom_screen.top : 0),
        static_cast<float>(l.bottom_screen_enabled ? l.bottom_screen.right : 0),
        static_cast<float>(l.bottom_screen_enabled ? l.bottom_screen.bottom : 0),
        static_cast<float>(l.width), static_cast<float>(l.height)};
    env->SetFloatArrayRegion(out, 0, 10, v);
    return out;
}

/// Re-reads mh4u-app.ini and applies it to the running game. `extras`: see ApplyScreenExtras.
FN(void, applySettings, jstring extras) {
    const std::string extra_text = Str(env, extras);
    Post([extra_text] {
        g_config = App::Load();
        ApplyConfig(g_config);
        ApplyScreenExtras(extra_text);
        Core::System::GetInstance().ApplySettings();
        // the layout follows the new option at once. (OnSurfaceChanged did nothing here: the
        // surface hadn't changed, so the layout was only worked out again at the next rotation.)
        if (g_window) {
            g_window->Relayout();
        }
        // ApplyConfig wrote the settings' resolution over the governor's word (the player may
        // have turned it off, or picked a resolution): settle that at once, not at its next poll
        Governor::Reassert(g_config);
    });
    // before a game runs there is no emulation thread to take the task: do it here
    if (g_stop) {
        RunTasks();
    }
}

/// A game option of mh4u.ini changed while the game runs (the port's hooks read these live).
FN(jboolean, setGameOption, jstring key, jstring value) {
    const std::string k = Str(env, key), v = Str(env, value);
    Post([k, v] { Port::Hooks::SetOption(k, v); });
    return JNI_TRUE;
}

/// The code library's note for the player ("" when all is well): shown once the game runs.
FN(jstring, notice) {
    std::scoped_lock lock(g_notice_mutex);
    return JStr(env, g_notice);
}

// ── cheats (Azahar's Gateway cheat engine; the file is <user dir>/cheats/<title id>.txt) ──

namespace {

std::mutex g_cheat_mutex;

/// The running game's program id, or 0.
u64 CheatTitleId() {
    u64 id = 0;
    if (!Core::System::GetInstance().IsPoweredOn() ||
        Core::System::GetInstance().GetAppLoader().ReadProgramId(id) != Loader::ResultStatus::Success) {
        return 0;
    }
    return id;
}

void SaveCheats() {
    if (const u64 id = CheatTitleId()) {
        Core::System::GetInstance().CheatEngine().SaveCheatFile(id);
    }
}

/// "" when `code` is Gateway code (lines of 8 hex digits, a space, 8 hex digits), else why not.
std::string CheckCheatCode(const std::string& code) {
    std::size_t at = 0;
    int lines = 0;
    while (at <= code.size()) {
        const std::size_t end = std::min(code.find('\n', at), code.size());
        std::string line = code.substr(at, end - at);
        at = end + 1;
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ' || line.back() == '\t')) line.pop_back();
        std::size_t first = line.find_first_not_of(" \t");
        if (first == std::string::npos) continue;
        line = line.substr(first);
        bool ok = line.size() == 17 && line[8] == ' ';
        for (std::size_t i = 0; ok && i < line.size(); ++i) {
            ok = i == 8 || std::isxdigit(static_cast<unsigned char>(line[i])) != 0;
        }
        if (!ok) {
            return "Not a Gateway code line: \"" + line + "\". Each line is 8 hex digits, a space, 8 hex digits.";
        }
        ++lines;
    }
    return lines ? "" : "The code is empty.";
}

std::string Normalized(std::string code) {
    code.erase(std::remove(code.begin(), code.end(), '\r'), code.end());
    std::string out;
    std::size_t at = 0;
    while (at <= code.size()) {
        const std::size_t end = std::min(code.find('\n', at), code.size());
        std::string line = code.substr(at, end - at);
        at = end + 1;
        const auto a = line.find_first_not_of(" \t");
        if (a == std::string::npos) continue;
        out += line.substr(a, line.find_last_not_of(" \t") - a + 1) + "\n";
    }
    return out;
}

} // namespace

/// JSON [{"i", "name", "comments", "code", "enabled", "builtin"}]: the cheats of the running game
/// (empty before it has started).
FN(jstring, cheatList) {
    std::scoped_lock lock(g_cheat_mutex);
    nlohmann::json list = nlohmann::json::array();
    if (CheatTitleId() != 0) {
        const auto cheats = Core::System::GetInstance().CheatEngine().GetCheats();
        int i = 0;
        for (const auto& c : cheats) {
            list.push_back({{"i", i++}, {"name", c->GetName()}, {"comments", c->GetComments()},
                            {"code", c->GetCode()}, {"enabled", c->IsEnabled()}, {"builtin", c->IsBuiltIn()}});
        }
    }
    return JStr(env, list.dump());
}

FN(jboolean, cheatSetEnabled, jint index, jboolean enabled) {
    std::scoped_lock lock(g_cheat_mutex);
    if (CheatTitleId() == 0) return JNI_FALSE;
    const auto cheats = Core::System::GetInstance().CheatEngine().GetCheats();
    if (index < 0 || static_cast<std::size_t>(index) >= cheats.size()) return JNI_FALSE;
    cheats[static_cast<std::size_t>(index)]->SetEnabled(enabled);
    SaveCheats();
    return JNI_TRUE;
}

/// Adds a cheat, or replaces the one at `index` (-1 adds). "" when done, else what is wrong.
FN(jstring, cheatSet, jint index, jstring name, jstring comments, jstring code) {
    std::scoped_lock lock(g_cheat_mutex);
    if (CheatTitleId() == 0) return JStr(env, "The game has not started yet.");
    std::string n = Str(env, name), text = Normalized(Str(env, code));
    if (n.empty()) return JStr(env, "The cheat needs a name.");
    if (n.find_first_of("[]\n") != std::string::npos) return JStr(env, "The name can't hold [ ] or a new line.");
    if (const std::string why = CheckCheatCode(text); !why.empty()) return JStr(env, why);
    auto& engine = Core::System::GetInstance().CheatEngine();
    auto cheat = std::make_shared<Cheats::GatewayCheat>(n, text, Str(env, comments));
    if (index >= 0) {
        const auto old = engine.GetCheats();
        if (static_cast<std::size_t>(index) >= old.size()) return JStr(env, "That cheat is gone.");
        cheat->SetEnabled(old[static_cast<std::size_t>(index)]->IsEnabled());
        engine.UpdateCheat(static_cast<std::size_t>(index), std::move(cheat));
    } else {
        engine.AddCheat(std::move(cheat));
    }
    SaveCheats();
    return JStr(env, "");
}

FN(jboolean, cheatRemove, jint index) {
    std::scoped_lock lock(g_cheat_mutex);
    if (CheatTitleId() == 0) return JNI_FALSE;
    auto& engine = Core::System::GetInstance().CheatEngine();
    if (index < 0 || static_cast<std::size_t>(index) >= engine.GetCheats().size()) return JNI_FALSE;
    engine.RemoveCheat(static_cast<std::size_t>(index));
    SaveCheats();
    return JNI_TRUE;
}

FN(void, toggleQuestMap) {
    Port::ToggleQuestMap();
}

FN(jstring, optionsPath) {
    return JStr(env, Port::OptionsPath());
}

/// Frames per second the game draws, for the counter; speed in % of real time.
FN(jdoubleArray, perf) {
    jdoubleArray out = env->NewDoubleArray(2);
    auto& system = Core::System::GetInstance();
    if (system.IsPoweredOn()) {
        const auto r = system.GetAndResetPerfStats();
        const double v[2] = {r.game_fps, r.emulation_speed * 100.0};
        env->SetDoubleArrayRegion(out, 0, 2, v);
    }
    return out;
}

/// The adaptive resolution governor's state as JSON: {"active", "current" (the internal
/// resolution it holds, in % of the 3DS's; 0: the screen's own), "thermal" (the hottest zone
/// that is ours to heat, °C; -1: none could be read), "zone"}.
FN(jstring, adaptiveStatus) {
    const auto s = Governor::Get();
    json j = {{"active", s.active},
              {"current", s.current},
              {"thermal", s.thermal},
              {"zone", s.zone}};
    return JStr(env, j.dump());
}

// ── installing the game's update (a .cia) ──
FN(jint, installCia, jstring path) {
    return static_cast<jint>(Service::AM::InstallCIA(Str(env, path)));
}

// ── multiplayer: App::Multiplayer, as the PC app's menu uses it ──
FN(jboolean, roomJoin, jstring host, jint port, jstring password) {
    App::Multiplayer::JoinRequest request;
    request.host = Str(env, host);
    request.port = port;
    request.password = Str(env, password);
    return App::Multiplayer::Join(request);
}

FN(jboolean, roomHost, jint port, jstring name, jint slots, jstring password, jboolean listed) {
    App::Multiplayer::HostRequest request;
    request.port = port;
    request.name = Str(env, name);
    request.slots = slots;
    request.password = Str(env, password);
    request.listed = listed;
    return App::Multiplayer::Host(request);
}

FN(jboolean, roomLeave, jboolean close) {
    return App::Multiplayer::Leave(close);
}

FN(jboolean, roomRejoin) {
    return App::Multiplayer::Rejoin();
}

FN(jstring, roomLast) {
    return JStr(env, App::Multiplayer::LastRoom());
}

FN(jstring, roomStatus) {
    return JStr(env, StatusJson().dump());
}

/// Sends a line to the room's chat. False: not in a room, or nothing to send.
FN(jboolean, chatSend, jstring text) {
    return App::Multiplayer::SendChat(Utf8Str(env, text));
}

/// The room's chat after line `since`, as JSON: {"seq": number of the last line, "lines": [{seq,
/// nick, msg, you, system}]}. ASCII only (other characters as unicode escapes), as NewStringUTF needs.
FN(jstring, chatPoll, jlong since) {
    unsigned long long newest = 0;
    const auto lines = App::Multiplayer::ChatSince(static_cast<unsigned long long>(since), newest);
    json j = {{"seq", newest}, {"lines", json::array()}};
    for (const auto& l : lines) {
        j["lines"].push_back({{"seq", l.seq}, {"nick", l.nickname}, {"msg", l.message}, {"you", l.you}, {"system", l.system}});
    }
    return JStr(env, j.dump(-1, ' ', true, json::error_handler_t::replace));
}

/// "ok|host|port|password|error" for an invite code or an address.
FN(jstring, parseInvite, jstring text) {
    const auto room = App::Invite::Parse(Str(env, text));
    json j = {{"ok", room.ok},     {"error", room.error}, {"host", room.host},
              {"port", room.port}, {"password", room.password}, {"code", room.code}};
    return JStr(env, j.dump());
}

FN(jstring, formatInvite, jstring host, jint port, jstring password) {
    return JStr(env, App::Invite::Format(Str(env, host), port, Str(env, password)));
}

FN(jboolean, validNickname, jstring name) {
    return App::Multiplayer::ValidNickname(Str(env, name));
}

/// A lobby's rooms as JSON (blocks: call off the main thread).
FN(jstring, lobbyRooms, jstring url) {
    const auto list = Launcher::Lobby::Fetch(Str(env, url));
    json j = {{"ok", list.ok}, {"error", list.error}, {"rooms", json::array()}};
    for (const auto& r : list.rooms) {
        j["rooms"].push_back({{"name", r.name},
                              {"description", r.description},
                              {"owner", r.owner},
                              {"address", r.address},
                              {"port", r.port},
                              {"max_players", r.max_players},
                              {"password", r.password},
                              {"players", r.players},
                              {"player_games", r.player_games},
                              {"game", r.game},
                              {"mh4u", r.mh4u}});
    }
    return JStr(env, j.dump());
}

// ── the combo engine's text form (input bindings), kept native so Kotlin stays thin ──
// (jni_input.cpp: Java_org_mh4u_port_MH4UInput_*)

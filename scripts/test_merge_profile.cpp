// A standalone harness for the Android app's MergeProfile (port/android/jni/native.cpp):
// compiles and runs without the emulator - copy the function's body when it changes.
//   g++ -std=c++17 -o /tmp/merge_test scripts/test_merge_profile.cpp && /tmp/merge_test
#include <algorithm>
#include <initializer_list>
#include <iostream>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

// the one piece of Citra/Azahar the real function uses
namespace Common {
static std::vector<std::string> SplitString(const std::string& str, char delim) {
    std::vector<std::string> out;
    std::istringstream in(str);
    std::string piece;
    while (std::getline(in, piece, delim)) {
        out.push_back(piece);
    }
    return out;
}
} // namespace Common

// ── begin the function under test (kept identical to native.cpp) ──
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

    std::map<std::string, std::string> values;
    std::vector<std::pair<std::string, std::string>> extras;
    std::string section;
    for (const std::string& raw : Common::SplitString(old_text, '\n')) {
        const std::string line = trim(raw);
        if (line.empty() || is_comment(line)) {
            continue;
        }
        if (is_header(line)) {
            section = line.substr(1, line.size() - 2);
            continue;
        }
        const std::size_t eq = line.find('=');
        if (eq == std::string::npos || line.substr(0, eq).find_first_not_of(" \t") == std::string::npos) {
            continue;
        }
        const std::string key = trim(line.substr(0, eq));
        const std::string value = trim(line.substr(eq + 1));
        if (key.empty() || value.empty()) {
            continue;
        }
        values[key] = value;
        extras.push_back({section, key + " = " + value});
    }

    std::set<std::string> spoken_for;
    const auto forced_key = [&](const std::string& key) {
        return std::any_of(forced.begin(), forced.end(), [&](const char* f) { return f == key; });
    };
    std::string out;
    std::string out_section;
    for (const std::string& raw : Common::SplitString(new_template, '\n')) {
        const std::string line = trim(raw);
        if (is_header(line)) {
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
// ── end of the function under test ──

// ── checks ──
static int failures = 0;

std::string ValueOf(const std::string& text, const std::string& section, const std::string& key) {
    std::string current;
    const auto lines = Common::SplitString(text, '\n');
    for (const std::string& raw : lines) {
        const std::size_t b = raw.find_first_not_of(" \t\r");
        if (b == std::string::npos) continue;
        if (raw[b] == '[' && raw.back() == ']') {
            current = raw.substr(b + 1, raw.find_last_of(']') - b - 1);
            continue;
        }
        const std::size_t eq = raw.find('=');
        if (eq == std::string::npos || raw.substr(0, eq).find_first_not_of(" \t") == std::string::npos)
            continue;
        std::string k = raw.substr(0, eq), v = raw.substr(eq + 1);
        k.erase(k.find_last_not_of(" \t") + 1);
        v.erase(0, v.find_first_not_of(" \t"));
        v.erase(v.find_last_not_of(" \t\r") + 1);
        if (current == section && k == key) {
            return v;
        }
    }
    return {};
}

void Check(const std::string& what, const std::string& got, const std::string& want) {
    const bool ok = got == want;
    std::cout << (ok ? "  ok  " : "  FAIL") << " " << what << ": " << (got.empty() && !want.empty() ? "(empty)" : "'" + got + "'")
              << (ok ? "" : " want '" + want + "'") << "\n";
    if (!ok) {
        ++failures;
    }
}

int main() {
    // the new template, as the app writes it (only the parts the checks need)
    const std::string templ =
        "# app profile: g100-native\n"
        "\n"
        "[Game]\n"
        "game = \n"
        "cpu_clock = 100\n"
        "cpu_affinity = 1\n"
        "\n"
        "[Display]\n"
        "renderer = opengl\n"
        "resolution = 0\n"
        "adaptive_resolution = on\n"
        "vsync = on\n"
        "custom_textures = off\n"
        "\n"
        "[Multiplayer]\n"
        "nickname = \n"
        "join = \n"
        "\n"
        "[Mods]\n"
        "catalog_url = https://example.invalid/catalog.json\n";

    // 1. a player on the build before: vulkan, 2x, their game, their room, [Android] options
    std::cout << "upgrading a pre-native file:\n";
    const std::string old1 =
        "# app profile: g100\n"
        "[Game]\n"
        "game = /storage/emulated/0/mh4u.3ds\n"
        "cpu_clock = 100\n"
        "cpu_affinity = 1\n"
        "\n"
        "[Display]\n"
        "renderer = vulkan\n"
        "resolution = 2\n"
        "vsync = on\n"
        "custom_textures = off\n"
        "\n"
        "[Multiplayer]\n"
        "nickname = Hunter\n"
        "join = 192.168.1.4\n"
        "\n"
        "[Android]\n"
        "overlay = on\n"
        "portrait = top\n"
        "gap = 4\n"
        "fps_counter = on\n";
    const std::string out1 = MergeProfile(old1, templ, {"renderer", "resolution", "adaptive_resolution"});
    Check("game kept", ValueOf(out1, "Game", "game"), "/storage/emulated/0/mh4u.3ds");
    Check("renderer forced to opengl", ValueOf(out1, "Display", "renderer"), "opengl");
    Check("resolution forced to 0", ValueOf(out1, "Display", "resolution"), "0");
    Check("adaptive forced on", ValueOf(out1, "Display", "adaptive_resolution"), "on");
    Check("vsync kept", ValueOf(out1, "Display", "vsync"), "on");
    Check("nickname kept", ValueOf(out1, "Multiplayer", "nickname"), "Hunter");
    Check("join kept", ValueOf(out1, "Multiplayer", "join"), "192.168.1.4");
    // the app's own section: intact and findable where the app looks for it
    Check("[Android] overlay kept in section", ValueOf(out1, "Android", "overlay"), "on");
    Check("[Android] portrait kept", ValueOf(out1, "Android", "portrait"), "top");
    Check("[Android] gap kept", ValueOf(out1, "Android", "gap"), "4");
    Check("[Android] fps_counter kept", ValueOf(out1, "Android", "fps_counter"), "on");
    // and nothing leaked into the sections it doesn't belong to
    Check("overlay not in [Mods]", ValueOf(out1, "Mods", "overlay"), "");
    Check("no second [Display] section", static_cast<int>(std::count(out1.begin(), out1.end(), '[') -
        std::count_if(out1.begin(), out1.end(), [](char c) { return c == '['; }) + 1) >= 0 ? "unique" : "dup", "unique");
    std::cout << out1 << "\n";

    // 2. a fresh install: no file at all
    std::cout << "fresh install:\n";
    const std::string out2 = MergeProfile("", templ, {"renderer", "resolution", "adaptive_resolution"});
    Check("template as-is", out2, templ);

    // 3. the game's options file (no sections)
    std::cout << "the game's options file:\n";
    const std::string game_templ =
        "# profile: g100-native\n"
        "fps = 60\n"
        "widescreen = 16:9\n"
        "dither = off\n";
    const std::string old3 =
        "# profile: g100\n"
        "fps = 60\n"
        "widescreen = 16:9\n"
        "dither = off\n"
        "touch_overlay = top-left 40 90\n";
    const std::string out3 = MergeProfile(old3, game_templ, {"fps"});
    Check("fps kept as the template has it", ValueOf(out3, "", "fps"), "60");
    Check("touch_overlay kept", ValueOf(out3, "", "touch_overlay"), "top-left 40 90");
    Check("no section headers crept in", out3.find('[') == std::string::npos ? "none" : "found", "none");

    // 4. an old file whose key the new template dropped (an extra [Display] key)
    std::cout << "an old key the template no longer lists:\n";
    const std::string old4 =
        "# app profile: g100\n"
        "[Display]\n"
        "renderer = vulkan\n"
        "resolution = 2\n"
        "vsync = on\n"
        "frame_limit_special = 99\n";
    const std::string out4 = MergeProfile(old4, templ, {"renderer", "resolution", "adaptive_resolution"});
    Check("the dropped key stays in its section", ValueOf(out4, "Display", "frame_limit_special"), "99");

    std::cout << (failures ? "\nFAILURES: " + std::to_string(failures) : "\nall merge tests pass") << "\n";
    return failures ? 1 : 0;
}

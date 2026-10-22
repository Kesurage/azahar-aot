// Copyright 2014-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#include <algorithm>
#include <cctype>
#include <string_view>
#include <vector>
#include <cmath>
#include <iomanip>
#include <memory>
#include <ranges>
#include <sstream>
#include <unordered_map>
#include <INIReader.h>
#include <type_traits>
#include <boost/hana/string.hpp>
#include <fmt/format.h>
#include "common/file_util.h"
#include "common/logging/backend.h"
#include "common/logging/log.h"
#include "common/param_package.h"
#include "common/setting_keys.h"
#include "common/settings.h"
#include "core/core.h"
#include "core/hle/service/cfg/cfg.h"
#include "core/hle/service/service.h"
#include "input_common/main.h"
#include "input_common/udp/client.h"
#include "jni/camera/ndk_camera.h"
#include "jni/config.h"
#include "jni/default_ini.h"
#include "jni/input_manager.h"

namespace {

std::string ToLower(std::string text) {
    std::ranges::transform(text, text.begin(),
                           [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    return text;
}

std::string Trim(const std::string& text) {
    const auto first = text.find_first_not_of(" \t\r");
    if (first == std::string::npos) {
        return {};
    }
    const auto last = text.find_last_not_of(" \t\r");
    return text.substr(first, last - first + 1);
}

struct PerGameOverride {
    std::string section;
    std::string key;
    std::string value;
    bool applied = false;
};

/// Reads the settings flagged with `<key>\use_global=false` from a per application config file.
std::vector<PerGameOverride> ReadPerGameOverrides(const std::string& text) {
    constexpr std::string_view use_global_suffix = "\\use_global";
    std::vector<PerGameOverride> result;
    std::unordered_map<std::string, std::pair<std::string, std::string>> values; // id -> section,v
    std::vector<std::pair<std::string, std::string>> flagged;                     // section, key

    std::istringstream stream(text);
    std::string line;
    std::string section;
    while (std::getline(stream, line)) {
        line = Trim(line);
        if (line.empty() || line[0] == ';' || line[0] == '#') {
            continue;
        }
        if (line.front() == '[' && line.back() == ']') {
            section = line.substr(1, line.size() - 2);
            continue;
        }
        const auto eq = line.find('=');
        if (section.empty() || eq == std::string::npos) {
            continue;
        }
        const std::string key = Trim(line.substr(0, eq));
        const std::string value = Trim(line.substr(eq + 1));
        if (key.ends_with(use_global_suffix)) {
            if (ToLower(value) == "false" || value == "0") {
                flagged.emplace_back(section, key.substr(0, key.size() - use_global_suffix.size()));
            }
        } else {
            values[ToLower(section) + "\n" + ToLower(key)] = {section, value};
        }
    }
    for (const auto& [flag_section, key] : flagged) {
        const auto it = values.find(ToLower(flag_section) + "\n" + ToLower(key));
        if (it != values.end()) {
            result.push_back({flag_section, key, it->second.second});
        }
    }
    return result;
}

/**
 * Produces the global ini text with the per application overrides folded in. Everything is
 * matched by key name only (the frontends don't agree on the section of every key), and keys
 * the global file doesn't have yet are appended under the section the override names.
 */
std::string MergePerGameOverrides(const std::string& global_text,
                                  std::vector<PerGameOverride> overrides) {
    std::string merged;
    merged.reserve(global_text.size() + 256);

    std::istringstream stream(global_text);
    std::string line;
    while (std::getline(stream, line)) {
        const auto eq = line.find('=');
        const std::string trimmed = Trim(line);
        if (eq != std::string::npos && !trimmed.empty() && trimmed[0] != '[' &&
            trimmed[0] != ';' && trimmed[0] != '#') {
            const std::string key = ToLower(Trim(line.substr(0, eq)));
            auto it = std::ranges::find_if(overrides, [&](const PerGameOverride& entry) {
                return !entry.applied && ToLower(entry.key) == key;
            });
            if (it != overrides.end()) {
                it->applied = true;
                merged += line.substr(0, eq) + "=" + it->value + "\n";
                continue;
            }
        }
        merged += line + "\n";
    }

    for (const auto& entry : overrides) {
        if (!entry.applied) {
            merged += "\n[" + entry.section + "]\n" + entry.key + "=" + entry.value + "\n";
        }
    }
    return merged;
}

} // namespace

Config::Config(u64 program_id) {
    // TODO: Don't hardcode the path; let the frontend decide where to put the config files.
    android_config_loc = FileUtil::GetUserPath(FileUtil::UserPath::ConfigDir) + "config.ini";
    std::string ini_buffer;
    FileUtil::ReadFileToString(true, android_config_loc, ini_buffer);

    // Per application overrides (config/custom/<title id>.ini) are folded into the text that is
    // parsed. The merged text is never written back, the global file stays untouched.
    if (program_id != 0 && !ini_buffer.empty()) {
        const std::string custom_path = GetPerGameConfigPath(program_id);
        std::string custom_text;
        if (FileUtil::Exists(custom_path) &&
            FileUtil::ReadFileToString(true, custom_path, custom_text) != 0) {
            auto overrides = ReadPerGameOverrides(custom_text);
            if (!overrides.empty()) {
                LOG_INFO(Config, "Applying {} per application settings from {}", overrides.size(),
                         custom_path);
                ini_buffer = MergePerGameOverrides(ini_buffer, std::move(overrides));
            }
        }
    }

    if (!ini_buffer.empty()) {
        android_config = std::make_unique<INIReader>(ini_buffer.c_str(), ini_buffer.size());
    }

    Reload();
}

Config::~Config() = default;

bool Config::LoadINI(const std::string& default_contents, bool retry) {
    const std::string& location = this->android_config_loc;
    if (android_config == nullptr || android_config->ParseError() < 0) {
        if (retry) {
            LOG_WARNING(Config, "Failed to load {}. Creating file from defaults...", location);
            FileUtil::CreateFullPath(location);
            FileUtil::WriteStringToFile(true, location, default_contents);
            std::string ini_buffer;
            FileUtil::ReadFileToString(true, location, ini_buffer);
            android_config =
                std::make_unique<INIReader>(ini_buffer.c_str(), ini_buffer.size()); // Reopen file

            return LoadINI(default_contents, false);
        }
        LOG_ERROR(Config, "Failed.");
        return false;
    }
    LOG_INFO(Config, "Successfully loaded {}", location);
    return true;
}

static const std::array<int, Settings::NativeButton::NumButtons> default_buttons = {
    InputManager::N3DS_BUTTON_A,     InputManager::N3DS_BUTTON_B,
    InputManager::N3DS_BUTTON_X,     InputManager::N3DS_BUTTON_Y,
    InputManager::N3DS_DPAD_UP,      InputManager::N3DS_DPAD_DOWN,
    InputManager::N3DS_DPAD_LEFT,    InputManager::N3DS_DPAD_RIGHT,
    InputManager::N3DS_TRIGGER_L,    InputManager::N3DS_TRIGGER_R,
    InputManager::N3DS_BUTTON_START, InputManager::N3DS_BUTTON_SELECT,
    InputManager::N3DS_BUTTON_DEBUG, InputManager::N3DS_BUTTON_GPIO14,
    InputManager::N3DS_BUTTON_ZL,    InputManager::N3DS_BUTTON_ZR,
    InputManager::N3DS_BUTTON_HOME,
};

static const std::array<int, Settings::NativeAnalog::NumAnalogs> default_analogs{{
    InputManager::N3DS_CIRCLEPAD,
    InputManager::N3DS_STICK_C,
}};

template <>
void Config::ReadSetting(const std::string& group, Settings::Setting<std::string>& setting) {
    std::string setting_value =
        android_config->Get(group, setting.GetLabel(), setting.GetDefault());
    if (setting_value.empty()) {
        setting_value = setting.GetDefault();
    }
    setting = std::move(setting_value);
}

template <>
void Config::ReadSetting(const std::string& group, Settings::Setting<bool>& setting) {
    setting = android_config->GetBoolean(group, setting.GetLabel(), setting.GetDefault());
}

template <typename Type, bool ranged>
void Config::ReadSetting(const std::string& group, Settings::Setting<Type, ranged>& setting) {
    if constexpr (std::is_floating_point_v<Type>) {
        setting = android_config->GetReal(group, setting.GetLabel(), setting.GetDefault());
    } else {
        setting = static_cast<Type>(android_config->GetInteger(
            group, setting.GetLabel(), static_cast<long>(setting.GetDefault())));
    }
}

void Config::ReadValues() {
    // Controls
    for (int i = 0; i < Settings::NativeButton::NumButtons; ++i) {
        std::string default_param = InputManager::GenerateButtonParamPackage(default_buttons[i]);
        Settings::values.current_input_profile.buttons[i] = android_config->GetString(
            "Controls", Settings::NativeButton::mapping[i], default_param);
        if (Settings::values.current_input_profile.buttons[i].empty())
            Settings::values.current_input_profile.buttons[i] = default_param;
    }

    for (int i = 0; i < Settings::NativeAnalog::NumAnalogs; ++i) {
        std::string default_param = InputManager::GenerateAnalogParamPackage(default_analogs[i]);
        Settings::values.current_input_profile.analogs[i] = android_config->GetString(
            "Controls", Settings::NativeAnalog::mapping[i], default_param);
        if (Settings::values.current_input_profile.analogs[i].empty())
            Settings::values.current_input_profile.analogs[i] = default_param;
    }

    Settings::values.current_input_profile.motion_device = android_config->GetString(
        "Controls", "motion_device",
        "engine:motion_emu,update_period:100,sensitivity:0.01,tilt_clamp:90.0");
    Settings::values.current_input_profile.touch_device =
        android_config->GetString("Controls", "touch_device", "engine:emu_window");
    Settings::values.current_input_profile.udp_input_address = android_config->GetString(
        "Controls", "udp_input_address", InputCommon::CemuhookUDP::DEFAULT_ADDR);
    Settings::values.current_input_profile.udp_input_port =
        static_cast<u16>(android_config->GetInteger("Controls", "udp_input_port",
                                                    InputCommon::CemuhookUDP::DEFAULT_PORT));

    ReadSetting("Controls", Settings::values.use_artic_base_controller);

    // Core
    ReadSetting("Core", Settings::values.use_cpu_jit);
    ReadSetting("Core", Settings::values.use_fastinterp);
    ReadSetting("Core", Settings::values.cpu_clock_percentage);
    ReadSetting("Core", Settings::values.reduce_downcount_slice);
    ReadSetting("Core", Settings::values.priority_boost_starved_threads);
    ReadSetting("Core", Settings::values.use_fastmem);
    ReadSetting("Core", Settings::values.cpu_affinity_mode);

    // Renderer
    Settings::values.use_gles = android_config->GetBoolean("Renderer", "use_gles", true);
    Settings::values.shaders_accurate_mul =
        android_config->GetBoolean("Renderer", "shaders_accurate_mul", false);
    ReadSetting("Renderer", Settings::values.graphics_api);
    ReadSetting("Renderer", Settings::values.async_presentation);
    ReadSetting("Renderer", Settings::values.async_shader_compilation);
    ReadSetting("Renderer", Settings::values.spirv_shader_gen);
    ReadSetting("Renderer", Settings::values.disable_spirv_optimizer);
    ReadSetting("Renderer", Settings::values.use_hw_shader);
    ReadSetting("Renderer", Settings::values.use_shader_jit);
    ReadSetting("Renderer", Settings::values.resolution_factor);
    {
        const u32 rf = Settings::values.resolution_factor.GetValue();
        if (rf >= 1 && rf <= 18) {
            Settings::values.resolution_factor.SetValue(rf * 100);
        }
    }
    ReadSetting("Renderer", Settings::values.use_disk_shader_cache);
    ReadSetting("Renderer", Settings::values.use_vsync);
    ReadSetting("Renderer", Settings::values.use_skip_duplicate_frames);
    ReadSetting("Renderer", Settings::values.texture_filter);
    ReadSetting("Renderer", Settings::values.texture_sampling);
    ReadSetting("Renderer", Settings::values.turbo_limit);
    // Workaround to map Android setting for enabling the frame limiter to the format Citra expects
    if (android_config->GetBoolean("Renderer", "use_frame_limit", true)) {
        ReadSetting("Renderer", Settings::values.frame_limit);
    } else {
        Settings::values.frame_limit = 0;
    }

    ReadSetting("Renderer", Settings::values.render_3d);
    ReadSetting("Renderer", Settings::values.factor_3d);
    std::string default_shader = "None (builtin)";
    if (Settings::values.render_3d.GetValue() == Settings::StereoRenderOption::Anaglyph)
        default_shader = "Dubois (builtin)";
    else if (Settings::values.render_3d.GetValue() == Settings::StereoRenderOption::Interlaced)
        default_shader = "Horizontal (builtin)";
    Settings::values.pp_shader_name =
        android_config->GetString("Renderer", "pp_shader_name", default_shader);
    ReadSetting("Renderer", Settings::values.filter_mode);
    ReadSetting("Renderer", Settings::values.use_integer_scaling);

    ReadSetting("Renderer", Settings::values.bg_red);
    ReadSetting("Renderer", Settings::values.bg_green);
    ReadSetting("Renderer", Settings::values.bg_blue);
    ReadSetting("Renderer", Settings::values.custom_second_layer_opacity);
    ReadSetting("Renderer", Settings::values.delay_game_render_thread_us);
    ReadSetting("Renderer", Settings::values.simulate_3ds_gpu_timings);
    ReadSetting("Renderer", Settings::values.disable_right_eye_render);
    ReadSetting("Renderer", Settings::values.force_hw_vertex_shaders);
    ReadSetting("Renderer", Settings::values.disable_surface_texture_copy);
    ReadSetting("Renderer", Settings::values.disable_flush_cpu_write);
    ReadSetting("Renderer", Settings::values.swap_eyes_3d);
    ReadSetting("Renderer", Settings::values.render_3d_which_display);
    // Layout
    // Somewhat inelegant solution to ensure layout value is between 0 and 5 on read
    // since older config files may have other values
    int layoutInt = (int)android_config->GetInteger(
        "Layout", "layout_option", static_cast<int>(Settings::LayoutOption::LargeScreen));
    if (layoutInt < 0 || layoutInt > 5) {
        layoutInt = static_cast<int>(Settings::LayoutOption::LargeScreen);
    }
    Settings::values.layout_option = static_cast<Settings::LayoutOption>(layoutInt);
    Settings::values.screen_gap =
        static_cast<int>(android_config->GetReal("Layout", "screen_gap", 0));
    Settings::values.large_screen_proportion =
        static_cast<float>(android_config->GetReal("Layout", "large_screen_proportion", 2.25));
    Settings::values.small_screen_position = static_cast<Settings::SmallScreenPosition>(
        android_config->GetInteger("Layout", "small_screen_position",
                                   static_cast<int>(Settings::SmallScreenPosition::TopRight)));
    ReadSetting("Layout", Settings::values.screen_gap);
    ReadSetting("Layout", Settings::values.custom_top_x);
    ReadSetting("Layout", Settings::values.custom_top_y);
    ReadSetting("Layout", Settings::values.custom_top_width);
    ReadSetting("Layout", Settings::values.custom_top_height);
    ReadSetting("Layout", Settings::values.custom_bottom_x);
    ReadSetting("Layout", Settings::values.custom_bottom_y);
    ReadSetting("Layout", Settings::values.custom_bottom_width);
    ReadSetting("Layout", Settings::values.aspect_ratio);
    ReadSetting("Layout", Settings::values.custom_bottom_height);
    ReadSetting("Layout", Settings::values.cardboard_screen_size);
    ReadSetting("Layout", Settings::values.cardboard_x_shift);
    ReadSetting("Layout", Settings::values.cardboard_y_shift);
    ReadSetting("Layout", Settings::values.upright_screen);

    Settings::values.portrait_layout_option =
        static_cast<Settings::PortraitLayoutOption>(android_config->GetInteger(
            "Layout", "portrait_layout_option",
            static_cast<int>(Settings::PortraitLayoutOption::PortraitTopFullWidth)));
    Settings::values.secondary_display_layout = static_cast<Settings::SecondaryDisplayLayout>(
        android_config->GetInteger("Layout", Settings::HKeys::secondary_display_layout.c_str(),
                                   static_cast<int>(Settings::SecondaryDisplayLayout::None)));
    ReadSetting("Layout", Settings::values.custom_portrait_top_x);
    ReadSetting("Layout", Settings::values.custom_portrait_top_y);
    ReadSetting("Layout", Settings::values.custom_portrait_top_width);
    ReadSetting("Layout", Settings::values.custom_portrait_top_height);
    ReadSetting("Layout", Settings::values.custom_portrait_bottom_x);
    ReadSetting("Layout", Settings::values.custom_portrait_bottom_y);
    ReadSetting("Layout", Settings::values.custom_portrait_bottom_width);
    ReadSetting("Layout", Settings::values.custom_portrait_bottom_height);

    // Storage
    ReadSetting("Storage", Settings::values.compress_cia_installs);
    ReadSetting("Storage", Settings::values.async_fs_operations);

    // Utility
    ReadSetting("Utility", Settings::values.dump_textures);
    ReadSetting("Utility", Settings::values.custom_textures);
    ReadSetting("Utility", Settings::values.preload_textures);
    ReadSetting("Utility", Settings::values.async_custom_loading);

    // Audio
    ReadSetting("Audio", Settings::values.audio_emulation);
    ReadSetting("Audio", Settings::values.enable_audio_stretching);
    ReadSetting("Audio", Settings::values.enable_realtime_audio);
    ReadSetting("Audio", Settings::values.simulate_headphones_plugged);
    ReadSetting("Audio", Settings::values.volume);
    ReadSetting("Audio", Settings::values.output_type);
    ReadSetting("Audio", Settings::values.output_device);
    ReadSetting("Audio", Settings::values.input_type);
    ReadSetting("Audio", Settings::values.input_device);

    // Data Storage
    ReadSetting("Data Storage", Settings::values.use_virtual_sd);

    // System
    ReadSetting("System", Settings::values.is_new_3ds);
    ReadSetting("System", Settings::values.lle_applets);
    ReadSetting("System", Settings::values.enable_required_online_lle_modules);
    ReadSetting("System", Settings::values.region_value);
    ReadSetting("System", Settings::values.init_clock);
    {
        std::string time =
            android_config->GetString("System", Settings::HKeys::init_time.c_str(), "946681277");
        try {
            Settings::values.init_time = std::stoll(time);
        } catch (...) {
        }
    }
    ReadSetting("System", Settings::values.init_ticks_type);
    ReadSetting("System", Settings::values.init_ticks_override);
    ReadSetting("System", Settings::values.plugin_loader_enabled);
    ReadSetting("System", Settings::values.allow_plugin_loader);
    ReadSetting("System", Settings::values.steps_per_hour);
    ReadSetting("System", Settings::values.apply_region_free_patch);

    // Camera
    using namespace Service::CAM;
    Settings::values.camera_name[OuterRightCamera] = android_config->GetString(
        "Camera", Settings::HKeys::camera_outer_right_name.c_str(), "ndk");
    Settings::values.camera_config[OuterRightCamera] =
        android_config->GetString("Camera", Settings::HKeys::camera_outer_right_config.c_str(),
                                  std::string{Camera::NDK::BackCameraPlaceholder});
    Settings::values.camera_flip[OuterRightCamera] =
        android_config->GetInteger("Camera", Settings::HKeys::camera_outer_right_flip.c_str(), 0);
    Settings::values.camera_name[InnerCamera] =
        android_config->GetString("Camera", Settings::HKeys::camera_inner_name.c_str(), "ndk");
    Settings::values.camera_config[InnerCamera] =
        android_config->GetString("Camera", Settings::HKeys::camera_inner_config.c_str(),
                                  std::string{Camera::NDK::FrontCameraPlaceholder});
    Settings::values.camera_flip[InnerCamera] =
        android_config->GetInteger("Camera", Settings::HKeys::camera_inner_flip.c_str(), 0);
    Settings::values.camera_name[OuterLeftCamera] =
        android_config->GetString("Camera", Settings::HKeys::camera_outer_left_name.c_str(), "ndk");
    Settings::values.camera_config[OuterLeftCamera] =
        android_config->GetString("Camera", Settings::HKeys::camera_outer_left_config.c_str(),
                                  std::string{Camera::NDK::BackCameraPlaceholder});
    Settings::values.camera_flip[OuterLeftCamera] =
        android_config->GetInteger("Camera", Settings::HKeys::camera_outer_left_flip.c_str(), 0);

    // Miscellaneous
    ReadSetting("Miscellaneous", Settings::values.log_filter);
    ReadSetting("Miscellaneous", Settings::values.log_regex_filter);

    // Apply the log_filter setting as the logger has already been initialized
    // and doesn't pick up the filter on its own.
    Common::Log::Filter filter;
    filter.ParseFilterString(Settings::values.log_filter.GetValue());
    Common::Log::SetGlobalFilter(filter);
    Common::Log::SetRegexFilter(Settings::values.log_regex_filter.GetValue());

    // Debugging
    Settings::values.record_frame_times =
        android_config->GetBoolean("Debugging", Settings::HKeys::record_frame_times.c_str(), false);
    ReadSetting("Debugging", Settings::values.renderer_debug);
    ReadSetting("Debugging", Settings::values.pica_debugging);
    ReadSetting("Debugging", Settings::values.use_gdbstub);
    ReadSetting("Debugging", Settings::values.gdbstub_port);
    ReadSetting("Debugging", Settings::values.instant_debug_log);
    ReadSetting("Debugging", Settings::values.enable_rpc_server);
    ReadSetting("Debugging", Settings::values.toggle_unique_data_console_type);
    ReadSetting("Debugging", Settings::values.enable_exception_handler);

    for (const auto& service_module : Service::service_module_map) {
        bool use_lle =
            android_config->GetBoolean("Debugging", "LLE\\" + service_module.name, false);
        Settings::values.lle_modules.emplace(service_module.name, use_lle);
    }

    // Web Service
    ReadSetting("WebService", Settings::values.web_api_url);
    ReadSetting("WebService", Settings::values.network_token);
}

void Config::Reload() {
    for (auto key = Settings::Keys::keys_array.begin(); key != Settings::Keys::keys_array.end();
         ++key) {
        const auto key_declaration_string = std::string(*key) + " =";
        if ((std::ranges::find(DefaultINI::android_config_omitted_keys, std::string_view(*key)) ==
             std::end(DefaultINI::android_config_omitted_keys)) &&
            (std::string_view(DefaultINI::android_config_default_file_content)
                 .find(key_declaration_string) == std::string_view::npos)) {
            ASSERT_MSG(false,
                       "Validation of default config content (jni/default_ini.h) failed: Missing "
                       "declaration for key '{}'",
                       *key);
        }
    }
    LoadINI(DefaultINI::android_config_default_file_content);
    // A previous per-game config may have left settings in their "custom" state. Make sure every
    // switchable setting is back to global, otherwise the assignments in ReadValues() would end up
    // in the custom slot instead of the global one.
    Settings::RestoreGlobalState(false);
    ReadValues();
}

std::string Config::GetPerGameConfigPath(u64 program_id) {
    return fmt::format("{}custom/{:016X}.ini", FileUtil::GetUserPath(FileUtil::UserPath::ConfigDir),
                       program_id);
}

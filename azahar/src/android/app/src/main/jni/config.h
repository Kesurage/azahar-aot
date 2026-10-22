// Copyright 2014-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#pragma once

#include <memory>
#include <string>
#include "common/common_types.h"
#include "common/settings.h"

class INIReader;

class Config {
private:
    std::unique_ptr<INIReader> android_config;
    std::string android_config_loc;

    bool LoadINI(const std::string& default_contents = "", bool retry = true);
    void ReadValues();

public:
    /// @param program_id when not 0, the overrides of config/custom/<title id>.ini are applied
    explicit Config(u64 program_id = 0);
    ~Config();

    void Reload();

    /**
     * Path of the per-application config file for a title:
     * <user dir>/config/custom/<16 digit upper case title id>.ini
     * (the same location and naming the desktop frontend uses).
     */
    static std::string GetPerGameConfigPath(u64 program_id);

private:
    /**
     * Applies a value read from the android_config to a Setting.
     *
     * @param group The name of the INI group
     * @param setting The yuzu setting to modify
     */
    template <typename Type, bool ranged>
    void ReadSetting(const std::string& group, Settings::Setting<Type, ranged>& setting);
};

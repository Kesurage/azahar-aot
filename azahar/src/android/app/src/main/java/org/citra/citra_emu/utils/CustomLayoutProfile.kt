// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.utils.SettingsFile

/**
 * Keeps the custom screen layout (top and bottom screen position and size) for one game or for
 * every game. The numbers are ordinary per-game settings, so a game's own copy lives in its
 * per-game ini and the global copy in the config file.
 */
object CustomLayoutProfile {
    private fun withoutThese(gameId: String, settings: List<IntSetting>) =
        SettingsFile.readPerGameSettings(gameId).filter { entry ->
            settings.none { it.key == entry.key }
        }

    /** Keeps [settings] for [gameId] alone. The global config is not touched. */
    fun saveForThisGame(gameId: String, settings: List<IntSetting>) {
        val own = settings.map {
            SettingsFile.PerGameEntry(it.section!!, it.key!!, it.valueAsString)
        }
        SettingsFile.savePerGameSettings(gameId, withoutThese(gameId, settings) + own, null)
    }

    /**
     * Keeps [settings] for every game. If the game had a layout of its own it is dropped, so the
     * game follows the global one like the rest.
     */
    fun saveForAllGames(gameId: String?, settings: List<IntSetting>) {
        settings.forEach { SettingsFile.saveFile(SettingsFile.FILE_NAME_CONFIG, it) }
        if (gameId != null) {
            SettingsFile.savePerGameSettings(gameId, withoutThese(gameId, settings), null)
        }
    }
}

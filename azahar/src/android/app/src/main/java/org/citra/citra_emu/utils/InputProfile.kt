// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication

/**
 * Picks the preference file that holds the gamepad mapping, the hotkeys, the touch bindings and
 * the on-screen controller layout (positions, scales, opacity, enabled buttons).
 *
 * By default that is the global preference file. A game can opt in to its own copy ("custom
 * controls"): turning it on copies the global values once, after that the game only reads and
 * writes its own file. Turning it off goes back to the global file but keeps the game's copy, so
 * turning it on again brings the old layout back. [reset] throws the copy away.
 */
object InputProfile {
    private const val FILE_PREFIX = "input_profile_"
    private const val KEY_ENABLED = "__custom_controls_enabled"
    private const val KEY_SEEDED = "__custom_controls_seeded"

    /** The game that is being played right now (null in the game list). */
    @Volatile
    var activeGameId: String? = null
        private set

    /** The game whose per-game settings are being edited (null when editing global settings). */
    @Volatile
    private var editingGameId: String? = null

    private val context: Context get() = CitraApplication.appContext

    private fun globalPrefs(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    private fun profilePrefs(gameId: String): SharedPreferences =
        context.getSharedPreferences(FILE_PREFIX + gameId, Context.MODE_PRIVATE)

    fun setActiveGame(gameId: String?) {
        activeGameId = gameId?.takeIf { it.isNotEmpty() }
    }

    fun beginEditing(gameId: String?) {
        editingGameId = gameId?.takeIf { it.isNotEmpty() }
    }

    fun endEditing() {
        editingGameId = null
    }

    /** The preferences every controller / overlay related setting has to read and write. */
    fun prefs(): SharedPreferences {
        val id = editingGameId ?: activeGameId
        return if (id != null && isCustom(id)) profilePrefs(id) else globalPrefs()
    }

    fun isCustom(gameId: String): Boolean =
        profilePrefs(gameId).getBoolean(KEY_ENABLED, false)

    fun enableCustom(gameId: String) {
        val target = profilePrefs(gameId)
        val editor = target.edit()
        if (!target.getBoolean(KEY_SEEDED, false)) {
            editor.clear()
            for ((key, value) in globalPrefs().all) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.putBoolean(KEY_SEEDED, true)
        }
        editor.putBoolean(KEY_ENABLED, true).apply()
    }

    /** Goes back to the global controls but keeps the game's copy. */
    fun disableCustom(gameId: String) {
        profilePrefs(gameId).edit().putBoolean(KEY_ENABLED, false).apply()
    }

    /** Throws the game's copy away. */
    fun reset(gameId: String) {
        profilePrefs(gameId).edit().clear().apply()
    }
}

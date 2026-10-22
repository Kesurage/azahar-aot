// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication

/**
 * Lets the layout of the on-screen controls (positions, scales, opacity, which buttons are shown)
 * be kept for one game only. The gamepad mapping, hotkeys and touch bindings are not affected,
 * they are always global.
 *
 * While a game runs the overlay reads and writes [prefs], which is the game's own layout if it
 * has one and the global one otherwise. When an edit is finished the user chooses where it is
 * kept (see [saveForThisGame], [saveForAllGames], [discard]).
 */
object OverlayProfile {
    private const val FILE_PREFIX = "overlay_layout_"
    private const val KEY_CUSTOM = "__overlay_custom"

    /** Every preference key that belongs to the layout of the on-screen controls. */
    private val layoutKey = Regex(
        "^\\d+(-[A-Za-z]+)?-[XY]$|^buttonToggle\\d+$|^controlScale.*$|" +
            "^controlOpacity(-\\d+)?$|^button(Hold|Rapid)-\\d+$|^OverlayInit$|^isTouchEnabled$"
    )

    /** The game that is being played right now (null in the game list). */
    @Volatile
    var activeGameId: String? = null
        private set

    private val context: Context get() = CitraApplication.appContext

    private fun globalPrefs(): SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    private fun gamePrefs(gameId: String): SharedPreferences =
        context.getSharedPreferences(FILE_PREFIX + gameId, Context.MODE_PRIVATE)

    fun setActiveGame(gameId: String?) {
        activeGameId = gameId?.takeIf { it.isNotEmpty() }
    }

    fun isCustom(gameId: String): Boolean = gamePrefs(gameId).getBoolean(KEY_CUSTOM, false)

    /** True if the game being played uses a layout of its own. */
    fun isActiveCustom(): Boolean = activeGameId?.let { isCustom(it) } ?: false

    /** The preferences the on-screen controls have to read and write. */
    fun prefs(): SharedPreferences {
        val id = activeGameId
        return if (id != null && isCustom(id)) gamePrefs(id) else globalPrefs()
    }

    /** The layout the on-screen controls use right now. */
    fun snapshot(): Map<String, Any> =
        prefs().all.filter { layoutKey.matches(it.key) && it.value != null }
            .mapValues { it.value!! }

    fun changed(before: Map<String, Any>): Boolean = snapshot() != before

    private fun write(target: SharedPreferences, values: Map<String, Any>) {
        val editor = target.edit()
        for (key in target.all.keys) {
            if (layoutKey.matches(key) && key !in values) {
                editor.remove(key)
            }
        }
        for ((key, value) in values) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
    }

    /** Puts the layout back as it was before the edit. */
    fun discard(before: Map<String, Any>) = write(prefs(), before)

    /**
     * Keeps [edited] for [gameId] alone. If the edit was made on the global layout, the global
     * layout goes back to [before].
     */
    fun saveForThisGame(
        gameId: String,
        before: Map<String, Any>,
        wasCustom: Boolean,
        edited: Map<String, Any>
    ) {
        if (!wasCustom) {
            write(globalPrefs(), before)
        }
        val target = gamePrefs(gameId)
        write(target, edited)
        target.edit().putBoolean(KEY_CUSTOM, true).apply()
    }

    /**
     * Keeps [edited] for every game. If the game had a layout of its own it is dropped, so the
     * game follows the global one like the rest.
     */
    fun saveForAllGames(gameId: String, wasCustom: Boolean, edited: Map<String, Any>) {
        if (wasCustom) {
            write(globalPrefs(), edited)
            reset(gameId)
        }
    }

    /** True if [key] is one of the preference keys that make up the on-screen controls layout. */
    fun isLayoutKey(key: String): Boolean = layoutKey.matches(key)

    private fun globalLayout(): Map<String, Any> =
        globalPrefs().all.filter { layoutKey.matches(it.key) && it.value != null }
            .mapValues { it.value!! }

    /** Imported keys must keep the defaults from being laid out again over the positions. */
    private fun withInit(imported: Map<String, Any>): Map<String, Any> =
        if (imported.keys.any { it.endsWith("-X") || it.endsWith("-Y") }) {
            imported + ("OverlayInit" to true)
        } else {
            imported
        }

    /** Keeps an imported layout for [gameId] alone; keys that were not imported stay as they are. */
    fun importForGame(gameId: String, imported: Map<String, Any>) {
        val target = gamePrefs(gameId)
        write(target, snapshot() + withInit(imported))
        target.edit().putBoolean(KEY_CUSTOM, true).apply()
    }

    /**
     * Keeps an imported layout for every game; keys that were not imported stay as they are. A
     * layout of the game's own is dropped so the game follows the global one.
     */
    fun importForAllGames(gameId: String?, imported: Map<String, Any>) {
        write(globalPrefs(), globalLayout() + withInit(imported))
        if (gameId != null) {
            reset(gameId)
        }
    }

    /** Throws the game's own layout away. */
    fun reset(gameId: String) {
        gamePrefs(gameId).edit().clear().apply()
    }
}

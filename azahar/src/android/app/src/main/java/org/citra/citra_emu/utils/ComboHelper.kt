// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.utils

import android.content.Context
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.features.hotkeys.Hotkey
import org.citra.citra_emu.features.settings.model.IntListSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile

object ComboHelper {
    const val COMBO_COUNT = 5

    // Pseudo button codes (not real native buttons) used for analog stick members of a combo.
    // They must match res/values/arrays.xml -> comboOptionValues.
    const val CIRCLE_LEFT = 1001
    const val CIRCLE_UP = 1002
    const val CIRCLE_RIGHT = 1003
    const val CIRCLE_DOWN = 1004
    const val C_LEFT = 1005
    const val C_UP = 1006
    const val C_RIGHT = 1007
    const val C_DOWN = 1008

    private val comboHotkeys = listOf(
        Hotkey.COMBO_BUTTON.button,
        Hotkey.COMBO_BUTTON_2.button,
        Hotkey.COMBO_BUTTON_3.button,
        Hotkey.COMBO_BUTTON_4.button,
        Hotkey.COMBO_BUTTON_5.button
    )

    // Same shared-preference keys the in-game "Toggle Controls" dialog uses for the combo
    // buttons (buttonToggle16..20), so the sidebar and the settings dialog are one switch.
    private const val TOGGLE_BASE = 16
    private const val KEY_USER_SET = "combo_user_set_"

    // Defaults like Eden: 1 = L+R, 2 = A+B, 3 = X+Y, 4 = ZL+ZR, 5 = empty.
    private val DEFAULT_BUTTONS = arrayOf(
        listOf(773, 774), // L + R
        listOf(700, 701), // A + B
        listOf(702, 703), // X + Y
        listOf(707, 708), // ZL + ZR
        listOf()
    )

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    private fun setting(index: Int): IntListSetting? = when (index) {
        0 -> IntListSetting.COMBO_BUTTON_BUTTONS
        1 -> IntListSetting.COMBO_BUTTON_BUTTONS_2
        2 -> IntListSetting.COMBO_BUTTON_BUTTONS_3
        3 -> IntListSetting.COMBO_BUTTON_BUTTONS_4
        4 -> IntListSetting.COMBO_BUTTON_BUTTONS_5
        else -> null
    }

    /** Which combo slots (0..4) are present in a set of internal hotkey button ids. */
    fun comboIndicesIn(buttonSet: Collection<Int>): List<Int> =
        comboHotkeys.indices.filter { buttonSet.contains(comboHotkeys[it]) }

    fun isEnabled(context: Context, index: Int): Boolean {
        if (index !in 0 until COMBO_COUNT) return false
        return OverlayProfile.prefs()
            .getBoolean("buttonToggle${TOGGLE_BASE + index}", false)
    }

    fun setEnabled(context: Context, index: Int, enabled: Boolean) {
        if (index !in 0 until COMBO_COUNT) return
        OverlayProfile.prefs()
            .edit().putBoolean("buttonToggle${TOGGLE_BASE + index}", enabled).commit()
        syncToConfig()
    }

    // ---- config.ini mirror -------------------------------------------------------------------
    // The on/off switches live in SharedPreferences, which Android wipes on uninstall. They are
    // mirrored into config.ini ([Controls] combo_button_enabled, combo_button_enabled_2, ...) which
    // sits in the user directory and survives a reinstall; on the next start they are restored.

    private fun enabledKey(index: Int) =
        if (index == 0) "combo_button_enabled" else "combo_button_enabled_${index + 1}"

    private fun listKey(index: Int) =
        if (index == 0) "combo_button_buttons" else "combo_button_buttons_${index + 1}"

    @Volatile
    private var restored = false

    /** Writes the current switches and button lists of all combos to config.ini. */
    fun syncToConfig() {
        val values = HashMap<String, String>()
        for (i in 0 until COMBO_COUNT) {
            values[enabledKey(i)] = isEnabled(CitraApplication.appContext, i).toString()
            values[listKey(i)] = getButtons(i).joinToString()
        }
        SettingsFile.writeValues(SettingsFile.FILE_NAME_CONFIG, Settings.SECTION_CONTROLS, values)
    }

    /**
     * Copies switches and button lists from config.ini into the preferences for every combo the
     * app does not know yet (fresh install). Existing preferences always win. Runs once per process.
     */
    fun restoreFromConfig() {
        if (restored) return
        restored = true
        val global = prefs()
        val stored = SettingsFile.readValues(
            SettingsFile.FILE_NAME_CONFIG,
            Settings.SECTION_CONTROLS,
            (0 until COMBO_COUNT).flatMap { listOf(enabledKey(it), listKey(it)) }
        )
        if (stored.isEmpty()) return
        val editor = global.edit()
        for (i in 0 until COMBO_COUNT) {
            val toggleKey = "buttonToggle${TOGGLE_BASE + i}"
            stored[enabledKey(i)]?.let {
                if (!global.contains(toggleKey)) editor.putBoolean(toggleKey, it.trim().toBoolean())
            }
            stored[listKey(i)]?.let { raw ->
                if (!global.contains(KEY_LIST + i)) {
                    val list = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
                    if (list.isNotEmpty()) {
                        editor.putString(KEY_LIST + i, list.joinToString(","))
                        setting(i)?.list = list
                    }
                }
            }
        }
        editor.commit()
    }

    private const val KEY_LIST = "combo_buttons_list_"

    /**
     * Buttons a combo presses. The list lives in SharedPreferences (written synchronously) so it
     * survives the app being closed or killed before config.ini is flushed. Until the user edits a
     * combo the defaults above are used; once edited (even to "none") the stored list is used as-is.
     */
    fun getButtons(index: Int): List<Int> {
        val s = setting(index) ?: return emptyList()
        val stored = prefs().getString(KEY_LIST + index, null)
        if (stored != null) {
            return stored.split(",").mapNotNull { it.trim().toIntOrNull() }
        }
        // Legacy: lists saved by older builds only in config.ini.
        if (s.list.isNotEmpty()) return s.list
        return DEFAULT_BUTTONS[index]
    }

    fun setButtons(context: Context, index: Int, buttons: List<Int>) {
        val s = setting(index) ?: return
        s.list = buttons.toList()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY_LIST + index, buttons.joinToString(","))
            .putBoolean(KEY_USER_SET + index, true)
            .commit()
        syncToConfig()
    }

    fun resetButtons(context: Context, index: Int) {
        val s = setting(index) ?: return
        s.list = listOf()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .remove(KEY_LIST + index)
            .putBoolean(KEY_USER_SET + index, false)
            .commit()
        syncToConfig()
    }

    /** (native value, label) for every selectable member, in display order. */
    fun options(context: Context): List<Pair<Int, String>> {
        val values = context.resources.getIntArray(R.array.comboOptionValues)
        val labels = context.resources.getStringArray(R.array.comboOptions)
        return values.indices.map { values[it] to labels[it] }
    }

    fun describe(context: Context, buttons: List<Int>): String {
        if (buttons.isEmpty()) return context.getString(R.string.combo_none)
        val labels = options(context).toMap()
        return buttons.joinToString(" + ") { labels[it] ?: it.toString() }
    }

    fun comboActivate(buttonStatus: Int, comboIndex: Int = 0) {
        if (comboIndex !in 0 until COMBO_COUNT) return
        val comboArray = getButtons(comboIndex)
        val pressed = buttonStatus == NativeLibrary.ButtonState.PRESSED

        // Accumulated analog stick deflection (y follows Android convention: up is negative).
        var lx = 0f; var ly = 0f; var cx = 0f; var cy = 0f
        var usesLeft = false; var usesC = false

        for (nativeButton in comboArray) {
            when (nativeButton) {
                -1 -> continue // don't parse bad inputs
                CIRCLE_LEFT -> { lx -= 1f; usesLeft = true }
                CIRCLE_RIGHT -> { lx += 1f; usesLeft = true }
                CIRCLE_UP -> { ly -= 1f; usesLeft = true }
                CIRCLE_DOWN -> { ly += 1f; usesLeft = true }
                C_LEFT -> { cx -= 1f; usesC = true }
                C_RIGHT -> { cx += 1f; usesC = true }
                C_UP -> { cy -= 1f; usesC = true }
                C_DOWN -> { cy += 1f; usesC = true }
                else -> NativeLibrary.onGamePadEvent(
                    NativeLibrary.TOUCHSCREEN_DEVICE,
                    nativeButton,
                    buttonStatus
                )
            }
        }

        // On release the stick is returned to centre.
        if (usesLeft) {
            NativeLibrary.onGamePadMoveEvent(
                NativeLibrary.TOUCHSCREEN_DEVICE,
                NativeLibrary.ButtonType.STICK_LEFT,
                if (pressed) lx.coerceIn(-1f, 1f) else 0f,
                if (pressed) ly.coerceIn(-1f, 1f) else 0f
            )
        }
        if (usesC) {
            NativeLibrary.onGamePadMoveEvent(
                NativeLibrary.TOUCHSCREEN_DEVICE,
                NativeLibrary.ButtonType.STICK_C,
                if (pressed) cx.coerceIn(-1f, 1f) else 0f,
                if (pressed) cy.coerceIn(-1f, 1f) else 0f
            )
        }
    }
}

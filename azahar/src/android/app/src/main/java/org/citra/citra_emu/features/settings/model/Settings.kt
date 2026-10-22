// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.features.settings.model

import org.citra.citra_emu.utils.OverlayProfile
import android.text.TextUtils
import java.util.TreeMap
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.features.settings.ui.SettingsActivityView
import org.citra.citra_emu.features.settings.utils.PerGameSettings
import org.citra.citra_emu.features.settings.utils.SettingsFile

class Settings {
    private var gameId: String? = null

    var isLoaded = false

    /** True while the settings of a single application (not the global ones) are being edited. */
    val isPerGame: Boolean get() = !TextUtils.isEmpty(gameId)

    /** Global value (as written in the ini file) of every switchable setting, by key. */
    private val globalValues = HashMap<String, String>()

    /** Keys of the settings that the loaded per application config file overrides. */
    private val overriddenKeys = HashSet<String>()

    /**
     * A HashMap<String></String>, SettingSection> that constructs a new SettingSection instead of returning null
     * when getting a key not already in the map
     */
    class SettingsSectionMap : HashMap<String, SettingSection?>() {
        override operator fun get(key: String): SettingSection? {
            if (!super.containsKey(key)) {
                val section = SettingSection(key)
                super.put(key, section)
                return section
            }
            return super.get(key)
        }
    }

    var sections: HashMap<String, SettingSection?> = SettingsSectionMap()

    fun getSection(sectionName: String): SettingSection? = sections[sectionName]

    val isEmpty: Boolean
        get() = sections.isEmpty()

    fun loadSettings(view: SettingsActivityView? = null) {
        sections = SettingsSectionMap()
        loadCitraSettings(view)
        if (isPerGame) {
            loadCustomGameSettings(gameId!!)
        }
        isLoaded = true
    }

    private fun loadCitraSettings(view: SettingsActivityView?) {
        for ((fileName) in configFileSectionsMap) {
            sections.putAll(SettingsFile.readFile(fileName, view))
        }
    }

    /**
     * Remembers the global values of everything that can be overridden, then applies the
     * overrides stored for the application on top of them.
     */
    private fun loadCustomGameSettings(gameId: String) {
        globalValues.clear()
        overriddenKeys.clear()
        for (setting in PerGameSettings.allOverridableSettings()) {
            globalValues[setting.key!!] = setting.valueAsString
        }

        for (entry in SettingsFile.readPerGameSettings(gameId)) {
            if (!PerGameSettings.isOverridable(entry.key)) {
                continue
            }
            val setting = SettingsFile.settingFromLine("${entry.key}=${entry.value}") ?: continue
            sections[entry.section]!!.putSetting(setting)
            overriddenKeys.add(entry.key)
        }
    }

    /** Per-game lists only show the settings that differ from the global ones. */
    var showCustomizedOnly = false

    /** How many settings of a menu have their own value for this application. */
    fun customCount(menuKey: String): Int {
        if (!isPerGame) {
            return 0
        }
        val sections = PerGameSettings.sectionsOf(menuKey)
        return PerGameSettings.allOverridableSettings().count {
            it.section in sections && isOverridden(it)
        }
    }

    /** Whether the setting currently has its own value for this application. */
    fun isOverridden(setting: AbstractSetting?): Boolean {
        val key = setting?.key ?: return false
        if (!isPerGame || !PerGameSettings.isOverridable(key)) {
            return false
        }
        return key in overriddenKeys || setting.valueAsString != globalValues[key]
    }

    /** Puts a single setting back to the global value (the "use global" state). */
    fun resetToGlobal(setting: AbstractSetting) {
        val key = setting.key ?: return
        for (k in listOfNotNull(key, PerGameSettings.linkedKey(key))) {
            globalValues[k]?.let { SettingsFile.settingFromLine("$k=$it") }
            overriddenKeys.remove(k)
        }
    }

    /** Drops every override of this application, both in memory and on disk. */
    fun resetAllToGlobal() {
        if (!isPerGame) {
            return
        }
        for (setting in PerGameSettings.allOverridableSettings()) {
            globalValues[setting.key!!]?.let { SettingsFile.settingFromLine("${setting.key}=$it") }
        }
        overriddenKeys.clear()
        OverlayProfile.reset(gameId!!)
        SettingsFile.deletePerGameSettings(gameId!!)
    }

    /**
     * The settings objects are process wide singletons, so after editing an application they
     * still hold its values. Reload the global config so nothing leaks into other screens.
     */
    fun restoreGlobalValues() {
        if (isPerGame) {
            loadCitraSettings(null)
        }
    }

    private fun collectOverrides(): List<SettingsFile.PerGameEntry> {
        val overridden = HashSet<String>()
        for (setting in PerGameSettings.allOverridableSettings()) {
            if (isOverridden(setting)) {
                overridden.add(setting.key!!)
                PerGameSettings.linkedKey(setting.key!!)?.let { overridden.add(it) }
            }
        }
        return PerGameSettings.allOverridableSettings()
            .filter { it.key in overridden }
            .map { SettingsFile.PerGameEntry(it.section!!, it.key!!, it.valueAsString) }
    }

    fun loadSettings(gameId: String, view: SettingsActivityView) {
        this.gameId = gameId
        loadSettings(view)
    }

    fun saveSettings(view: SettingsActivityView) {
        if (TextUtils.isEmpty(gameId)) {
            view.showToastMessage(
                CitraApplication.appContext.getString(R.string.ini_saved),
                false
            )
            for ((fileName, sectionNames) in configFileSectionsMap.entries) {
                val iniSections = TreeMap<String, SettingSection?>()
                for (section in sectionNames) {
                    iniSections[section] = sections[section]
                }
                SettingsFile.saveFile(fileName, iniSections, view)
            }
        } else {
            val entries = collectOverrides()
            SettingsFile.savePerGameSettings(gameId!!, entries, view)
            view.showToastMessage(
                CitraApplication.appContext.getString(R.string.ini_saved),
                false
            )
        }
    }

    fun saveSetting(setting: AbstractSetting, filename: String) {
        SettingsFile.saveFile(filename, setting)
    }

    companion object {
        const val SECTION_CORE = "Core"
        const val SECTION_SYSTEM = "System"
        const val SECTION_CAMERA = "Camera"
        const val SECTION_CONTROLS = "Controls"
        const val SECTION_RENDERER = "Renderer"
        const val SECTION_LAYOUT = "Layout"
        const val SECTION_UTILITY = "Utility"
        const val SECTION_NETWORK = "WebService"
        const val SECTION_AUDIO = "Audio"
        const val SECTION_DEBUG = "Debugging"
        const val SECTION_THEME = "Theme"
        const val SECTION_CUSTOM_LANDSCAPE = "Custom Landscape Layout"
        const val SECTION_CUSTOM_PORTRAIT = "Custom Portrait Layout"
        const val SECTION_PERFORMANCE_OVERLAY = "Performance Overlay"
        const val SECTION_CHAT_OVERLAY = "Chat Overlay"
        const val SECTION_STORAGE = "Storage"
        const val SECTION_MISC = "Miscellaneous"

        const val KEY_BUTTON_A = "button_a"
        const val KEY_BUTTON_B = "button_b"
        const val KEY_BUTTON_X = "button_x"
        const val KEY_BUTTON_Y = "button_y"
        const val KEY_BUTTON_SELECT = "button_select"
        const val KEY_BUTTON_START = "button_start"
        const val KEY_BUTTON_HOME = "button_home"
        const val KEY_BUTTON_UP = "button_up"
        const val KEY_BUTTON_DOWN = "button_down"
        const val KEY_BUTTON_LEFT = "button_left"
        const val KEY_BUTTON_RIGHT = "button_right"
        const val KEY_BUTTON_L = "button_l"
        const val KEY_BUTTON_R = "button_r"
        const val KEY_BUTTON_ZL = "button_zl"
        const val KEY_BUTTON_ZR = "button_zr"
        const val KEY_CIRCLEPAD_AXIS_VERTICAL = "circlepad_axis_vertical"
        const val KEY_CIRCLEPAD_AXIS_HORIZONTAL = "circlepad_axis_horizontal"
        const val KEY_CSTICK_AXIS_VERTICAL = "cstick_axis_vertical"
        const val KEY_CSTICK_AXIS_HORIZONTAL = "cstick_axis_horizontal"
        const val KEY_DPAD_AXIS_VERTICAL = "dpad_axis_vertical"
        const val KEY_DPAD_AXIS_HORIZONTAL = "dpad_axis_horizontal"
        const val HOTKEY_ENABLE = "hotkey_enable"
        const val HOTKEY_SCREEN_SWAP = "hotkey_screen_swap"
        const val HOTKEY_CYCLE_LAYOUT = "hotkey_toggle_layout"
        const val HOTKEY_CLOSE_GAME = "hotkey_close_game"
        const val HOTKEY_PAUSE_OR_RESUME = "hotkey_pause_or_resume_game"
        const val HOTKEY_QUICKSAVE = "hotkey_quickload"
        const val HOTKEY_QUICKLOAD = "hotkey_quickpause"
        const val HOTKEY_TURBO_LIMIT = "hotkey_turbo_limit"
        const val HOTKEY_BUTTON_COMBO = "hotkey_button_combo"
        const val HOTKEY_BUTTON_COMBO_2 = "hotkey_button_combo_2"
        const val HOTKEY_BUTTON_COMBO_3 = "hotkey_button_combo_3"
        const val HOTKEY_BUTTON_COMBO_4 = "hotkey_button_combo_4"
        const val HOTKEY_BUTTON_COMBO_5 = "hotkey_button_combo_5"

        val buttonKeys = listOf(
            KEY_BUTTON_A,
            KEY_BUTTON_B,
            KEY_BUTTON_X,
            KEY_BUTTON_Y,
            KEY_BUTTON_SELECT,
            KEY_BUTTON_START,
            KEY_BUTTON_HOME
        )
        val buttonTitles = listOf(
            R.string.button_a,
            R.string.button_b,
            R.string.button_x,
            R.string.button_y,
            R.string.button_select,
            R.string.button_start,
            R.string.button_home
        )
        val circlePadKeys = listOf(
            KEY_CIRCLEPAD_AXIS_VERTICAL,
            KEY_CIRCLEPAD_AXIS_HORIZONTAL
        )
        val cStickKeys = listOf(
            KEY_CSTICK_AXIS_VERTICAL,
            KEY_CSTICK_AXIS_HORIZONTAL
        )
        val dPadAxisKeys = listOf(
            KEY_DPAD_AXIS_VERTICAL,
            KEY_DPAD_AXIS_HORIZONTAL
        )
        val dPadButtonKeys = listOf(
            KEY_BUTTON_UP,
            KEY_BUTTON_DOWN,
            KEY_BUTTON_LEFT,
            KEY_BUTTON_RIGHT
        )
        val axisTitles = listOf(
            R.string.controller_axis_vertical,
            R.string.controller_axis_horizontal
        )
        val dPadTitles = listOf(
            R.string.direction_up,
            R.string.direction_down,
            R.string.direction_left,
            R.string.direction_right
        )
        val triggerKeys = listOf(
            KEY_BUTTON_L,
            KEY_BUTTON_R,
            KEY_BUTTON_ZL,
            KEY_BUTTON_ZR
        )
        val triggerTitles = listOf(
            R.string.button_l,
            R.string.button_r,
            R.string.button_zl,
            R.string.button_zr
        )
        val hotKeys = listOf(
            HOTKEY_ENABLE,
            HOTKEY_SCREEN_SWAP,
            HOTKEY_CYCLE_LAYOUT,
            HOTKEY_CLOSE_GAME,
            HOTKEY_PAUSE_OR_RESUME,
            HOTKEY_QUICKSAVE,
            HOTKEY_QUICKLOAD,
            HOTKEY_TURBO_LIMIT,
            HOTKEY_BUTTON_COMBO,
            HOTKEY_BUTTON_COMBO_2,
            HOTKEY_BUTTON_COMBO_3,
            HOTKEY_BUTTON_COMBO_4,
            HOTKEY_BUTTON_COMBO_5
        )
        val hotkeyTitles = listOf(
            R.string.controller_hotkey_enable_button,
            R.string.emulation_swap_screens,
            R.string.emulation_cycle_landscape_layouts,
            R.string.emulation_close_game,
            R.string.emulation_toggle_pause,
            R.string.emulation_quicksave,
            R.string.emulation_quickload,
            R.string.turbo_limit_hotkey,
            R.string.button_combo,
            R.string.button_combo_2,
            R.string.button_combo_3,
            R.string.button_combo_4,
            R.string.button_combo_5
        )

        // TODO: Move these in with the other setting keys in GenerateSettingKeys.cmake
        const val PREF_FIRST_APP_LAUNCH = "FirstApplicationLaunch"
        const val PREF_MATERIAL_YOU = "MaterialYouTheme"
        const val PREF_THEME_MODE = "ThemeMode"
        const val PREF_BLACK_BACKGROUNDS = "BlackBackgrounds"
        const val PREF_SHOW_HOME_APPS = "ShowHomeApps"
        const val PREF_STATIC_THEME_COLOR = "StaticThemeColor"

        private val configFileSectionsMap: MutableMap<String, List<String>> = HashMap()

        /** Names of the ini files (without extension) that hold the global settings. */
        val configFileNames: Set<String> get() = configFileSectionsMap.keys

        init {
            configFileSectionsMap[SettingsFile.FILE_NAME_CONFIG] =
                listOf(
                    SECTION_CORE,
                    SECTION_SYSTEM,
                    SECTION_CAMERA,
                    SECTION_CONTROLS,
                    SECTION_RENDERER,
                    SECTION_LAYOUT,
                    SECTION_NETWORK,
                    SECTION_UTILITY,
                    SECTION_AUDIO,
                    SECTION_DEBUG,
                    SECTION_THEME,
                    SECTION_CUSTOM_LANDSCAPE,
                    SECTION_CUSTOM_PORTRAIT,
                    SECTION_PERFORMANCE_OVERLAY,
                    SECTION_CHAT_OVERLAY,
                    SECTION_STORAGE,
                    SECTION_MISC
                )
        }
    }
}
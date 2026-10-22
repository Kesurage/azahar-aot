// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.utils

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.features.hotkeys.Hotkey
import org.citra.citra_emu.utils.DirectoryInitialization.userDirectory

/**
 * Layout files: plain .ini files in the config folder (next to config.ini) that hold the custom
 * screen layout and the layout of the on-screen controls, so they can be edited by hand, copied
 * between games or kept as a backup.
 *
 * layout_global.ini is the shared file, layout_<title id>.ini belongs to one game.
 */
object LayoutFiles {
    const val GLOBAL_NAME = "layout_global"

    fun gameName(gameId: String) = "layout_$gameId"

    /** What a layout file contains. Screen maps go from the value index (0..7) to the number. */
    class Layout(
        val controls: Map<String, Any>,
        val landscape: Map<Int, Int>,
        val portrait: Map<Int, Int>
    ) {
        val isEmpty: Boolean get() = controls.isEmpty() && landscape.isEmpty() && portrait.isEmpty()
    }

    private const val SECTION_LANDSCAPE = "Screens Landscape"
    private const val SECTION_PORTRAIT = "Screens Portrait"
    private const val SECTION_CONTROLS = "Controls"

    /** Same order as CustomLayoutManager.layoutSettings(). */
    private val screenNames = listOf(
        "top_x", "top_y", "top_width", "top_height",
        "bottom_x", "bottom_y", "bottom_width", "bottom_height"
    )

    private val positionKey = Regex("^\\d+(-[A-Za-z]+)?-[XY]$")
    private val boolKey = Regex("^buttonToggle\\d+$|^OverlayInit$|^isTouchEnabled$")

    /** Only used for the comments in the file, so a wrong name can not break anything. */
    private val controlNames: Map<Int, String> = mapOf(
        NativeLibrary.ButtonType.BUTTON_A to "A",
        NativeLibrary.ButtonType.BUTTON_B to "B",
        NativeLibrary.ButtonType.BUTTON_X to "X",
        NativeLibrary.ButtonType.BUTTON_Y to "Y",
        NativeLibrary.ButtonType.BUTTON_START to "Start",
        NativeLibrary.ButtonType.BUTTON_SELECT to "Select",
        NativeLibrary.ButtonType.BUTTON_HOME to "Home",
        NativeLibrary.ButtonType.BUTTON_ZL to "ZL",
        NativeLibrary.ButtonType.BUTTON_ZR to "ZR",
        NativeLibrary.ButtonType.DPAD_UP to "D-pad",
        NativeLibrary.ButtonType.STICK_LEFT to "Circle pad",
        NativeLibrary.ButtonType.STICK_C to "C-stick",
        NativeLibrary.ButtonType.TRIGGER_L to "L",
        NativeLibrary.ButtonType.TRIGGER_R to "R",
        NativeLibrary.ButtonType.BUTTON_SWAP to "Swap screens",
        NativeLibrary.ButtonType.BUTTON_TURBO to "Turbo",
        Hotkey.COMBO_BUTTON.button to "Combo button 1",
        Hotkey.COMBO_BUTTON_2.button to "Combo button 2",
        Hotkey.COMBO_BUTTON_3.button to "Combo button 3",
        Hotkey.COMBO_BUTTON_4.button to "Combo button 4",
        Hotkey.COMBO_BUTTON_5.button to "Combo button 5"
    )

    private fun configDir(): DocumentFile? {
        val root = DocumentFile.fromTreeUri(CitraApplication.appContext, Uri.parse(userDirectory))
            ?: return null
        return root.findFile("config")
    }

    fun exists(name: String): Boolean = configDir()?.findFile("$name.ini") != null

    private fun numericId(key: String): Int = key.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE

    private fun build(controls: Map<String, Any>, landscape: List<Int>, portrait: List<Int>) =
        buildString {
            append("; AzaharPlus layout file.\n")
            append("; Edit the numbers, then use \"Layout files\" in the in-game overlay menu to import it.\n")
            append("; Screens: pixels from the top left of the game area. Lines you delete keep their current value.\n")
            append("; Controls: positions are pixels, scale and opacity are percent (50 is the default),\n")
            append(";           buttonToggleN=true means that button is shown.\n\n")
            fun screens(title: String, values: List<Int>) {
                append("[").append(title).append("]\n")
                screenNames.forEachIndexed { i, name ->
                    append(name).append('=').append(values.getOrElse(i) { 0 }).append('\n')
                }
                append('\n')
            }
            screens(SECTION_LANDSCAPE, landscape)
            screens(SECTION_PORTRAIT, portrait)
            append("[").append(SECTION_CONTROLS).append("]\n")
            controls.entries
                .sortedWith(compareBy<Map.Entry<String, Any>>({ numericId(it.key) }, { it.key }))
                .forEach { (key, value) ->
                    if (key.endsWith("-X")) {
                        controlNames[numericId(key)]?.let {
                            append("; ").append(it)
                            if (key.contains("Portrait")) append(" (portrait)")
                            append('\n')
                        }
                    }
                    append(key).append('=').append(value.toString()).append('\n')
                }
        }

    /** Writes (or overwrites) config/<name>.ini. Returns false if the file could not be written. */
    fun write(name: String, controls: Map<String, Any>, landscape: List<Int>, portrait: List<Int>): Boolean {
        val dir = configDir() ?: return false
        val file = dir.findFile("$name.ini")
            ?: dir.createFile("application/octet-stream", "$name.ini")
            ?: return false
        val text = build(controls, landscape, portrait)
        return try {
            CitraApplication.appContext.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(text.toByteArray())
                it.flush()
            } != null
        } catch (e: Exception) {
            Log.error("[LayoutFiles] Could not write $name.ini: ${e.message}")
            false
        }
    }

    private fun parseScreen(key: String, value: String): Pair<Int, Int>? {
        val index = screenNames.indexOf(key.lowercase())
        val number = value.toIntOrNull() ?: value.toFloatOrNull()?.toInt()
        return if (index < 0 || number == null) null else index to number.coerceIn(0, 65535)
    }

    private fun parseControl(key: String, value: String): Any? {
        if (!OverlayProfile.isLayoutKey(key)) return null
        return when {
            positionKey.matches(key) -> value.toFloatOrNull()
            boolKey.matches(key) -> when (value.lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
            else -> value.toIntOrNull() ?: value.toFloatOrNull()?.toInt()
        }
    }

    private fun readText(file: DocumentFile, name: String): String? {
        return try {
            CitraApplication.appContext.contentResolver.openInputStream(file.uri)
                ?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.error("[LayoutFiles] Could not read $name.ini: ${e.message}")
            null
        }
    }

    /** Reads config/<name>.ini. Lines that make no sense are skipped. Null if it can not be read. */
    fun read(name: String): Layout? {
        val file = configDir()?.findFile("$name.ini") ?: return null
        val text = readText(file, name) ?: return null

        val controls = LinkedHashMap<String, Any>()
        val landscape = LinkedHashMap<Int, Int>()
        val portrait = LinkedHashMap<Int, Int>()
        var section = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            val split = line.indexOf('=')
            if (split <= 0) continue
            val key = line.substring(0, split).trim()
            val value = line.substring(split + 1).trim()
            when (section) {
                SECTION_LANDSCAPE.lowercase() ->
                    parseScreen(key, value)?.let { (index, number) -> landscape[index] = number }
                SECTION_PORTRAIT.lowercase() ->
                    parseScreen(key, value)?.let { (index, number) -> portrait[index] = number }
                SECTION_CONTROLS.lowercase() ->
                    parseControl(key, value)?.let { controls[key] = it }
            }
        }
        return Layout(controls, landscape, portrait)
    }
}

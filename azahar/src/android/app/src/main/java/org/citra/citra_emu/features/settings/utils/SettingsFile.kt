// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.features.settings.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.util.TreeMap
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.features.settings.model.AbstractSetting
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.FloatSetting
import org.citra.citra_emu.features.settings.model.IntListSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.ScaledFloatSetting
import org.citra.citra_emu.features.settings.model.SettingSection
import org.citra.citra_emu.features.settings.model.Settings.SettingsSectionMap
import org.citra.citra_emu.features.settings.model.StringSetting
import org.citra.citra_emu.features.settings.ui.SettingsActivityView
import org.citra.citra_emu.utils.BiMap
import org.citra.citra_emu.utils.DirectoryInitialization.userDirectory
import org.citra.citra_emu.utils.Log
import org.ini4j.Wini

/**
 * Contains static methods for interacting with .ini files in which settings are stored.
 */
object SettingsFile {
    const val FILE_NAME_CONFIG = "config"
    private const val USE_GLOBAL_SUFFIX = "\\use_global"

    private var sectionsMap = BiMap<String?, String?>()

    /**
     * Reads a given .ini file from disk and returns it as a HashMap of Settings, themselves
     * effectively a HashMap of key/value settings. If unsuccessful, outputs an error telling why it
     * failed.
     *
     * @param ini          The ini file to load the settings from
     * @param isCustomGame
     * @param view         The current view.
     * @return An Observable that emits a HashMap of the file's contents, then completes.
     */
    fun readFile(
        ini: DocumentFile,
        isCustomGame: Boolean,
        view: SettingsActivityView?
    ): HashMap<String, SettingSection?> {
        val sections: HashMap<String, SettingSection?> = SettingsSectionMap()
        var reader: BufferedReader? = null
        try {
            val context: Context = CitraApplication.appContext
            val inputStream = context.contentResolver.openInputStream(ini.uri)
            reader = BufferedReader(InputStreamReader(inputStream))
            var current: SettingSection? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.startsWith("[") && line!!.endsWith("]")) {
                    current = sectionFromLine(line!!, isCustomGame)
                    sections[current.name] = current
                } else if (current != null) {
                    val setting = settingFromLine(line!!)
                    if (setting != null) {
                        current.putSetting(setting)
                    }
                }
            }
        } catch (e: FileNotFoundException) {
            Log.error("[SettingsFile] File not found: " + ini.uri + e.message)
            view?.onSettingsFileNotFound()
        } catch (e: IOException) {
            Log.error("[SettingsFile] Error reading from: " + ini.uri + e.message)
            view?.onSettingsFileNotFound()
        } finally {
            if (reader != null) {
                try {
                    reader.close()
                } catch (e: IOException) {
                    Log.error("[SettingsFile] Error closing: " + ini.uri + e.message)
                }
            }
        }
        return sections
    }

    fun readFile(fileName: String, view: SettingsActivityView?): HashMap<String, SettingSection?> =
        readFile(getSettingsFile(fileName), false, view)

    fun readFile(fileName: String): HashMap<String, SettingSection?> = readFile(fileName, null)

    /**
     * One overridden setting read from a per application config file.
     */
    data class PerGameEntry(val section: String, val key: String, val value: String)

    /**
     * Reads config/custom/<title id>.ini. Only settings flagged with `<key>\use_global=false` are
     * returned, everything else in the file is ignored (this is the same file format the desktop
     * frontend writes, and what the native Config::ApplyPerGameConfig reads).
     *
     * @param gameId 16 digit hex title id, see [PerGameSettings.fileName]
     */
    fun readPerGameSettings(gameId: String): List<PerGameEntry> {
        val file = findPerGameSettingsFile(gameId, create = false) ?: return emptyList()
        val values = LinkedHashMap<Pair<String, String>, String>()
        val overridden = LinkedHashSet<Pair<String, String>>()
        try {
            val context: Context = CitraApplication.appContext
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                var section = ""
                BufferedReader(InputStreamReader(stream)).forEachLine { raw ->
                    val line = raw.trim()
                    if (line.startsWith("[") && line.endsWith("]")) {
                        section = line.substring(1, line.length - 1)
                        return@forEachLine
                    }
                    val split = line.indexOf('=')
                    if (section.isEmpty() || split <= 0) {
                        return@forEachLine
                    }
                    val key = line.substring(0, split).trim()
                    val value = line.substring(split + 1).trim()
                    if (key.endsWith(USE_GLOBAL_SUFFIX)) {
                        if (!value.toBoolean()) {
                            overridden.add(section to key.removeSuffix(USE_GLOBAL_SUFFIX))
                        }
                    } else {
                        values[section to key] = value
                    }
                }
            }
        } catch (e: Exception) {
            Log.error("[SettingsFile] Error reading per game settings $gameId: ${e.message}")
            return emptyList()
        }
        return overridden.mapNotNull { id ->
            values[id]?.let { PerGameEntry(id.first, id.second, it) }
        }
    }

    /**
     * Writes config/custom/<title id>.ini. An empty list removes the file again so that games
     * without any override do not leave files behind.
     */
    fun savePerGameSettings(gameId: String, entries: List<PerGameEntry>, view: SettingsActivityView?) {
        if (entries.isEmpty()) {
            deletePerGameSettings(gameId)
            return
        }
        val file = findPerGameSettingsFile(gameId, create = true)
        if (file == null) {
            Log.error("[SettingsFile] Could not create per game settings file for $gameId")
            view?.showToastMessage(
                CitraApplication.appContext.getString(R.string.error_saving, gameId, "create"),
                false
            )
            return
        }
        val text = buildString {
            entries.groupBy { it.section }.forEach { (section, sectionEntries) ->
                append('[').append(section).append("]\n")
                sectionEntries.forEach {
                    append(it.key).append(USE_GLOBAL_SUFFIX).append("=false\n")
                    append(it.key).append('=').append(it.value).append('\n')
                }
                append('\n')
            }
        }
        try {
            val context: Context = CitraApplication.appContext
            context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                it.write(text.toByteArray())
                it.flush()
            }
        } catch (e: Exception) {
            Log.error("[SettingsFile] Error saving per game settings $gameId: ${e.message}")
            view?.showToastMessage(
                CitraApplication.appContext.getString(R.string.error_saving, gameId, e.message),
                false
            )
        }
    }

    fun deletePerGameSettings(gameId: String) {
        findPerGameSettingsFile(gameId, create = false)?.delete()
    }

    fun hasPerGameSettings(gameId: String): Boolean =
        findPerGameSettingsFile(gameId, create = false) != null

    /**
     * Saves a Settings HashMap to a given .ini file on disk. If unsuccessful, outputs an error
     * telling why it failed.
     *
     * @param fileName The target filename without a path or extension.
     * @param sections The HashMap containing the Settings we want to serialize.
     * @param view     The current view.
     */
    fun saveFile(
        fileName: String,
        sections: TreeMap<String, SettingSection?>,
        view: SettingsActivityView
    ) {
        val ini = getSettingsFile(fileName)
        try {
            val context: Context = CitraApplication.appContext
            val inputStream = context.contentResolver.openInputStream(ini.uri)
            val writer = Wini(inputStream)
            val keySet: Set<String> = sections.keys
            for (key in keySet) {
                val section = sections[key]
                writeSection(writer, section!!)
            }
            inputStream!!.close()
            val outputStream = context.contentResolver.openOutputStream(ini.uri, "wt")
            writer.store(outputStream)
            outputStream!!.flush()
            outputStream.close()
        } catch (e: Exception) {
            Log.error("[SettingsFile] File not found: $fileName.ini: ${e.message}")
            view.showToastMessage(
                CitraApplication.appContext
                    .getString(R.string.error_saving, fileName, e.message),
                false
            )
        }
    }

    /**
     * Reads plain `key=value` entries of one section straight from the ini, for values that are
     * not backed by an [AbstractSetting]. Missing keys (or an unreadable file) are left out.
     */
    fun readValues(fileName: String, section: String, keys: Collection<String>): Map<String, String> {
        return try {
            val ini = getSettingsFile(fileName)
            val context: Context = CitraApplication.appContext
            context.contentResolver.openInputStream(ini.uri)?.use { stream ->
                val reader = Wini(stream)
                keys.mapNotNull { key -> reader[section, key]?.let { key to it } }.toMap()
            } ?: emptyMap()
        } catch (e: Exception) {
            Log.error("[SettingsFile] Could not read $fileName.ini: ${e.message}")
            emptyMap()
        }
    }

    /** Writes plain `key=value` entries into one section, keeping everything else in the file. */
    fun writeValues(fileName: String, section: String, values: Map<String, String>) {
        try {
            val ini = getSettingsFile(fileName)
            val context: Context = CitraApplication.appContext
            val writer = context.contentResolver.openInputStream(ini.uri)!!.use { Wini(it) }
            values.forEach { (key, value) -> writer.put(section, key, value) }
            context.contentResolver.openOutputStream(ini.uri, "wt")!!.use {
                writer.store(it)
                it.flush()
            }
        } catch (e: Exception) {
            Log.error("[SettingsFile] Could not write $fileName.ini: ${e.message}")
        }
    }

    fun saveFile(fileName: String, setting: AbstractSetting) {
        val ini = getSettingsFile(fileName)
        try {
            val context: Context = CitraApplication.appContext
            val inputStream = context.contentResolver.openInputStream(ini.uri)
            val writer = Wini(inputStream)
            writer.put(setting.section, setting.key, setting.valueAsString)
            inputStream!!.close()
            val outputStream = context.contentResolver.openOutputStream(ini.uri, "wt")
            writer.store(outputStream)
            outputStream!!.flush()
            outputStream.close()
        } catch (e: Exception) {
            Log.error("[SettingsFile] File not found: $fileName.ini: ${e.message}")
        }
    }

    private fun mapSectionNameFromIni(generalSectionName: String): String? =
        if (sectionsMap.getForward(generalSectionName) != null) {
            sectionsMap.getForward(generalSectionName)
        } else {
            generalSectionName
        }

    private fun mapSectionNameToIni(generalSectionName: String): String =
        if (sectionsMap.getBackward(generalSectionName) != null) {
            sectionsMap.getBackward(generalSectionName).toString()
        } else {
            generalSectionName
        }

    fun getSettingsFile(fileName: String): DocumentFile {
        val root = DocumentFile.fromTreeUri(CitraApplication.appContext, Uri.parse(userDirectory))
        val configDirectory = root!!.findFile("config")
        return configDirectory!!.findFile("$fileName.ini")!!
    }

    /**
     * Looks up <user dir>/config/custom/<gameId>.ini, optionally creating the directory and file.
     */
    private fun findPerGameSettingsFile(gameId: String, create: Boolean): DocumentFile? {
        val root = DocumentFile.fromTreeUri(CitraApplication.appContext, Uri.parse(userDirectory))
            ?: return null
        val config = root.findFile("config") ?: return null
        val custom = config.findFile("custom")
            ?: if (create) config.createDirectory("custom") else null
        custom ?: return null
        return custom.findFile("$gameId.ini")
            ?: if (create) custom.createFile("application/octet-stream", "$gameId.ini") else null
    }

    private fun sectionFromLine(line: String, isCustomGame: Boolean): SettingSection {
        var sectionName: String = line.substring(1, line.length - 1)
        if (isCustomGame) {
            sectionName = mapSectionNameToIni(sectionName)
        }
        return SettingSection(sectionName)
    }

    /**
     * For a line of text, determines what type of data is being represented, and returns
     * a Setting object containing this data.
     *
     * @param line    The line of text being parsed.
     * @return A typed Setting containing the key/value contained in the line.
     */
    fun settingFromLine(line: String): AbstractSetting? {
        val splitLine = line.split("=".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (splitLine.size != 2) {
            return null
        }
        val key = splitLine[0].trim { it <= ' ' }
        val value = splitLine[1].trim { it <= ' ' }
        if (value.isEmpty()) {
            return null
        }

        val booleanSetting = BooleanSetting.from(key)
        if (booleanSetting != null) {
            booleanSetting.boolean = value.toBoolean()
            return booleanSetting
        }

        val intSetting = IntSetting.from(key)
        if (intSetting != null) {
            try {
                intSetting.int = value.toInt()
            } catch (e: NumberFormatException) {
                intSetting.int = if (value.toBoolean()) 1 else 0
            }
            return intSetting
        }

        val scaledFloatSetting = ScaledFloatSetting.from(key)
        if (scaledFloatSetting != null) {
            scaledFloatSetting.float = value.toFloat() * scaledFloatSetting.scale
            return scaledFloatSetting
        }

        val floatSetting = FloatSetting.from(key)
        if (floatSetting != null) {
            floatSetting.float = value.toFloat()
            return floatSetting
        }

        val stringSetting = StringSetting.from(key)
        if (stringSetting != null) {
            stringSetting.string = value
            return stringSetting
        }

        val intListSetting = IntListSetting.from(key)
        if (intListSetting != null) {
            intListSetting.list = value.split(", ").map { it.toInt() }
            return intListSetting
        }

        return null
    }

    /**
     * Writes the contents of a Section HashMap to disk.
     *
     * @param parser  A Wini pointed at a file on disk.
     * @param section A section containing settings to be written to the file.
     */
    private fun writeSection(parser: Wini, section: SettingSection) {
        // Write the section header.
        val header = section.name

        // Write this section's values.
        val settings = section.settings
        val keySet: Set<String> = settings.keys
        for (key in keySet) {
            val setting = settings[key]
            parser.put(header, setting!!.key, setting.valueAsString)
        }
    }
}

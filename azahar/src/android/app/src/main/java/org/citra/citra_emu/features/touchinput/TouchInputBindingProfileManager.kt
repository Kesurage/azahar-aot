// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version.
// Refer to the license.txt file included.

package org.citra.citra_emu.features.touchinput

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceManager
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Stores named sets of touch input bindings. Every profile is equally editable and deletable —
 * there's no special "Default" that's pinned in place. At least one profile always exists: a
 * fresh install starts with "Profile 1", and deleting the last remaining profile replaces it with
 * a new "Profile N" rather than leaving nothing to select.
 */
class TouchInputBindingProfileManager(context: Context) {
    private val preferences: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(
            context.applicationContext ?: CitraApplication.appContext
        )

    // Off while starting up so a fresh empty profile never overwrites a backup in config.ini
    private var mirrorEnabled = false

    init {
        if (getProfiles().isEmpty()) {
            restoreFromConfig()
        }
        if (getProfiles().isEmpty()) {
            replaceAllProfilesWithFreshOne()
        }
    }

    init {
        mirrorEnabled = true
    }

    fun getCurrentProfile(): String =
        preferences.getString(KEY_CURRENT_PROFILE, null) ?: getProfiles().first()

    fun setCurrentProfile(profileName: String) {
        preferences.edit().putString(KEY_CURRENT_PROFILE, profileName).apply()
        mirrorToConfig()
    }

    fun getProfiles(): List<String> {
        val json = preferences.getString(KEY_PROFILES_LIST, null) ?: return emptyList()

        return try {
            val array = JSONArray(json)
            List(array.length()) { array.getString(it) }
        } catch (_: JSONException) {
            emptyList()
        }
    }

    /** "Profile 1", or the next number not already taken by an existing profile. */
    fun suggestNextProfileName(): String = nextAvailableProfileName(getProfiles())

    fun createProfile(profileName: String): Boolean {
        val name = profileName.trim()
        if (name.isEmpty()) return false

        val profiles = getProfiles()
        if (profiles.contains(name)) return false

        saveProfilesList(profiles + name)
        saveProfile(name, emptyList())
        return true
    }

    fun deleteProfile(profileName: String): Boolean {
        val profiles = getProfiles()
        if (!profiles.contains(profileName)) return false

        preferences.edit().remove(getStorageKey(profileName)).apply()

        val remaining = profiles - profileName
        if (remaining.isEmpty()) {
            // Never leave the screen with nothing to select.
            replaceAllProfilesWithFreshOne()
        } else {
            saveProfilesList(remaining)
            if (getCurrentProfile() == profileName) {
                setCurrentProfile(remaining.first())
            }
        }
        return true
    }

    fun renameProfile(oldName: String, newName: String): Boolean {
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty() || oldName == trimmedName) return false

        val profiles = getProfiles()
        val index = profiles.indexOf(oldName)
        if (index == -1 || profiles.contains(trimmedName)) return false

        val bindings = loadProfile(oldName)

        saveProfilesList(profiles.toMutableList().also { it[index] = trimmedName })
        saveProfile(trimmedName, bindings)
        preferences.edit().remove(getStorageKey(oldName)).apply()

        if (getCurrentProfile() == oldName) {
            setCurrentProfile(trimmedName)
        }
        return true
    }

    fun saveProfile(profileName: String, bindings: List<TouchInputBinding>) {
        preferences.edit()
            .putString(getStorageKey(profileName), TouchInputBinding.listToJson(bindings))
            .apply()
        mirrorToConfig()
    }

    fun loadProfile(profileName: String): List<TouchInputBinding> =
        TouchInputBinding.listFromJson(preferences.getString(getStorageKey(profileName), null))

    /** Wipes every profile and starts over with a single freshly-named, empty one. */
    private fun replaceAllProfilesWithFreshOne() {
        val freshName = nextAvailableProfileName(emptyList())
        saveProfilesList(listOf(freshName))
        saveProfile(freshName, emptyList())
        setCurrentProfile(freshName)
    }

    private fun nextAvailableProfileName(existing: Collection<String>): String {
        val taken = existing.toHashSet()
        var number = 1
        while ("$PROFILE_NAME_PREFIX $number" in taken) number++
        return "$PROFILE_NAME_PREFIX $number"
    }

    private fun saveProfilesList(profiles: List<String>) {
        val array = JSONArray()
        profiles.forEach { array.put(it) }
        preferences.edit().putString(KEY_PROFILES_LIST, array.toString()).apply()
        mirrorToConfig()
    }

    // ---- config.ini mirror ---------------------------------------------------------------------
    // Profiles live in SharedPreferences, which Android wipes on uninstall. They are mirrored into
    // config.ini ([Controls] touch_input_profiles, one Base64 JSON blob) in the user directory, and
    // restored from there on a fresh install.

    private fun mirrorToConfig() {
        if (!mirrorEnabled) return
        try {
            val profiles = getProfiles()
            if (profiles.isEmpty()) return
            val root = JSONObject()
            root.put("current", preferences.getString(KEY_CURRENT_PROFILE, null) ?: profiles.first())
            val list = JSONArray()
            profiles.forEach { name ->
                list.put(
                    JSONObject()
                        .put("name", name)
                        .put(
                            "bindings",
                            JSONArray(
                                TouchInputBinding.listToJson(loadProfile(name))
                            )
                        )
                )
            }
            root.put("profiles", list)
            val encoded = Base64.encodeToString(
                root.toString().toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP or Base64.URL_SAFE
            )
            SettingsFile.writeValues(
                SettingsFile.FILE_NAME_CONFIG,
                Settings.SECTION_CONTROLS,
                mapOf(CONFIG_KEY to encoded)
            )
        } catch (_: Exception) {
            // Best effort; the preferences stay the source of truth for this run
        }
    }

    private fun restoreFromConfig() {
        try {
            val encoded = SettingsFile.readValues(
                SettingsFile.FILE_NAME_CONFIG,
                Settings.SECTION_CONTROLS,
                listOf(CONFIG_KEY)
            )[CONFIG_KEY]?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val root = JSONObject(
                String(Base64.decode(encoded, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
            )
            val list = root.getJSONArray("profiles")
            if (list.length() == 0) return
            val names = JSONArray()
            val editor = preferences.edit()
            for (i in 0 until list.length()) {
                val entry = list.getJSONObject(i)
                val name = entry.getString("name")
                names.put(name)
                editor.putString(getStorageKey(name), entry.getJSONArray("bindings").toString())
            }
            editor.putString(KEY_PROFILES_LIST, names.toString())
            editor.putString(KEY_CURRENT_PROFILE, root.optString("current", names.getString(0)))
            editor.commit()
        } catch (_: Exception) {
            // Unreadable or missing: start with a fresh profile instead
        }
    }

    private fun getStorageKey(profileName: String): String = "$KEY_BINDINGS_PREFIX$profileName"

    companion object {
        private const val CONFIG_KEY = "touch_input_profiles"
        private const val PROFILE_NAME_PREFIX = "Profile"

        private const val KEY_CURRENT_PROFILE = "current_profile"
        private const val KEY_PROFILES_LIST = "profiles_list"
        private const val KEY_BINDINGS_PREFIX = "bindings_profile_"
    }
}

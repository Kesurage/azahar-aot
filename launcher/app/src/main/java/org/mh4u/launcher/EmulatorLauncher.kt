package org.mh4u.launcher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drives the emulator(s) already on the phone. The registry is data-driven
 * so package names / activity names / intent extras can be fixed per device
 * without a rebuild: defaults ship as assets/emulators.json, user edits are
 * stored in preferences and win.
 */
object EmulatorLauncher {

    data class Emulator(
        val name: String,
        val packageName: String,
        val activity: String?,   // optional explicit activity class (short form)
        val viewAction: Boolean, // true: ACTION_VIEW with the rom uri
    ) {
        fun toJson(): String = JSONObject().apply {
            put("name", name)
            put("package", packageName)
            put("activity", activity ?: JSONObject.NULL)
            put("viewAction", viewAction)
        }.toString()
    }

    fun defaults(context: Context): MutableList<Emulator> {
        val raw = context.assets.open("emulators.json").bufferedReader().use { it.readText() }
        return parse(raw)
    }

    fun parse(raw: String): MutableList<Emulator> {
        val arr = JSONArray(raw)
        val out = mutableListOf<Emulator>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += Emulator(
                name = o.getString("name"),
                packageName = o.getString("package"),
                activity = if (o.isNull("activity")) null else o.optString("activity"),
                viewAction = o.optBoolean("viewAction", true),
            )
        }
        return out
    }

    fun custom(context: Context): MutableList<Emulator>? {
        val prefs = context.getSharedPreferences("emulators", Context.MODE_PRIVATE)
        val s = prefs.getString("list", null) ?: return null
        return runCatching { parse(s) }.getOrNull()
    }

    fun save(context: Context, list: List<Emulator>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject(it.toJson())) }
        context.getSharedPreferences("emulators", Context.MODE_PRIVATE)
            .edit().putString("list", arr.toString()).apply()
    }

    fun all(context: Context): MutableList<Emulator> =
        custom(context) ?: defaults(context)

    fun installed(context: Context, emu: Emulator): Boolean = runCatching {
        context.packageManager.getPackageInfo(emu.packageName, 0)
        true
    }.getOrDefault(false)

    /** Try to launch [game] in [emu]; returns null on success or a reason string. */
    fun launch(context: Context, emu: Emulator, game: Game): String? {
        if (!installed(context, emu)) return "${emu.name} is not installed"
        // 1) explicit activity + ACTION_VIEW (how most Citra-family forks accept a rom)
        if (emu.viewAction) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(game.uri, "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                if (emu.activity != null) {
                    component = ComponentName(emu.packageName, emu.activity)
                } else {
                    setPackage(emu.packageName)
                }
            }
            if (runCatching { context.startActivity(intent) }.isSuccess) return null
        }
        // 2) just open the emulator, user picks the game inside
        val fallback = context.packageManager.getLaunchIntentForPackage(emu.packageName)
        if (fallback != null) {
            context.startActivity(fallback)
            return "Opened ${emu.name}; pick the game inside it"
        }
        return "Could not start ${emu.name}"
    }

    fun launchBest(context: Context, game: Game): String? {
        val candidates = all(context).filter { installed(context, it) }
        if (candidates.isEmpty()) return "No supported emulator installed (Azahar / AzaharPlus / Citra MMJ)"
        for (emu in candidates) {
            val err = launch(context, emu, game)
            if (err == null) return null
        }
        return "No emulator accepted the game file"
    }

    /** Open the emulator's own settings UI (its launcher intent). */
    fun openEmulator(context: Context, emu: Emulator): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(emu.packageName) ?: return false
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}

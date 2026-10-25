package org.mh4u.port

import java.io.File

/**
 * The app's settings: mh4u-app.ini (the app: display, sound, rooms; the PC port's file) and
 * mh4u.ini (the game's options, which the port's hooks read). Android-only settings (the
 * on-screen controls, extra screen layouts) live in the same app file under [Android], which
 * the PC app ignores.
 */
object Prefs {
    private val app get() = Ini(File(Storage.root, "mh4u-app.ini"))
    private val game get() = Ini(File(Storage.root, "mh4u.ini"))

    fun getApp(section: String, key: String, default: String): String = app.read(section, key) ?: default
    fun setApp(section: String, key: String, value: String) {
        app.write(section, key, value)
        // every display/audio setting takes effect at once in a running game (like Azahar)
        if (section != "Game") apply()
    }

    fun getGame(key: String, default: String): String = game.read("", key) ?: default
    fun setGame(key: String, value: String) {
        game.write("", key, value)
        // the port's hooks read their options live; the game that runs hears the change at once
        if (Native.isRunning()) Native.setGameOption(key, value)
    }

    fun getInt(section: String, key: String, default: Int) = getApp(section, key, default.toString()).toIntOrNull() ?: default
    fun getBool(section: String, key: String, default: Boolean) =
        getApp(section, key, if (default) "on" else "off").lowercase() in setOf("on", "1", "yes", "true")
    fun setBool(section: String, key: String, v: Boolean) = setApp(section, key, if (v) "on" else "off")

    /** The screen options the native side wants besides mh4u-app.ini (see native.cpp). */
    fun screenExtras(): String = buildString {
        append("swap=").append(if (getBool("Android", "swap", false)) 1 else 0).append('\n')
        append("upright=").append(if (getBool("Android", "upright", false)) 1 else 0).append('\n')
        append("gap=").append(getInt("Android", "gap", 0)).append('\n')
        append("large_proportion=").append(getApp("Android", "large_proportion", "4")).append('\n')
        append("portrait=").append(getApp("Android", "portrait", "top")).append('\n')
    }

    /** Writes the settings into the running game (or just before it starts). */
    fun apply() {
        applyInput()
        if (Native.isRunning()) Native.applySettings(screenExtras())
    }

    /** Turbo's buttons (a mask of the 3DS's own, A B X Y by default) and its pulses a second. */
    var turboButtons: Int
        get() = getInt("Controls", "turbo_buttons", MH4UInput.A or MH4UInput.B or MH4UInput.X or MH4UInput.Y)
        set(v) = setApp("Controls", "turbo_buttons", v.toString())
    var turboRate: Int
        get() = getInt("Controls", "turbo_rate", 8).coerceIn(2, 15)
        set(v) = setApp("Controls", "turbo_rate", v.toString())

    fun applyInput() { MH4UInput.setTurbo(turboButtons, turboRate); MH4UInput.applyTuning() }

    /** The Swap screens action (a binding or an on-screen button), or the Display page's switch. */
    fun toggleSwap() = setBool("Android", "swap", !getBool("Android", "swap", false))

    // the game and the code library, as the player set them up
    var gamePath: String
        get() = getApp("Game", "game", "")
        set(v) = setApp("Game", "game", v)

    fun gameFile(): File? = gamePath.takeIf { it.isNotEmpty() }?.let { File(it.removePrefix("!")) }?.takeIf { it.exists() }
    fun codeLibrary(): File? = (Storage.aot.listFiles { f -> f.extension == "so" }?.firstOrNull()
        ?: Storage.recomp.listFiles { f -> f.extension == "so" }?.firstOrNull())
}

package org.mh4u.port

import android.app.Application
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Storage.init(this)
        Native.init(Storage.privateDir.absolutePath)
        MH4UInput.init(Storage.root)
        // HD texture packs dropped in texture_packs/ get unpacked for the game, and the app
        // turns custom textures on for them (the pack's monsters need the Old 3DS mode, which
        // new_3ds = auto already gives)
        thread(name = "mh4u-texture-packs") {
            TexturePacks.installAll()
            if (TexturePacks.installed() && !Prefs.getBool("Display", "custom_textures", false)) {
                Prefs.setApp("Display", "custom_textures", "on")
            }
        }
    }
}

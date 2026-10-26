package org.mh4u.launcher

import android.content.Context
import org.json.JSONObject

/**
 * Per-game profile: the tuned performance settings the launcher applies and
 * remembers. Today these drive the launch hint + bundled core (phase 2);
 * the values mirror what a stable MH4U setup needs: 30 fps target,
 * 1600x720-class internal resolution, no glitch-prone passes, optional FSR.
 */
data class GameProfile(
    val renderer: String = "vulkan",   // vulkan | gles | auto
    val fpsTarget: Int = 30,           // 30 stable, or 60 for strong phones
    val resolution: Int = 2,           // internal resolution factor: 1..4 (2 ~ 800x240 x2 -> 720p-class)
    val fsr: Boolean = true,           // FSR upscale of the rendered frame (bundled core)
    val frameskip: Int = 0,            // 0..3
    val shaderCache: Boolean = true,   // aggressive precompile
) {
    fun toJson(): String = JSONObject().apply {
        put("renderer", renderer)
        put("fps", fpsTarget)
        put("resolution", resolution)
        put("fsr", fsr)
        put("frameskip", frameskip)
        put("shaderCache", shaderCache)
    }.toString()

    companion object {
        fun fromJson(s: String?): GameProfile {
            if (s.isNullOrBlank()) return GameProfile()
            return runCatching {
                val o = JSONObject(s)
                GameProfile(
                    renderer = o.optString("renderer", "vulkan"),
                    fpsTarget = o.optInt("fps", 30),
                    resolution = o.optInt("resolution", 2),
                    fsr = o.optBoolean("fsr", true),
                    frameskip = o.optInt("frameskip", 0),
                    shaderCache = o.optBoolean("shaderCache", true),
                )
            }.getOrDefault(GameProfile())
        }
    }
}

object ProfilesStore {
    private fun file(context: Context, gameUri: String) =
        java.io.File(context.filesDir, "profiles/${gameUri.hashCode().toUInt().toString()}.json")

    fun get(context: Context, gameUri: String): GameProfile {
        val f = file(context, gameUri)
        return GameProfile.fromJson(if (f.exists()) f.readText() else null)
    }

    fun put(context: Context, gameUri: String, profile: GameProfile) {
        file(context, gameUri).parentFile?.mkdirs()
        file(context, gameUri).writeText(profile.toJson())
    }
}

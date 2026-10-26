package org.mh4u.port

import java.util.Locale

/** The native touch_overlay option, kept separate from whether the overlay is visible. */
data class TouchOverlayConfig(
    val corner: String = "top-right",
    val heightPercent: Float = 30f,
    val opacityPercent: Float = 60f,
) {
    fun encode(): String = "$corner $heightPercent $opacityPercent"

    companion object {
        private val corners = setOf("top-left", "top-right", "bottom-left", "bottom-right")

        /** Null means hidden (or an invalid option), as in the native parser. */
        fun parseVisible(value: String?): TouchOverlayConfig? {
            val words = value?.trim()?.lowercase(Locale.ROOT)?.split(Regex("\\s+")) ?: return null
            val corner = words.firstOrNull()?.replace('_', '-') ?: return null
            // Preserve the native defaults for existing configurations using the legacy "on".
            if (corner in setOf("on", "yes", "true", "1")) return TouchOverlayConfig(opacityPercent = 85f)
            if (corner !in corners) return null
            fun percent(index: Int, fallback: Float, minimum: Float): Float =
                words.getOrNull(index)?.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?.coerceIn(minimum, 100f) ?: fallback
            return TouchOverlayConfig(corner, percent(1, 30f, 5f), percent(2, 85f, 10f))
        }
    }
}

/**
 * Hiding used to replace the only saved configuration with "off", losing its corner,
 * size and opacity. Retain the last visible configuration in the app's Android section.
 * This class only reads/writes INIs; Prefs forwards its returned option to the running core.
 */
class TouchOverlayPreferences(private val app: Ini, private val game: Ini) {
    fun isVisible(): Boolean = TouchOverlayConfig.parseVisible(game.read("", "touch_overlay")) != null

    fun configuration(): TouchOverlayConfig =
        TouchOverlayConfig.parseVisible(game.read("", "touch_overlay"))
            ?: TouchOverlayConfig.parseVisible(app.read("Android", "last_touch_overlay"))
            ?: TouchOverlayConfig()

    fun setVisible(visible: Boolean): String = save(configuration(), visible)

    fun save(config: TouchOverlayConfig, visible: Boolean): String {
        val encoded = config.encode()
        app.write("Android", "last_touch_overlay", encoded)
        val option = if (visible) encoded else "off"
        game.write("", "touch_overlay", option)
        return option
    }
}

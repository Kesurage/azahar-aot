package org.mh4u.port

/**
 * A phone's tuned settings, written at once from the Display page's "Phone preset" card.
 *
 * Unlike the old g100-native profile, nothing here is forced on every start: the player taps
 * the button, the values are written once into their own settings files (the app's
 * mh4u-app.ini and the game's mh4u.ini), and from then on they are the player's own values,
 * free to change. The preset is only a good starting point, not a lock.
 *
 * Pure Kotlin so the plain JVM tests can check it (no Android classes in here).
 */
object DevicePreset {
    /** One preset: (section, key, value) lines for mh4u-app.ini and key = value for mh4u.ini. */
    data class Preset(
        val name: String,
        val device: String,
        val app: List<Triple<String, String, String>>,
        val game: List<Pair<String, String>>,
    )

    /**
     * Infinix Hot 50 4G: Helio G100 (4×A78 + 4×A55), Mali G57 MC2, a 720×1600 panel.
     *
     * The reasoning, so the next phone's preset is easy to judge:
     * - fps = 30: the game's own rate. Chasing 60 doubles the GPU's and CPU's work per
     *   second; a steady 30 with no dips plays better than a 45 that drops.
     * - renderer = opengl: the Mali driver's Vulkan shows garbled monster textures and black
     *   cutscenes (see the [Display] renderer comment in the app's template); GLES is the
     *   stable path on this chip.
     * - resolution = 0 with adaptive_resolution on: the governor starts at the 3DS's own
     *   pixels, climbs only while the frame rate and temperature hold, and steps back the
     *   moment either fails. On a 720p panel the native pixels are already a third of the
     *   screen's, and the governor decides itself how far up this unit's silicon goes.
     * - cpu_affinity = 1: keep the emulation and the GPU's workers on the A78s; the OS likes
     *   to drift them onto the A55s, where the frame rate wobbles.
     * - accurate_mul = on: correct translucency on Mali; async_shaders and the disk shader
     *   cache keep the one-time shader stutter out of the hunt.
     * - every full-screen game filter off, antialiasing and the second eye's pass off: each
     *   one is a full extra scene pass, which is the difference between a steady 30 and dips.
     */
    val HOT_50_4G = Preset(
        name = "Infinix Hot 50 4G",
        device = "Helio G100, Mali G57 MC2, 720×1600",
        app = listOf(
            Triple("Game", "cpu_clock", "100"),
            Triple("Game", "cpu_affinity", "1"),
            Triple("Display", "renderer", "opengl"),
            Triple("Display", "resolution", "0"),
            Triple("Display", "adaptive_resolution", "on"),
            Triple("Display", "linear_filter", "on"),
            Triple("Display", "vsync", "on"),
            Triple("Display", "accurate_mul", "on"),
            Triple("Display", "async_shaders", "on"),
            Triple("Display", "disk_shader_cache", "on"),
            Triple("Display", "texture_filter", "none"),
            Triple("Display", "texture_sampling", "game"),
            Triple("Display", "disable_right_eye", "off"),
            Triple("Display", "aot_skip_idle", "on"),
            Triple("Display", "aot_fewer_unwinds", "on"),
        ),
        game = listOf(
            "fps" to "30",
            "widescreen" to "16:9",
            "wide_render" to "0",
            "hud_edges" to "1",
            "dither" to "off",
            "stereo_3d" to "off",
            "antialiasing" to "off",
            "bloom" to "off",
            "depth_of_field" to "off",
            "motion_blur" to "off",
            "radial_blur" to "off",
            "haze" to "off",
            "net_sync" to "on",
        ),
    )

    val ALL = listOf(HOT_50_4G)

    fun byName(name: String): Preset? = ALL.firstOrNull { it.name == name }

    /** Writes the preset through the app's own settings paths (live if the game runs). */
    fun apply(preset: Preset, setApp: (String, String, String) -> Unit, setGame: (String, String) -> Unit) {
        preset.app.forEach { (section, key, value) -> setApp(section, key, value) }
        preset.game.forEach { (key, value) -> setGame(key, value) }
    }
}

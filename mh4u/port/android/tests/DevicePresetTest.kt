import org.mh4u.port.DevicePreset

/**
 * The phone presets are data the app writes straight into the player's settings files: a
 * wrong key lands in an ini nobody reads, a duplicate key silently loses to the last write.
 * These checks keep every preset well-formed and the Hot 50 4G's reasoning in place.
 */
private val fail = object {
    var count = 0
    fun check(condition: Boolean, message: String) {
        check(condition) { message }
        count++
    }
}

private val knownAppSections = setOf("Game", "Display", "Audio", "Android", "Controls")

fun main() {
    // every preset is well formed
    DevicePreset.ALL.forEach { preset ->
        check(preset.name.isNotBlank()) { "preset needs a name" }
        check(preset.device.isNotBlank()) { "preset needs a device line" }
        val appKeys = mutableSetOf<Pair<String, String>>()
        preset.app.forEach { (section, key, value) ->
            check(section in knownAppSections) { "${preset.name}: unknown app section $section" }
            check(key.isNotBlank() && value.isNotBlank()) { "${preset.name}: empty key or value" }
            check(appKeys.add(section to key)) { "${preset.name}: duplicate app key $section/$key" }
        }
        val gameKeys = mutableSetOf<String>()
        preset.game.forEach { (key, value) ->
            check(key.isNotBlank() && value.isNotBlank()) { "${preset.name}: empty key or value" }
            check(gameKeys.add(key)) { "${preset.name}: duplicate game key $key" }
        }
    }

    // the Hot 50 4G's reasoning: a steady 30 on the GLES path, the governor in charge
    val hot = DevicePreset.HOT_50_4G
    val game = hot.game.toMap()
    fun app(section: String, key: String): String =
        hot.app.first { it.first == section && it.second == key }.third
    check(game["fps"] == "30") { "fps: the game's own rate, with headroom" }
    check(app("Display", "renderer") == "opengl") { "Mali's Vulkan shows broken textures" }
    check(app("Display", "resolution") == "0") { "auto: the adaptive governor decides" }
    check(app("Display", "adaptive_resolution") == "on") { "governor holds fps and temperature" }
    check(app("Game", "cpu_affinity") == "1") { "big cores hold the frame rate" }
    check(app("Display", "accurate_mul") == "on") { "correct translucency on Mali" }
    check(game["antialiasing"] == "off") { "a full extra pass on a small GPU" }
    check(game["stereo_3d"] == "off") { "the second eye's pass is a whole scene draw" }
    listOf("bloom", "depth_of_field", "motion_blur", "radial_blur", "haze").forEach {
        check(game[it] == "off") { "$it is a full-screen pass a phone GPU skips" }
    }

    // apply writes every value through the given paths, app and game alike
    val written = mutableListOf<String>()
    DevicePreset.apply(hot, { s, k, v -> written += "app:$s/$k=$v" }, { k, v -> written += "game:$k=$v" })
    check(written.size == hot.app.size + hot.game.size) { "apply: wrote ${written.size}, wanted ${hot.app.size + hot.game.size}" }
    check("game:fps=30" in written) { "apply: the game's fps missing" }
    check("app:Display/renderer=opengl" in written) { "apply: the renderer missing" }

    // byName finds what the page shows, and only that
    check(DevicePreset.byName(hot.name) === hot) { "byName: the preset is not found" }
    check(DevicePreset.byName("Nothing") == null) { "byName: unknown names answer null" }

    println("DevicePresetTest: OK (${DevicePreset.ALL.size} preset(s), ${written.size} values)")
}

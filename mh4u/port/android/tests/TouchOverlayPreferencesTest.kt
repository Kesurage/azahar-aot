package org.mh4u.port

import java.io.File
import java.nio.file.Files

private fun expect(expected: Any?, actual: Any?, message: String) {
    check(expected == actual) { "$message: expected <$expected>, got <$actual>" }
}

private fun withPreferences(test: (File, File, TouchOverlayPreferences) -> Unit) {
    val directory = Files.createTempDirectory("mh4u-touch-overlay-test-").toFile()
    try {
        val app = File(directory, "mh4u-app.ini")
        val game = File(directory, "mh4u.ini")
        test(app, game, TouchOverlayPreferences(Ini(app), Ini(game)))
    } finally {
        directory.deleteRecursively()
    }
}

fun main() {
    withPreferences { app, game, prefs ->
        expect(false, prefs.isVisible(), "Missing native option means hidden")
        expect(false, app.exists(), "Reading defaults must not create app settings")
        expect(false, game.exists(), "Reading defaults must not create game settings")
        expect(TouchOverlayConfig("top-right", 30f, 60f), prefs.configuration(), "New overlay defaults")
        val option = prefs.setVisible(true)
        expect("top-right 30.0 60.0", option, "Newly shown overlay is semitransparent")
        expect(option, Ini(game).read("", "touch_overlay"), "Returned native option is persisted")
        expect(true, prefs.isVisible(), "Enabled option is visible")
    }

    withPreferences { app, game, prefs ->
        app.writeText("# keep my app settings\n[Display]\nscreens = overlay\n[Android]\noverlay = off\n[Audio]\nvolume = 73\n")
        game.writeText("# keep my game settings\nfps = 60\ntouch_overlay = bottom-left 35.5 47\nquest_overlay = map 40\n")
        val original = TouchOverlayConfig("bottom-left", 35.5f, 47f)
        expect(original, prefs.configuration(), "Existing corner, fractional size and opacity")
        expect("off", prefs.setVisible(false), "Hide sends off to native core")
        expect(false, prefs.isVisible(), "Hide state is persisted")

        // Reopen from disk, as after an Activity or process restart, before showing again.
        val reopened = TouchOverlayPreferences(Ini(app), Ini(game))
        expect(original, reopened.configuration(), "Hidden geometry survives process restart")
        expect(original.encode(), reopened.setVisible(true), "Show restores geometry")
        repeat(5) { reopened.setVisible(false); reopened.setVisible(true) }
        expect(original, reopened.configuration(), "Repeated hide/show does not lose geometry")
        expect("off", Ini(app).read("Android", "overlay"), "Virtual control visibility is independent")
        expect("73", Ini(app).read("Audio", "volume"), "Other app sections survive")
        expect("overlay", Ini(app).read("Display", "screens"), "Layout mode survives")
        expect("60", Ini(game).read("", "fps"), "Other game settings survive")
        expect("map 40", Ini(game).read("", "quest_overlay"), "Quest crop preference survives")
        check(app.readText().contains("# keep my app settings"))
        check(game.readText().contains("# keep my game settings"))
    }

    withPreferences { app, game, prefs ->
        prefs.setVisible(false)
        val edited = TouchOverlayConfig("bottom-right", 55f, 25f)
        expect("off", prefs.save(edited, visible = false), "Editing a hidden overlay must not show it")
        val reopened = TouchOverlayPreferences(Ini(app), Ini(game))
        expect(false, reopened.isVisible(), "Hidden edits remain hidden after restart")
        expect(edited, reopened.configuration(), "Hidden edits survive restart")
        expect(edited.encode(), reopened.setVisible(true), "Show applies hidden edits")
    }

    withPreferences { _, game, prefs ->
        game.writeText("touch_overlay = on\n")
        expect(TouchOverlayConfig(opacityPercent = 85f), prefs.configuration(), "Legacy on preserves native opacity")
        prefs.setVisible(false)
        expect("top-right 30.0 85.0", prefs.setVisible(true), "Legacy opacity survives hide/show")
        game.writeText("touch_overlay = top-left 20 90\n")
        expect(TouchOverlayConfig("top-left", 20f, 90f), prefs.configuration(), "Explicit native option takes priority over saved geometry")
    }

    expect(TouchOverlayConfig("bottom-right", 45f, 67f),
        TouchOverlayConfig.parseVisible("  BOTTOM_RIGHT\t45   67  "), "Native corner aliases and whitespace")
    expect(TouchOverlayConfig("top-left", 5f, 100f),
        TouchOverlayConfig.parseVisible("top-left -1 150"), "Native percentage bounds")
    expect(TouchOverlayConfig("top-right", 30f, 85f),
        TouchOverlayConfig.parseVisible("top-right"), "Existing abbreviated option uses native defaults")
    expect(TouchOverlayConfig("top-right", 30f, 85f),
        TouchOverlayConfig.parseVisible("top-right NaN Infinity"), "Nonfinite percentages do not enter sliders")
    for (hidden in listOf(null, "", "off", "false", "0", "no", "unsupported 30 60")) {
        expect(null, TouchOverlayConfig.parseVisible(hidden), "Hidden or invalid option $hidden")
    }
    println("TouchOverlayPreferences: all regression checks passed")
}

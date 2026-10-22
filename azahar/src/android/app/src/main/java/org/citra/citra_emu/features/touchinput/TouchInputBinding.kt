// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version.
// Refer to the license.txt file included.

package org.citra.citra_emu.features.touchinput

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.roundToInt
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A controller key or axis mapped to normalized (x, y) coordinates on the 3DS bottom screen.
 */
data class TouchInputBinding(
    val keyCode: Int = DEFAULT_UNBOUND,
    val axis: Int = DEFAULT_UNBOUND,
    val positive: Boolean = true,
    val analog: Boolean = false,
    val threshold: Float = DEFAULT_THRESHOLD,
    val x: Float = DEFAULT_COORDINATE,
    val y: Float = DEFAULT_COORDINATE,
    /** An on-screen overlay button (NativeLibrary.ButtonType id), instead of a controller input. */
    val overlayButton: Int = DEFAULT_UNBOUND
) {
    val isOverlayBinding: Boolean
        get() = overlayButton != DEFAULT_UNBOUND

    val isAxisBinding: Boolean
        get() = axis != DEFAULT_UNBOUND

    val isKeyBinding: Boolean
        get() = keyCode != DEFAULT_UNBOUND

    /** Whether this binding listens to the same physical input as [other]. */
    fun hasSameInputAs(other: TouchInputBinding): Boolean =
        overlayButton == other.overlayButton &&
            keyCode == other.keyCode &&
            axis == other.axis &&
            positive == other.positive &&
            analog == other.analog

    /** Readable name of the bound input, e.g. "Volume Up" or "Left Stick X +". */
    fun displayName(): String =
        if (isOverlayBinding) {
            CitraApplication.appContext.getString(
                R.string.touch_input_overlay_button_format,
                overlayLabel(overlayButton)
            )
        } else if (isAxisBinding) {
            val name = MotionEvent.axisToString(axis).toDisplayName(AXIS_PREFIX)
            "$name ${if (positive) "+" else "\u2212"}"
        } else {
            KeyEvent.keyCodeToString(keyCode).toDisplayName(KEYCODE_PREFIX)
        }

    /** Touch position as percentages, e.g. "X 90% · Y 90%". */
    fun positionLabel(): String = formatPosition(x, y)

    /** Compact name for the on-screen pill overlay. Keep it short; the pill grows with text. */
    fun shortLabel(): String =
        if (isOverlayBinding) {
            "T" + overlayLabel(overlayButton)
        } else if (isAxisBinding) {
            val name = MotionEvent.axisToString(axis).removePrefix(AXIS_PREFIX).toShortName()
            "$name ${if (positive) "+" else "−"}"
        } else {
            KeyEvent.keyCodeToString(keyCode).removePrefix(KEYCODE_PREFIX).toShortName()
        }

    fun toJson(): JSONObject =
        JSONObject().apply {
            put(JSON_KEY_CODE, keyCode)
            put(JSON_AXIS, axis)
            put(JSON_POSITIVE, positive)
            put(JSON_ANALOG, analog)
            put(JSON_THRESHOLD, threshold)
            put(JSON_X, x)
            put(JSON_Y, y)
            put(JSON_OVERLAY_BUTTON, overlayButton)
        }

    companion object {
        const val DEFAULT_UNBOUND = -1
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_COORDINATE = 0.5f

        private const val TAG = "TouchInputBinding"

        private const val KEYCODE_PREFIX = "KEYCODE_"
        private const val AXIS_PREFIX = "AXIS_"

        private const val JSON_KEY_CODE = "keyCode"
        private const val JSON_AXIS = "axis"
        private const val JSON_POSITIVE = "positive"
        private const val JSON_ANALOG = "analog"
        private const val JSON_THRESHOLD = "threshold"
        private const val JSON_X = "x"
        private const val JSON_Y = "y"
        private const val JSON_OVERLAY_BUTTON = "overlayButton"

        fun overlayLabel(id: Int): String = TouchPointButtons.numberOf(id).toString()

        fun formatPosition(x: Float, y: Float): String =
            "X ${(x * 100).roundToInt()}% \u00B7 Y ${(y * 100).roundToInt()}%"

        fun fromJson(json: JSONObject): TouchInputBinding =
            TouchInputBinding(
                keyCode = json.optInt(JSON_KEY_CODE, DEFAULT_UNBOUND),
                axis = json.optInt(JSON_AXIS, DEFAULT_UNBOUND),
                positive = json.optBoolean(JSON_POSITIVE, true),
                analog = json.optBoolean(JSON_ANALOG, false),
                threshold = json.optDouble(JSON_THRESHOLD, DEFAULT_THRESHOLD.toDouble()).toFloat(),
                x = json.optDouble(JSON_X, DEFAULT_COORDINATE.toDouble()).toFloat(),
                y = json.optDouble(JSON_Y, DEFAULT_COORDINATE.toDouble()).toFloat(),
                overlayButton = json.optInt(JSON_OVERLAY_BUTTON, DEFAULT_UNBOUND)
            )

        fun listToJson(bindings: List<TouchInputBinding>): String {
            val array = JSONArray()
            bindings.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        /** Parses a JSON array of bindings, returning an empty list if it is missing or invalid. */
        fun listFromJson(json: String?): List<TouchInputBinding> {
            if (json.isNullOrEmpty()) {
                return emptyList()
            }

            return try {
                val array = JSONArray(json)
                List(array.length()) { fromJson(array.getJSONObject(it)) }
            } catch (e: JSONException) {
                Log.e(TAG, "Failed to parse touch input bindings", e)
                emptyList()
            }
        }
    }
}

private fun String.toDisplayName(prefix: String): String =
    removePrefix(prefix)
        .split('_')
        .joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }

/**
 * A short but still-readable pill label. Recognized d-pad and volume names get a glyph or a plain
 * word ("DPAD_UP" -> "↑", "VOLUME_DOWN" -> "Vol −"); a single word like "BUTTON_A" loses its
 * "BUTTON_" prefix and stays as "A". Anything else is title-cased ("THUMBL" -> "Thumbl") unless
 * that would run long, in which case it falls back to initials ("SELECT" alone never does, but a
 * rare multi-word axis name like "LEFT_TRIGGER" would become "LT").
 */
private fun String.toShortName(): String {
    KNOWN_SHORT_NAMES[this]?.let { return it }

    val words = removePrefix("BUTTON_").split('_').filter { it.isNotEmpty() }
    val titleCased = words.joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
    return if (titleCased.length <= SHORT_NAME_MAX_CHARS) {
        titleCased
    } else {
        words.mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
    }
}

private const val SHORT_NAME_MAX_CHARS = 8

// Names where a plain word or symbol reads far more clearly than the generic title-casing above.
private val KNOWN_SHORT_NAMES = mapOf(
    "DPAD_UP" to "\u2191",
    "DPAD_DOWN" to "\u2193",
    "DPAD_LEFT" to "\u2190",
    "DPAD_RIGHT" to "\u2192",
    "VOLUME_UP" to "Vol \u2191",
    "VOLUME_DOWN" to "Vol \u2193",
    "BUTTON_THUMBL" to "L3",
    "BUTTON_THUMBR" to "R3",
    "BUTTON_L1" to "L",
    "BUTTON_R1" to "R",
    "BUTTON_L2" to "ZL",
    "BUTTON_R2" to "ZR"
)

// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version.
// Refer to the license.txt file included.

package org.citra.citra_emu.features.touchinput

import android.content.SharedPreferences
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.preference.PreferenceManager
import kotlin.math.abs
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.utils.ControllerMappingHelper

/**
 * Holds the active touch input bindings and turns controller input into touchscreen events.
 *
 * The active bindings are persisted so the emulator can restore them on launch. Input events
 * are expected to arrive on the main thread.
 */
object TouchInputBindingManager {
    private const val TAG = "TouchInputBinding"
    private const val PREF_KEY = "TouchscreenBindings"

    private const val AXIS_DEADZONE = 0.15f
    private const val TOUCH_PROXIMITY_THRESHOLD = 0.02f

    // The bottom screen rectangle inside NativeLibrary.getFramebufferLayout()
    private const val LAYOUT_MIN_SIZE = 6
    private const val LAYOUT_LEFT = 2
    private const val LAYOUT_TOP = 3
    private const val LAYOUT_RIGHT = 4
    private const val LAYOUT_BOTTOM = 5

    private data class AxisKey(val axis: Int, val positive: Boolean, val analog: Boolean)

    private val preferences: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

    private val bindings = mutableListOf<TouchInputBinding>()
    private val pressedKeys = mutableSetOf<Int>()
    private val pressedOverlayButtons = mutableSetOf<Int>()
    private val pressedAxes = mutableMapOf<AxisKey, Boolean>()

    init {
        bindings.addAll(TouchInputBinding.listFromJson(preferences.getString(PREF_KEY, null)))
    }

    fun getBindings(): List<TouchInputBinding> = bindings.toList()

    fun getBindingAt(x: Float, y: Float): TouchInputBinding? =
        bindings.firstOrNull {
            abs(it.x - x) < TOUCH_PROXIMITY_THRESHOLD && abs(it.y - y) < TOUCH_PROXIMITY_THRESHOLD
        }

    /** Adds [binding], replacing any existing binding for the same physical input. */
    fun addBinding(binding: TouchInputBinding) {
        val index = bindings.indexOfFirst { it.hasSameInputAs(binding) }
        if (index == -1) {
            bindings.add(binding)
        } else {
            bindings[index] = binding
        }
        saveBindings()
    }

    /** Replaces [oldBinding] with [newBinding], keeping its position in the list. */
    fun replaceBinding(oldBinding: TouchInputBinding, newBinding: TouchInputBinding) {
        val index = bindings.indexOf(oldBinding)
        if (index == -1) {
            addBinding(newBinding)
            return
        }
        bindings[index] = newBinding
        saveBindings()
    }

    fun removeBinding(binding: TouchInputBinding) {
        bindings.remove(binding)
        saveBindings()
    }

    fun clearBindings() {
        bindings.clear()
        resetInputState()
        preferences.edit().remove(PREF_KEY).apply()
    }

    /** Replaces all active bindings, e.g. when switching profiles. */
    fun setBindings(newBindings: List<TouchInputBinding>) {
        bindings.clear()
        bindings.addAll(newBindings)
        resetInputState()
        saveBindings()
    }

    fun handleKeyDown(event: KeyEvent): Boolean {
        if (!isBindableKeyEvent(event, KeyEvent.ACTION_DOWN)) return false
        if (!pressedKeys.add(event.keyCode)) return false

        val matches = getBindingsForKey(event.keyCode)
        if (matches.isEmpty()) {
            pressedKeys.remove(event.keyCode)
            return false
        }

        matches.forEach { sendTouch(it, true) }
        return true
    }

    fun handleKeyUp(event: KeyEvent): Boolean {
        if (!isBindableKeyEvent(event, KeyEvent.ACTION_UP)) return false
        if (!pressedKeys.remove(event.keyCode)) return false

        val matches = getBindingsForKey(event.keyCode)
        matches.forEach { sendTouch(it, false) }
        return matches.isNotEmpty()
    }

    /**
     * Called when an on-screen overlay button changes state. Presses the bound touch point while
     * the button is held. The button still does its normal job in the game as well.
     */
    fun handleOverlayButton(id: Int, pressed: Boolean) {
        if (bindings.none { it.isOverlayBinding && it.overlayButton == id }) return

        val changed = if (pressed) pressedOverlayButtons.add(id) else pressedOverlayButtons.remove(id)
        if (!changed) return

        bindings
            .filter { it.isOverlayBinding && it.overlayButton == id }
            .forEach { sendTouch(it, pressed) }
    }

    fun handleAxisMotion(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false

        var handled = false

        for (binding in bindings) {
            if (!binding.isAxisBinding) continue

            val value = ControllerMappingHelper.scaleAxis(
                event.device,
                binding.axis,
                event.getAxisValue(binding.axis)
            )
            val pressed = isAxisPressed(binding, value)
            val axisKey = AxisKey(binding.axis, binding.positive, binding.analog)

            if (pressedAxes[axisKey] != pressed) {
                pressedAxes[axisKey] = pressed
                sendTouch(binding, pressed)
                handled = true
            }
        }

        return handled
    }

    fun sendTouch(binding: TouchInputBinding, pressed: Boolean) {
        val layout = NativeLibrary.getFramebufferLayout()
        if (layout.size < LAYOUT_MIN_SIZE) {
            Log.w(TAG, "Framebuffer layout is too small, dropping touch event")
            return
        }

        val left = layout[LAYOUT_LEFT].toFloat()
        val top = layout[LAYOUT_TOP].toFloat()
        val right = layout[LAYOUT_RIGHT].toFloat()
        val bottom = layout[LAYOUT_BOTTOM].toFloat()

        NativeLibrary.onTouchEvent(
            left + binding.x * (right - left),
            top + binding.y * (bottom - top),
            pressed
        )
    }

    private fun isBindableKeyEvent(event: KeyEvent, action: Int): Boolean =
        event.action == action &&
            event.keyCode != KeyEvent.KEYCODE_BACK &&
            !ControllerMappingHelper.shouldKeyBeIgnored(event.device, event.keyCode)

    private fun getBindingsForKey(keyCode: Int): List<TouchInputBinding> =
        bindings.filter { it.isKeyBinding && it.keyCode == keyCode }

    private fun isAxisPressed(binding: TouchInputBinding, value: Float): Boolean =
        when {
            binding.analog -> abs(value) > binding.threshold
            binding.positive -> value > AXIS_DEADZONE
            else -> value < -AXIS_DEADZONE
        }

    private fun resetInputState() {
        pressedKeys.clear()
        pressedOverlayButtons.clear()
        pressedAxes.clear()
    }

    private fun saveBindings() {
        preferences.edit()
            .putString(PREF_KEY, TouchInputBinding.listToJson(bindings))
            .apply()
    }
}

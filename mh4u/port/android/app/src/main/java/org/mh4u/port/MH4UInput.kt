package org.mh4u.port

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File

/**
 * Gamepads, keyboards and mice (any device Android lists: Bluetooth, USB, built in) with combos
 * and per-device bindings. Events go to the native engine (src/input_map.cpp), which turns them
 * into 3DS buttons and sticks and hands them to the game together with the on-screen controls.
 *
 * A binding is "all of these held -> all of these 3DS buttons pressed": one key can press
 * several buttons (a combo), several keys can press one (a chord). A source can name one device
 * (its descriptor) or any.
 */
object MH4UInput {
    init { System.loadLibrary("mh4u-android") }

    // 3DS button bits, as Port::InputMap::Button
    const val A = 1 shl 0; const val B = 1 shl 1; const val X = 1 shl 2; const val Y = 1 shl 3
    const val L = 1 shl 4; const val R = 1 shl 5; const val ZL = 1 shl 6; const val ZR = 1 shl 7
    const val START = 1 shl 8; const val SELECT = 1 shl 9
    const val UP = 1 shl 10; const val DOWN = 1 shl 11; const val LEFT = 1 shl 12; const val RIGHT = 1 shl 13
    const val HOME = 1 shl 14
    /** Not 3DS buttons: actions of the app, bound and put on screen like buttons. */
    const val SWAP_SCREENS = 1 shl 16   // swaps the top and bottom screen when pressed
    const val TURBO = 1 shl 17          // while held, the turbo buttons (Controls) pulse

    /** Buttons in the order the screens list them. */
    val buttons = listOf(
        "A" to A, "B" to B, "X" to X, "Y" to Y, "L" to L, "R" to R, "ZL" to ZL, "ZR" to ZR,
        "START" to START, "SELECT" to SELECT, "D-pad up" to UP, "D-pad down" to DOWN,
        "D-pad left" to LEFT, "D-pad right" to RIGHT,
        "Swap screens" to SWAP_SCREENS, "Turbo (hold)" to TURBO,
    )

    /** The 3DS's own buttons: what Turbo can pulse. */
    val padButtons get() = buttons.filter { it.second < SWAP_SCREENS }

    private var file: File? = null

    fun init(dir: File) {
        file = File(dir, "mh4u-input.ini")
        val text = file?.takeIf { it.exists() }?.readText()
        // a file with one bad line still keeps its good ones; the defaults only fill an empty set
        if (text != null) nativeLoad(text)
        if (text == null || bindings().isEmpty()) nativeLoadDefaults()
        applyTuning()
    }

    fun save() { file?.writeText(nativeSave()) }

    /** Lets go of everything (the next motion event sends every axis again). */
    fun releaseAll() { profiles.values.forEach { it.last.fill(Float.NaN) }; nativeReleaseAll() }

    /** The actions pressed since the last call (bit 0: swap the screens), for the activity to act on. */
    fun takeActions(): Int = nativeTakeActions()

    /** The stick dead zone and the press point of axes used as buttons (Controls > Tuning). */
    fun applyTuning() = nativeSetTuning(
        Prefs.getInt("Controls", "deadzone", 12).coerceIn(0, 50),
        Prefs.getInt("Controls", "press_point", 50).coerceIn(15, 95),
    )

    /** The buttons Turbo makes pulse, and how many times a second. */
    fun setTurbo(mask: Int, hertz: Int) = nativeSetTurbo(mask, hertz)

    // ── events from the activity ──

    private fun id(d: InputDevice?) = d?.descriptor ?: ""

    /** From dispatchKeyEvent; true when the event was taken. */
    fun onKey(e: KeyEvent): Boolean {
        val src = e.source
        val pad = src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD ||
            src and InputDevice.SOURCE_KEYBOARD == InputDevice.SOURCE_KEYBOARD ||
            src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        if (!pad) return false
        // the system's own Back (not a pad's B) and volume keys stay the system's
        if (e.keyCode == KeyEvent.KEYCODE_BACK && src and InputDevice.SOURCE_GAMEPAD == 0) return false
        if (e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (e.repeatCount > 0) return true
        nativeOnKey(id(e.device), e.keyCode, e.action == KeyEvent.ACTION_DOWN)
        return true
    }

    /**
     * What one device's axes are, found from the axes it reports and where they rest (like the
     * controller databases modern emulators use, but worked out, so any pad works):
     *  - the right stick is Z/RZ or RX/RY, whichever pair the pad does not use for its triggers;
     *  - triggers that are not Android's LTRIGGER/RTRIGGER (Z/RZ or RX/RY on many pads, BRAKE/GAS
     *    on others) are sent as LTRIGGER/RTRIGGER;
     *  - a trigger that rests at -1 (range -1..1) or +1 is brought to 0..1, so it is not "held"
     *    the moment the pad connects (which also spoiled binding by moving an axis).
     * Everything else (the left stick, the hat, extra axes) goes on as it is. The native engine's
     * defaults then read: X/Y circle pad, Z/RZ C-stick, HAT d-pad, LTRIGGER/RTRIGGER ZL/ZR.
     */
    private class Profile(val axes: IntArray, val canon: IntArray, val mode: IntArray) {
        val last = FloatArray(axes.size) { Float.NaN }
    }

    private val profiles = HashMap<String, Profile>()

    private const val REST_LOW = -0.85f

    private fun buildProfile(e: MotionEvent, dev: InputDevice): Profile {
        val have = dev.motionRanges.filter { it.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK }
            .map { it.axis }.toSet()
        fun has(vararg a: Int) = a.all { it in have }
        fun atMin(a: Int) = e.getAxisValue(a) < REST_LOW
        val hasLR = has(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER) || has(MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS)
        val zPair = MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        val rPair = MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        val p1 = has(zPair.first, zPair.second)
        val p2 = has(rPair.first, rPair.second)
        var stick: Pair<Int, Int>? = null
        var triggers: Pair<Int, Int>? = null
        fun restsLow(p: Pair<Int, Int>) = atMin(p.first) || atMin(p.second)
        when {
            p1 && p2 -> when {
                hasLR -> stick = zPair                       // the other pair is a duplicate of the triggers
                restsLow(rPair) -> { stick = zPair; triggers = rPair }
                else -> { stick = rPair; triggers = zPair }  // Z/RZ as triggers, RX/RY the right stick
            }
            p1 -> if (!hasLR && restsLow(zPair)) triggers = zPair else stick = zPair
            p2 -> if (!hasLR && restsLow(rPair)) triggers = rPair else stick = rPair
        }
        val axes = ArrayList<Int>(); val canon = ArrayList<Int>(); val mode = ArrayList<Int>()
        // BRAKE/GAS triggers (and nothing on LTRIGGER/RTRIGGER) are the pad's L2/R2
        val brakeGas = !has(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER) && has(MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS)
        for (a in have.sorted()) {
            var c = a
            when {
                brakeGas && a == MotionEvent.AXIS_BRAKE -> c = MotionEvent.AXIS_LTRIGGER
                brakeGas && a == MotionEvent.AXIS_GAS -> c = MotionEvent.AXIS_RTRIGGER
                stick != null && a == stick.first -> c = MotionEvent.AXIS_Z
                stick != null && a == stick.second -> c = MotionEvent.AXIS_RZ
                triggers != null && !hasLR && a == triggers.first -> c = MotionEvent.AXIS_LTRIGGER
                triggers != null && !hasLR && a == triggers.second -> c = MotionEvent.AXIS_RTRIGGER
            }
            val isTrigger = c == MotionEvent.AXIS_LTRIGGER || c == MotionEvent.AXIS_RTRIGGER ||
                c == MotionEvent.AXIS_BRAKE || c == MotionEvent.AXIS_GAS
            val rest = e.getAxisValue(a)
            // 1: rests at -1, 2: rests at +1; both end up 0..1
            val m = if (!isTrigger) 0 else if (rest < REST_LOW) 1 else if (rest > -REST_LOW) 2 else 0
            axes.add(a); canon.add(c); mode.add(m)
        }
        return Profile(axes.toIntArray(), canon.toIntArray(), mode.toIntArray())
    }

    /** From dispatchGenericMotionEvent. */
    fun onMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            e.action != MotionEvent.ACTION_MOVE) return false
        val dev = e.device ?: return false
        val key = dev.descriptor
        val p = profiles.getOrPut(key) { buildProfile(e, dev) }
        for (i in p.axes.indices) {
            var v = e.getAxisValue(p.axes[i])
            if (p.mode[i] == 1) v = ((v + 1f) / 2f).coerceIn(0f, 1f)
            else if (p.mode[i] == 2) v = ((1f - v) / 2f).coerceIn(0f, 1f)
            if (v != p.last[i]) {
                p.last[i] = v
                nativeOnAxis(key, p.canon[i], v)
            }
        }
        return true
    }

    /** An InputDeviceListener's onInputDeviceRemoved. */
    fun deviceRemoved(descriptor: String) { profiles.remove(descriptor); nativeOnDeviceRemoved(descriptor) }

    // ── on-screen controls ──

    /** What the on-screen controls hold: 3DS buttons (a combo button holds several) and the sticks. */
    fun setOverlay(buttons: Int, circleX: Float, circleY: Float, cX: Float, cY: Float) =
        nativeSetOverlay(buttons, circleX, circleY, cX, cY)

    // ── binding ──

    /** The next keys held together and released become one chord: see [learned]. */
    fun startLearn() { profiles.values.forEach { it.last.fill(Float.NaN) }; nativeStartLearn() }
    fun cancelLearn() = nativeCancelLearn()
    fun learning() = nativeLearning()
    /** The chord learned, as text, or null while nothing is complete. */
    fun learned(): String? = nativeTakeLearned()

    /** Rebinds [mask] (one button, or several for a combo) to [chord]. */
    fun bind(mask: Int, chord: String): Boolean {
        nativeRemoveFor(mask)
        return nativeAddBinding(mask, chord).also { if (it) save() }
    }

    /** A further source for a button or combo (a pad button and a key, say). */
    fun addAlternative(mask: Int, chord: String): Boolean =
        nativeAddBinding(mask, chord).also { if (it) save() }

    fun unbind(mask: Int) { nativeRemoveFor(mask); save() }

    fun resetToDefaults() { nativeLoad(""); nativeLoadDefaults(); save() }

    /** One line per binding of the engine: which buttons, and what presses them. */
    data class Binding(val mask: Int, val chord: String, val text: String)

    fun bindings(): List<Binding> =
        nativeSave().lineSequence().mapNotNull { line ->
            val l = line.trim()
            if (!l.startsWith("bind")) return@mapNotNull null
            val body = l.substringAfter("=").trim()
            val names = body.substringBefore("<=").trim()
            val chord = body.substringAfter("<=").trim()
            var mask = 0
            for (n in names.split(",")) mask = mask or buttonFromName(n.trim())
            Binding(mask, chord, describe(chord))
        }.toList()

    private fun buttonFromName(n: String): Int = when (n.uppercase()) {
        "A" -> A; "B" -> B; "X" -> X; "Y" -> Y; "L" -> L; "R" -> R; "ZL" -> ZL; "ZR" -> ZR
        "START" -> START; "SELECT" -> SELECT; "UP" -> UP; "DOWN" -> DOWN; "LEFT" -> LEFT
        "RIGHT" -> RIGHT; "HOME" -> HOME; "SWAPSCREENS" -> SWAP_SCREENS; "TURBO" -> TURBO; else -> 0
    }

    fun maskName(mask: Int): String {
        val names = buttons.filter { mask and it.second != 0 }.map { it.first }
        return if (names.isEmpty()) "?" else names.joinToString(" + ")
    }

    /** "key:96@desc + axis+:18" as "A button + Right trigger (Xbox controller)". */
    fun describe(chord: String): String = chord.split("+").let { _ ->
        chord.split(" + ").joinToString(" + ") { part ->
            val dev = part.substringAfter("@", "")
            val src = part.substringBefore("@").trim()
            val what = when {
                src.startsWith("key:") -> KeyEvent.keyCodeToString(src.substring(4).toIntOrNull() ?: 0).removePrefix("KEYCODE_")
                    .replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
                src.startsWith("axis+:") -> MotionEvent.axisToString(src.substring(6).toIntOrNull() ?: 0).removePrefix("AXIS_").lowercase() + " +"
                src.startsWith("axis-:") -> MotionEvent.axisToString(src.substring(6).toIntOrNull() ?: 0).removePrefix("AXIS_").lowercase() + " -"
                else -> src
            }
            if (dev.isEmpty()) what else "$what (${deviceName(dev)})"
        }
    }

    private fun deviceName(descriptor: String): String =
        InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { it.descriptor == descriptor }?.name ?: "disconnected device"

    /** Devices that can press 3DS buttons right now. */
    fun devices(): List<String> =
        InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.sources and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_KEYBOARD) != 0 && !it.isVirtual }
            .map { it.name }.toList()

    @JvmStatic private external fun nativeLoadDefaults()
    @JvmStatic private external fun nativeOnKey(device: String, code: Int, down: Boolean)
    @JvmStatic private external fun nativeOnAxis(device: String, axis: Int, value: Float)
    @JvmStatic private external fun nativeSetTuning(deadzonePercent: Int, pressPercent: Int)
    @JvmStatic private external fun nativeOnDeviceRemoved(device: String)
    @JvmStatic private external fun nativeReleaseAll()
    @JvmStatic private external fun nativeTakeActions(): Int
    @JvmStatic private external fun nativeSetTurbo(mask: Int, hertz: Int)
    @JvmStatic private external fun nativeSetOverlay(buttons: Int, cx: Float, cy: Float, sx: Float, sy: Float)
    @JvmStatic private external fun nativeStartLearn()
    @JvmStatic private external fun nativeCancelLearn()
    @JvmStatic private external fun nativeLearning(): Boolean
    @JvmStatic private external fun nativeTakeLearned(): String?
    @JvmStatic private external fun nativeAddBinding(mask: Int, chord: String): Boolean
    @JvmStatic private external fun nativeRemoveFor(mask: Int)
    @JvmStatic private external fun nativeLoad(text: String): Boolean
    @JvmStatic private external fun nativeSave(): String
}

package org.mh4u.port

import android.content.res.Configuration
import android.os.Bundle
import android.view.*
import android.view.Choreographer
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import org.mh4u.port.overlay.Control
import org.mh4u.port.overlay.OverlayLayout
import org.mh4u.port.overlay.OverlayView
import org.mh4u.port.ui.*

/**
 * The game: a surface the core draws on, the on-screen controls over it, and the in-game menu
 * (the PC app's Esc menu: Game, Display, Audio, Controls, Multiplayer). The menu mutes the
 * game's input rather than pausing it, as on PC, so a room keeps running.
 */
class EmulationActivity : ComponentActivity(), SurfaceHolder.Callback {

    private lateinit var overlay: OverlayView
    private var gameThread: Thread? = null
    private var menuOpen by mutableStateOf(false)
    private var editing by mutableStateOf(false)
    private val chat = ChatModel()
    private var chatOpen by mutableStateOf(false)
    private var selected by mutableStateOf<Control?>(null)
    /** Bumped when the selected control changes in a way Compose can't see (its fields aren't state). */
    private var editTick by mutableIntStateOf(0)
    private var ended: String? = null
    /** Whose layout [overlay] holds: portrait and landscape each have their own. */
    private var layoutPortrait = false

    /// The display's frame callback: with the OpenGL (GLES) renderer the game's frames are
    /// presented here, on the UI thread, in step with the screen (the Vulkan renderer presents
    /// on the game thread itself, and this does nothing that matters). Started with the game.
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (gameThread?.isAlive == true) {
                Native.doFrame()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Keyboard.host = this

        val game = intent.getStringExtra(EXTRA_GAME) ?: return finish()
        val root = FrameLayout(this)
        val surface = SurfaceView(this).also { it.holder.addCallback(this) }
        layoutPortrait = isPortrait()
        overlay = OverlayView(this, OverlayLayout.load(layoutPortrait)).apply {
            onMenu = { openMenu() }
            onSelect = { c -> this@EmulationActivity.selected = c }   // not the view's own `selected`: that loops
        }
        applyOverlayPrefs()
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.addView(ComposeView(this).apply { setContent { Mh4uTheme { Hud() } } }, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (chatOpen) closeChat() else if (menuOpen) closeMenu() else openMenu() }
        })

        Prefs.apply()
        gameThread = Thread({
            val message = Native.run(game)
            runOnUiThread {
                ended = message
                if (!isFinishing) { setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_MESSAGE, message)); finish() }
            }
        }, "mh4u-game").also { it.start() }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun isPortrait() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    private fun applyOverlayPrefs() {
        val padConnected = InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let { d ->
                d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD && !d.isVirtual
            } == true
        }
        val hideForPad = Prefs.getBool("Android", "overlay_autohide", false) && padConnected
        overlay.controlsVisible = Prefs.getBool("Android", "overlay", true) && !hideForPad
        // opacity and vibration are the same in both orientations (OverlayLayout.saveShared); the
        // landscape file is where the settings page reads them from, so it is the one to go by
        OverlayLayout.load(false).let { overlay.layout.opacity = it.opacity; overlay.layout.haptics = it.haptics }
    }

    /**
     * The window turned (this activity isn't recreated for it): the other orientation's controls
     * take over. What was being arranged stays with the orientation it was arranged in.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val portrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        if (portrait == layoutPortrait) return
        saveLayout()   // whatever the old orientation's controls were, they stay with it
        overlay.releaseEverything()
        layoutPortrait = portrait
        overlay.selected = null
        overlay.layout = OverlayLayout.load(portrait)
        applyOverlayPrefs()
        overlay.invalidate()
    }

    // ── menu ──

    private fun openMenu() { chatOpen = false; menuOpen = true; overlay.shown = false; MH4UInput.releaseAll() }
    private fun closeMenu() { menuOpen = false; overlay.shown = true; applyOverlayPrefs() }

    // ── chat: the input bar mutes the controls like the menu does (the log and the button don't) ──
    private fun openChat() { if (menuOpen || editing) return; chatOpen = true; overlay.shown = false; MH4UInput.releaseAll() }
    private fun closeChat() { if (!chatOpen) return; chatOpen = false; overlay.shown = true; applyOverlayPrefs() }

    @Composable
    private fun Hud() {
        var fps by remember { mutableStateOf("") }
        var notice by remember { mutableStateOf("") }
        var counter by remember { mutableStateOf(Prefs.getApp("Display", "fps_counter", "off") == "on") }
        // the menu changes it while this loop runs: re-read, don't keep the value from the start
        LaunchedEffect(menuOpen) { counter = Prefs.getApp("Display", "fps_counter", "off") == "on" }
        // the Swap screens action: a bound key or an on-screen button; it fires once when pressed
        LaunchedEffect(Unit) {
            while (true) {
                val actions = MH4UInput.takeActions()
                if (actions and 1 != 0 && !menuOpen && !editing) Prefs.toggleSwap()
                delay(40)
            }
        }
        LaunchedEffect(Unit) {
            delay(2500); notice = Native.notice()
            while (true) {
                val p = Native.perf()
                counter = Prefs.getApp("Display", "fps_counter", "off") == "on"
                if (counter && p.size == 2) {
                    var line = "%.0f fps  %.0f%%".format(p[0], p[1])
                    // the adaptive governor, when it holds the resolution: what it picked, and
                    // how warm the phone is (it steps the resolution with that)
                    runCatching { org.json.JSONObject(Native.adaptiveStatus()) }.getOrNull()?.let { a ->
                        if (a.optBoolean("active")) {
                            line += if (a.optInt("current") == 0) "  ·  native"
                                    else "  ·  %.1fx".format(a.optInt("current") / 100.0)
                            val t = a.optDouble("thermal", -1.0)
                            if (t >= 1.0) line += "  %.0fC".format(t)
                        }
                    }
                    fps = line
                }
                delay(1000)
            }
        }
        // The room's outcome over the game: a join that failed, a room that was left or lost, a
        // join that worked. It is also kept for the launcher (Multiplayer / last_result), which
        // can't join before the game runs and so can't say anything sooner.
        var roomNote by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            var before = ""
            var told = ""
            while (true) {
                val st = withContext(Dispatchers.IO) { runCatching { org.json.JSONObject(Native.roomStatus()) }.getOrNull() }
                if (st != null) {
                    val phase = st.optString("phase")
                    val error = st.optString("error")
                    val hint = st.optString("hint")
                    var note = ""
                    if (phase == "room" && before != "room") {
                        val name = st.optString("room_name").ifEmpty { "${st.optString("host")}:${st.optInt("port")}" }
                        note = if (st.optBoolean("hosting")) "Hosting $name" else "Joined $name"
                        told = ""
                        Prefs.setApp("Multiplayer", "last_result", "OK: $note")
                    } else if (phase != "busy" && phase != "room" && error.isNotEmpty() && error != told) {
                        told = error
                        note = error + if (hint.isEmpty()) "" else "\n$hint"
                        Prefs.setApp("Multiplayer", "last_result", "Failed: " + note.replace("\n", " "))
                    }
                    if (phase == "busy") told = "" else before = phase   // a new try may fail the same way
                    if (note.isNotEmpty()) roomNote = note
                }
                delay(1000)
            }
        }
        LaunchedEffect(roomNote) { if (roomNote.isNotEmpty()) { delay(9000); roomNote = "" } }
        Box(Modifier.fillMaxSize()) {
            if (roomNote.isNotEmpty() && !menuOpen)
                Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp).clickable { roomNote = "" }) {
                    Notice(roomNote)
                }
            if (counter && fps.isNotEmpty() && !menuOpen)
                Text(fps, color = Mh4uColors.Amber, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
                    .background(Color(0x99121214)).padding(horizontal = 6.dp, vertical = 2.dp))
            if (notice.isNotEmpty() && !menuOpen)
                Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp).clickable { notice = "" }) { Notice(notice + "\n(tap to dismiss)") }
            ChatOverlay(chat, hidden = menuOpen || editing, inputOpen = chatOpen, onOpen = { openChat() }, onClose = { closeChat() })
            if (editing) EditBar()
            if (menuOpen) Menu()
        }
    }

    @Composable
    private fun Menu() {
        var tab by remember { mutableIntStateOf(0) }
        val titles = listOf("Game", "Display", "Audio", "Controls", "Chat", "Cheats", "Multiplayer")
        Surface(Modifier.fillMaxSize(), color = Mh4uColors.WindowBg.copy(alpha = 0.97f)) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().background(Mh4uColors.TitleBgActive).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("MH4U", color = Mh4uColors.Amber, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    QuietButton("Quest map") { Native.toggleQuestMap() }
                    Spacer(Modifier.width(8.dp))
                    AmberButton("Resume") { closeMenu() }
                    Spacer(Modifier.width(8.dp))
                    QuietButton("Quit") { Native.stop() }
                }
                Tabs(titles, tab) { tab = it }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> GamePage()
                        1 -> DisplayPage()
                        2 -> AudioPage()
                        3 -> ControlsPage(onEditOverlay = { menuOpen = false; startEditing() }, onOverlayChanged = { applyOverlayPrefs() })
                        4 -> ChatPage()
                        5 -> CheatsPage()
                        else -> MultiplayerPage()
                    }
                }
            }
        }
    }

    // ── editing the on-screen controls ──

    private fun startEditing() { overlay.selected = null; overlay.shown = true; overlay.controlsVisible = true; overlay.editing = true; editing = true }
    private fun stopEditing() {
        overlay.editing = false; editing = false; overlay.layout.save(layoutPortrait); overlay.selected = null; closeMenu()
    }

    /**
     * The editor's bar. It stands over part of the controls it edits, so it moves out of the way:
     * to the half of the screen the selected control isn't in, by hand with its grip, or with the
     * arrows button. Combo buttons are made in a window of their own, so the bar stays small.
     */
    @Composable
    private fun EditBar() {
        var comboName by remember { mutableStateOf("") }
        var mask by remember { mutableIntStateOf(0) }
        var latch by remember { mutableStateOf(false) }
        var adding by remember { mutableStateOf(false) }
        var atTop by remember { mutableStateOf(false) }
        var dx by remember { mutableFloatStateOf(0f) }
        var dy by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(selected) { selected?.let { atTop = it.y > 0.5f; dx = 0f; dy = 0f } }
        Box(Modifier.fillMaxSize()) {
            Surface(
                Modifier.align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
                    .then(if (atTop) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding())
                    .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
                    .padding(8.dp).widthIn(max = 420.dp),
                color = Mh4uColors.PopupBg.copy(alpha = .95f),
                shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, Mh4uColors.Border),
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("☰ move", color = Mh4uColors.Amber, fontSize = 13.sp,
                            modifier = Modifier.pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); dx += drag.x; dy += drag.y } }
                                .background(Mh4uColors.Button, MaterialTheme.shapes.small).padding(horizontal = 10.dp, vertical = 8.dp))
                        Text(selected?.let { it.label.ifEmpty { it.kind.name.lowercase().replace('_', ' ') } }
                            ?: "Drag a control. Pinch to resize.", fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        QuietButton("⇅") { atTop = !atTop; dx = 0f; dy = 0f }
                        AmberButton("Done") { stopEditing() }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietButton("−", enabled = selected != null) { selected?.let { it.size = (it.size * 0.9f).coerceAtLeast(0.04f); overlay.invalidate() } }
                        QuietButton("+", enabled = selected != null) { selected?.let { it.size = (it.size * 1.1f).coerceAtMost(0.5f); overlay.invalidate() } }
                        QuietButton(if (editTick >= 0 && selected?.visible == false) "Show" else "Hide", enabled = selected != null) {
                            selected?.let { it.visible = !it.visible; editTick++; overlay.invalidate() }
                        }
                        QuietButton("Delete", enabled = selected?.kind == Control.Kind.COMBO) {
                            selected?.let { overlay.layout.controls.remove(it); overlay.selected = null; overlay.invalidate() }
                        }
                        QuietButton("Add combo") { adding = true }
                        QuietButton("Reset") {
                            overlay.layout.controls.clear(); overlay.layout.controls.addAll(OverlayLayout.defaults(layoutPortrait).controls)
                            overlay.selected = null; overlay.invalidate()
                        }
                    }
                }
            }
        }
        if (adding) Dialog(onDismissRequest = { adding = false }) {
            Surface(color = Mh4uColors.PopupBg, shape = MaterialTheme.shapes.medium, border = androidx.compose.foundation.BorderStroke(1.dp, Mh4uColors.Border)) {
                Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text("New combo button", color = Mh4uColors.Amber, fontSize = 15.sp)
                    TextRow("Name", null, comboName) { comboName = it }
                    FlowChips(mask) { mask = it }
                    ToggleRow("Stay pressed until tapped again", null, latch) { latch = it }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietButton("Cancel") { adding = false }
                        AmberButton("Create", enabled = mask != 0 && comboName.isNotBlank()) {
                            overlay.selected = overlay.layout.addCombo(comboName.trim().take(6), mask, latch)
                            overlay.invalidate(); adding = false; comboName = ""; mask = 0
                        }
                    }
                }
            }
        }
    }

    // ── surface ──

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        Native.surfaceChanged(holder.surface, frame.width(), frame.height())
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { Native.surfaceChanged(holder.surface, width, height) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { Native.surfaceDestroyed() }

    // ── input ──

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (chatOpen) return super.dispatchKeyEvent(e)   // typing: keys are the keyboard's, not the game's
        if (e.keyCode == KeyEvent.KEYCODE_BUTTON_MODE && e.action == KeyEvent.ACTION_DOWN && !menuOpen) { openMenu(); return true }
        // while binding in the menu the engine learns from the same events
        if (MH4UInput.onKey(e) && (!menuOpen || MH4UInput.learning())) return true
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean =
        (!chatOpen && MH4UInput.onMotion(e) && (!menuOpen || MH4UInput.learning())) || super.dispatchGenericMotionEvent(e)

    // ── life ──

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * Writes the controls of the orientation they are in now. Done and a turn of the screen already
     * save; this covers leaving the app (home, back out, switching apps, the system ending the
     * process) while arranging them, which would otherwise lose the changes. Each orientation
     * has its own file, so the other one is never touched.
     */
    private fun saveLayout() {
        if (!::overlay.isInitialized) return
        runCatching { overlay.layout.save(layoutPortrait) }
    }

    override fun onPause() {
        super.onPause()
        saveLayout()
        MH4UInput.releaseAll(); overlay.releaseEverything()
        // a room goes on in the background; alone, the game waits
        if (org.json.JSONObject(Native.roomStatus()).optString("phase") != "room") Native.setPaused(true)
    }

    override fun onResume() {
        super.onResume()
        Native.setPaused(false)
        // the screen may have turned while the app was away: take that orientation's controls
        if (::overlay.isInitialized) onConfigurationChanged(resources.configuration)
    }

    override fun onDestroy() {
        super.onDestroy()
        saveLayout()
        if (Keyboard.host === this) Keyboard.host = null
        if (isFinishing) { Native.stop(); gameThread?.join(5000) }
    }

    companion object {
        const val EXTRA_GAME = "game"
        const val EXTRA_MESSAGE = "message"
    }
}

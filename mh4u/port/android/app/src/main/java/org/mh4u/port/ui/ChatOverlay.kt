package org.mh4u.port.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mh4u.port.Native

data class ChatMsg(val seq: Long, val nick: String, val text: String, val you: Boolean, val system: Boolean)

/**
 * The room's chat, as the Activity keeps it: what the native side has (Native.chatPoll), whether
 * the player is in a room at all, and what came in unread. The room's lines are cleared by the
 * native side when a room is entered or left, so nothing here outlives a room.
 */
class ChatModel {
    val lines = mutableStateListOf<ChatMsg>()
    var inRoom by mutableStateOf(false); private set
    var unread by mutableIntStateOf(0)
    /** [SystemClock.uptimeMillis] of the newest line (0: none yet): the log shows itself for a while. */
    var lastAt by mutableLongStateOf(0L); private set
    private var seq = 0L

    /** One look at the room and its chat. The JNI calls run off the main thread. */
    suspend fun refresh(inputOpen: Boolean) {
        val polled = withContext(Dispatchers.IO) {
            runCatching {
                val room = JSONObject(Native.roomStatus()).optString("phase") == "room"
                if (!room) return@runCatching null
                JSONObject(Native.chatPoll(seq))
            }.getOrNull()
        }
        if (polled == null) {
            inRoom = false
            if (lines.isNotEmpty()) lines.clear()
            unread = 0
            return
        }
        inRoom = true
        val fresh = polled.optJSONArray("lines")
        var added = false
        if (fresh != null) for (i in 0 until fresh.length()) {
            val o = fresh.getJSONObject(i)
            val msg = ChatMsg(o.optLong("seq"), o.optString("nick"), o.optString("msg"), o.optBoolean("you"), o.optBoolean("system"))
            lines.add(msg)
            if (!msg.you && !msg.system && !inputOpen) unread++
            added = true
        }
        seq = maxOf(seq, polled.optLong("seq", seq))
        while (lines.size > 50) lines.removeAt(0)
        if (added) lastAt = SystemClock.uptimeMillis()
    }
}

/**
 * Chat over the game while the player is in a room; nothing at all outside one. Three separate
 * pieces: the log of recent lines (read-only, touches go through it to the game), the chat button
 * and, once it is tapped, the input bar. [hidden]: the game's own menu is up.
 */
@Composable
fun ChatOverlay(model: ChatModel, hidden: Boolean, inputOpen: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(inputOpen) {
        while (true) {
            model.refresh(inputOpen)
            delay(if (inputOpen) 500 else 1000)
        }
    }
    // out of the room with the input open (kicked, the room closed): the input goes with it
    LaunchedEffect(model.inRoom, inputOpen) { if (!model.inRoom && inputOpen) onClose() }

    if (!model.inRoom || hidden) return
    Box(Modifier.fillMaxSize()) {
        ChatLog(model, inputOpen)
        if (!inputOpen) ChatButton(model.unread) { model.unread = 0; onOpen() }
        else ChatInput(
            onSend = { text -> scope.launch(Dispatchers.IO) { Native.chatSend(text) } },
            onClose = onClose,
        )
    }
}

private const val LOG_SHOWN_MS = 8000L

/** The latest lines at the top left, between the L button and the menu button. Not touchable. */
@Composable
private fun BoxScope.ChatLog(model: ChatModel, inputOpen: Boolean) {
    var recent by remember { mutableStateOf(false) }
    LaunchedEffect(model.lastAt) {
        if (model.lastAt != 0L) { recent = true; delay(LOG_SHOWN_MS); recent = false }
    }
    // input open: the whole kept log, scrollable; closed: the last few lines, touches pass through
    val shown = if (inputOpen) model.lines.toList() else model.lines.takeLast(5)
    if ((recent || inputOpen) && shown.isNotEmpty()) {
        val scroll = rememberScrollState()
        // stay at the newest line, unless the player has scrolled up to read
        val atBottom = scroll.maxValue - scroll.value < 40
        LaunchedEffect(shown.size, inputOpen) { if (atBottom || inputOpen) scroll.scrollTo(scroll.maxValue) }
        Column(
            Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 96.dp, top = 8.dp)
                .widthIn(max = 280.dp)
                .then(if (inputOpen) Modifier.heightIn(max = 160.dp) else Modifier)
                .background(Color(0x99121214), RoundedCornerShape(6.dp))
                .then(if (inputOpen) Modifier.verticalScroll(scroll) else Modifier)
                .padding(6.dp),
        ) {
            for (m in shown) {
                Text(
                    buildAnnotatedString {
                        if (m.system) withStyle(SpanStyle(color = Mh4uColors.TextDisabled, fontStyle = FontStyle.Italic)) { append(m.text) }
                        else {
                            withStyle(SpanStyle(color = if (m.you) Mh4uColors.AmberBright else Mh4uColors.Amber, fontWeight = FontWeight.Bold)) { append(m.nick + ": ") }
                            withStyle(SpanStyle(color = Mh4uColors.Text)) { append(m.text) }
                        }
                    },
                    fontSize = 12.sp, lineHeight = 15.sp,
                )
            }
        }
    }
}

/** The round button that opens the input; a red count of what came in since it was last opened. */
@Composable
private fun BoxScope.ChatButton(unread: Int, onClick: () -> Unit) {
    Box(
        Modifier.align(BiasAlignment(0.22f, -0.88f)).size(44.dp)
            .background(Color(0x99121214), CircleShape).border(1.dp, Mh4uColors.Border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("💬", fontSize = 18.sp)
        if (unread > 0) Box(
            Modifier.align(Alignment.TopEnd).size(18.dp).background(Mh4uColors.Error, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(if (unread > 9) "9+" else unread.toString(), fontSize = 9.sp, color = Color.White) }
    }
}

/** The input bar at the bottom, above the keyboard. Sending closes it, so the controls come back. */
@Composable
private fun BoxScope.ChatInput(onSend: (String) -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        onSend(t); text = ""; onClose()
    }
    Surface(
        Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(8.dp).widthIn(max = 560.dp).fillMaxWidth(),
        color = Mh4uColors.PopupBg.copy(alpha = .95f), shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Mh4uColors.Border),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(200) }, singleLine = true,
                placeholder = { Text("Message the room") },
                modifier = Modifier.weight(1f).focusRequester(focus),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Mh4uColors.Amber, unfocusedBorderColor = Mh4uColors.Border, cursorColor = Mh4uColors.Amber,
                ),
            )
            AmberButton("Send", enabled = text.isNotBlank()) { send() }
            QuietButton("✕") { onClose() }
        }
    }
}

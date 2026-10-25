package org.mh4u.port.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.mh4u.port.Native

/** One cheat of the running game, as the native side lists it. */
private data class CheatItem(val index: Int, val name: String, val comments: String, val code: String, val enabled: Boolean)

private fun readCheats(): List<CheatItem> = runCatching {
    val a = JSONArray(Native.cheatList())
    List(a.length()) {
        val o = a.getJSONObject(it)
        CheatItem(o.getInt("i"), o.getString("name"), o.getString("comments").trim(), o.getString("code").trim(), o.getBoolean("enabled"))
    }
}.getOrDefault(emptyList())

/**
 * Cheat files as people share them: "[Name]", then "*comment" lines and the Gateway code
 * lines. Text without any "[Name]" is taken as one cheat called "Cheat".
 */
private fun parseCheatText(text: String): List<Triple<String, String, String>> {
    val out = mutableListOf<Triple<String, String, String>>()
    var name: String? = null
    val code = StringBuilder()
    val comments = StringBuilder()
    fun flush() {
        val n = name
        if (n != null && code.isNotBlank()) out += Triple(n, comments.toString().trim(), code.toString().trim())
        code.clear(); comments.clear()
    }
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.length >= 2 && line.startsWith("[") && line.endsWith("]") -> { flush(); name = line.substring(1, line.length - 1).trim() }
            line.startsWith("*") -> if (line != "*citra_enabled" && line != "*azaharplus_built_in") comments.appendLine(line.drop(1).trim())
            line.isNotEmpty() -> { if (name == null) name = "Cheat"; code.appendLine(line) }
        }
    }
    flush()
    return out
}

/**
 * Cheats: the running game's Gateway cheats (Azahar's engine). A switch turns one on or off,
 * and every change is saved to the game's cheat file at once. Before the game has started there
 * are none to list; the menu is only reached from a running game, so that is rare.
 */
@Composable
fun CheatsPage() {
    var cheats by remember { mutableStateOf(readCheats()) }
    var edit by remember { mutableStateOf<CheatItem?>(null) }   // index -1: a new cheat
    var importing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun reload() { cheats = readCheats() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Cheats")
        Panel {
            Hint("Gateway cheats for this game, saved to its cheat file as you change them. A cheat can crash the game or spoil a save: back the save up first. In a room, cheats that change the game's state can put you out of step with the others.")
        }
        Spacer(Modifier.height(8.dp))
        Panel {
            if (cheats.isEmpty()) Hint("No cheats yet. Add one, or paste cheats in the usual [Name] form.")
            cheats.forEach { c ->
                ToggleRow(c.name, c.comments.ifBlank { null }, c.enabled) { on ->
                    if (Native.cheatSetEnabled(c.index, on)) reload() else message = "Could not change the cheat."
                }
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietButton("Edit") { message = ""; edit = c }
                    QuietButton("Delete") { if (Native.cheatRemove(c.index)) reload() else message = "Could not delete the cheat." }
                }
                RowDivider()
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmberButton("Add cheat") { message = ""; edit = CheatItem(-1, "", "", "", true) }
            QuietButton("Paste cheats") { message = ""; importing = true }
        }
        if (message.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Notice(message) }
        Spacer(Modifier.height(24.dp))
    }

    edit?.let { c ->
        var name by remember(c) { mutableStateOf(c.name) }
        var comments by remember(c) { mutableStateOf(c.comments) }
        var code by remember(c) { mutableStateOf(c.code) }
        var error by remember(c) { mutableStateOf("") }
        CheatDialog(if (c.index < 0) "New cheat" else "Edit cheat", onDismiss = { edit = null }) {
            TextRow("Name", null, name) { name = it }
            TextRow("Note (optional)", null, comments) { comments = it }
            TextRow("Code", "One Gateway line per row: 8 hex digits, a space, 8 hex digits.", code, multiline = true) { code = it }
            if (error.isNotEmpty()) Notice(error)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Cancel") { edit = null }
                AmberButton("Save") {
                    error = Native.cheatSet(c.index, name.trim(), comments.trim(), code)
                    if (error.isEmpty()) { edit = null; reload() }
                }
            }
        }
    }

    if (importing) {
        var text by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        CheatDialog("Paste cheats", onDismiss = { importing = false }) {
            TextRow("Cheats", "As shared online: [Name] then the code lines. Several cheats at once are fine; they are added switched off.", text, multiline = true) { text = it }
            if (result.isNotEmpty()) Notice(result)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton("Cancel") { importing = false }
                AmberButton("Add", enabled = text.isNotBlank()) {
                    val found = parseCheatText(text)
                    val problems = mutableListOf<String>()
                    var added = 0
                    for ((n, note, code) in found) {
                        val why = Native.cheatSet(-1, n, note, code)
                        if (why.isEmpty()) added++ else problems += "$n: $why"
                    }
                    reload()
                    if (found.isEmpty()) result = "Found no cheat in that text."
                    else if (problems.isEmpty()) importing = false
                    else result = "Added $added. " + problems.joinToString(" ")
                    if (found.isNotEmpty() && problems.isEmpty()) message = "Added $added cheat" + if (added == 1) "." else "s."
                }
            }
        }
    }
}

@Composable
private fun CheatDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Mh4uColors.PopupBg, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, Mh4uColors.Border)) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Text(title, color = Mh4uColors.Amber, fontSize = 15.sp, modifier = Modifier.padding(bottom = 4.dp))
                content()
            }
        }
    }
}

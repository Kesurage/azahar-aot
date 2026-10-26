package org.mh4u.launcher

import android.app.AlertDialog
import android.widget.EditText

/** Editable emulator registry: package/activity list, JSON, survives rebuilds. */
object EmulatorsDialog {

    fun show(activity: MainActivity) {
        val current = EmulatorLauncher.all(activity)
            .joinToString("\n") { it.toJson() }
        val input = EditText(activity).apply {
            setText(current)
            setMinLines(6)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.emulators_title)
            .setMessage(activity.getString(R.string.emulators_help))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runCatching {
                    EmulatorLauncher.save(activity, EmulatorLauncher.parse(input.text.toString()))
                }
                activity.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

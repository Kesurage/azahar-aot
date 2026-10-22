// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.citra.citra_emu.R

object OverlayThemeDialog {
    fun show(activity: AppCompatActivity, control: Any, onChanged: () -> Unit) {
        val images = OverlayTheme.imagesOf(control)
        if (images.isEmpty()) return

        val labels = images.map { activity.getString(it.second) } +
            activity.getString(R.string.overlay_theme_reset_images)

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.overlay_theme_edit_images)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == images.size) {
                    OverlayTheme.resetImages(activity, images.map { it.first })
                    onChanged()
                } else {
                    pickImage(activity, images[which].first, onChanged)
                }
            }
            .show()
    }

    private fun pickImage(activity: AppCompatActivity, drawableId: Int, onChanged: () -> Unit) {
        var launcher: ActivityResultLauncher<Array<String>>? = null
        launcher = activity.activityResultRegistry.register(
            "overlay_theme_image_${System.nanoTime()}",
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            launcher?.unregister()
            if (uri != null) {
                val theme = OverlayTheme.saveImage(activity, drawableId, uri)
                val message = if (theme != null) {
                    activity.getString(R.string.overlay_theme_saved, theme)
                } else {
                    activity.getString(R.string.overlay_theme_save_failed)
                }
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                onChanged()
            }
        }
        launcher.launch(arrayOf("image/*"))
    }

    /** Lets the user pick a theme .zip and imports it into theme/<zip name>. */
    fun importZip(activity: AppCompatActivity, onDone: () -> Unit) {
        var launcher: ActivityResultLauncher<Array<String>>? = null
        launcher = activity.activityResultRegistry.register(
            "overlay_theme_import_${System.nanoTime()}",
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            launcher?.unregister()
            if (uri != null) {
                Thread {
                    val name = OverlayTheme.importZip(activity, uri)
                    activity.runOnUiThread {
                        val message = if (name != null) {
                            activity.getString(R.string.overlay_theme_import_done, name)
                        } else {
                            activity.getString(R.string.overlay_theme_import_failed)
                        }
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                        onDone()
                    }
                }.start()
            }
        }
        launcher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
    }

    /** Lets the user choose where to save the selected theme as <name>.zip. */
    fun exportZip(activity: AppCompatActivity, theme: String) {
        var launcher: ActivityResultLauncher<String>? = null
        launcher = activity.activityResultRegistry.register(
            "overlay_theme_export_${System.nanoTime()}",
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            launcher?.unregister()
            if (uri != null) {
                Thread {
                    val ok = OverlayTheme.exportZip(activity, uri, theme)
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            if (ok) R.string.overlay_theme_export_done
                            else R.string.overlay_theme_export_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
            }
        }
        launcher.launch(OverlayTheme.exportName(theme) + ".zip")
    }
}

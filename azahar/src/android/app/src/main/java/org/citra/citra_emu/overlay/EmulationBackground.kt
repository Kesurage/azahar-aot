// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.utils.Log

/** Stores the custom image that is shown behind the emulated screens. */
object EmulationBackground {
    private const val MAX_SIZE = 2048

    private fun file(context: Context, portrait: Boolean) =
        File(
            context.filesDir,
            if (portrait) "emulation_background_portrait.png"
            else "emulation_background_landscape.png"
        )

    fun exists(context: Context, portrait: Boolean): Boolean = file(context, portrait).isFile

    /** Loads the saved image, or null if none is set. */
    fun load(context: Context, portrait: Boolean): Bitmap? {
        val f = file(context, portrait)
        if (!f.isFile) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            Log.error("[EmulationBackground] Cannot load image: ${e.message}")
            null
        }
    }

    /** Small version of the saved image for previews, or null if none is set. */
    fun loadThumbnail(context: Context, portrait: Boolean, maxSize: Int): Bitmap? {
        val f = file(context, portrait)
        if (!f.isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                f.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        } catch (e: Exception) {
            Log.error("[EmulationBackground] Cannot load thumbnail: ${e.message}")
            null
        }
    }

    /** Decodes a picked image, downscaled if huge. */
    private fun decode(context: Context, source: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(source)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (e: Exception) {
            Log.error("[EmulationBackground] Cannot decode image: ${e.message}")
            null
        }
    }

    /** Stores the (already cropped) image. */
    private fun saveBitmap(context: Context, bitmap: Bitmap, portrait: Boolean): Boolean {
        return try {
            file(context, portrait).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            true
        } catch (e: Exception) {
            Log.error("[EmulationBackground] Cannot save image: ${e.message}")
            false
        }
    }

    fun clear(context: Context, portrait: Boolean) {
        file(context, portrait).delete()
        applyToRenderer(context)
    }

    /** Sends both saved images (or none) to the renderer. Safe to call at any time. */
    fun applyToRenderer(context: Context) {
        for (portrait in booleanArrayOf(false, true)) {
            try {
                val bitmap = load(context, portrait)
                if (bitmap == null) {
                    NativeLibrary.setBackgroundImage(portrait, null, 0, 0)
                    continue
                }
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                bitmap.recycle()
                NativeLibrary.setBackgroundImage(portrait, pixels, width, height)
            } catch (e: Throwable) {
                Log.error("[EmulationBackground] Cannot apply image: ${e.message}")
            }
        }
    }

    /** Opens the system picker and stores the chosen image. */
    fun pick(activity: AppCompatActivity, portrait: Boolean, onDone: () -> Unit) {
        var launcher: ActivityResultLauncher<Array<String>>? = null
        launcher = activity.activityResultRegistry.register(
            "emulation_background_${System.nanoTime()}",
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            launcher?.unregister()
            if (uri != null) {
                Thread {
                    val bitmap = decode(activity, uri)
                    activity.runOnUiThread {
                        if (bitmap == null) {
                            Toast.makeText(
                                activity,
                                R.string.emulation_background_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                            return@runOnUiThread
                        }
                        // Let the user move/zoom the image inside the game's aspect ratio
                        BackgroundCropDialog.show(activity, bitmap, portrait) { cropped ->
                            Thread {
                                val ok = saveBitmap(activity, cropped, portrait)
                                if (ok) applyToRenderer(activity)
                                activity.runOnUiThread {
                                    Toast.makeText(
                                        activity,
                                        if (ok) R.string.emulation_background_set
                                        else R.string.emulation_background_failed,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onDone()
                                }
                            }.start()
                        }
                    }
                }.start()
            }
        }
        launcher.launch(arrayOf("image/*"))
    }
}

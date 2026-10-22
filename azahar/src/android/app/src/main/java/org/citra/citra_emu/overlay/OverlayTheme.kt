// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.BufferedOutputStream
import java.util.WeakHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.FileUtil
import org.citra.citra_emu.utils.Log

object OverlayTheme {
    private const val PREF_KEY = "OverlayButtonTheme"
    private const val THEME_DIR = "theme"
    private const val NO_MEDIA = ".nomedia"
    private const val TEMPLATE_THEME = "Template"
    private const val MAX_IMAGE_SIZE = 512
    private const val TEMPLATE_MIN_SIZE = 256f

    private val TEMPLATE_DRAWABLES = intArrayOf(
        R.drawable.button_a,
        R.drawable.button_a_pressed,
        R.drawable.button_b,
        R.drawable.button_b_pressed,
        R.drawable.button_x,
        R.drawable.button_x_pressed,
        R.drawable.button_y,
        R.drawable.button_y_pressed,
        R.drawable.button_l,
        R.drawable.button_l_pressed,
        R.drawable.button_r,
        R.drawable.button_r_pressed,
        R.drawable.button_zl,
        R.drawable.button_zl_pressed,
        R.drawable.button_zr,
        R.drawable.button_zr_pressed,
        R.drawable.button_start,
        R.drawable.button_start_pressed,
        R.drawable.button_select,
        R.drawable.button_select_pressed,
        R.drawable.dpad,
        R.drawable.dpad_pressed_one_direction,
        R.drawable.dpad_pressed_two_directions,
        R.drawable.stick_main_range,
        R.drawable.stick_main,
        R.drawable.stick_main_pressed,
        R.drawable.stick_c_range,
        R.drawable.stick_c,
        R.drawable.stick_c_pressed,
        R.drawable.button_home,
        R.drawable.button_home_pressed,
        R.drawable.button_swap,
        R.drawable.button_swap_pressed,
        R.drawable.button_turbo,
        R.drawable.button_turbo_pressed,
        R.drawable.button_combo_1,
        R.drawable.button_combo_1_pressed,
        R.drawable.button_combo_2,
        R.drawable.button_combo_2_pressed,
        R.drawable.button_combo_3,
        R.drawable.button_combo_3_pressed,
        R.drawable.button_combo_4,
        R.drawable.button_combo_4_pressed,
        R.drawable.button_combo_5,
        R.drawable.button_combo_5_pressed,
    )

    private var files: Map<String, Uri> = emptyMap()
    private val bitmaps = HashMap<String, Bitmap>()
    private val controlImages = WeakHashMap<Any, List<Pair<Int, Int>>>()

    var selected: String
        get() = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
            .getString(PREF_KEY, "") ?: ""
        set(value) {
            PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
                .edit()
                .putString(PREF_KEY, value)
                .apply()
            refresh()
        }

    /** Remembers which images (drawable id to label string id) a control uses. */
    fun register(control: Any, images: List<Pair<Int, Int>>) {
        controlImages[control] = images
    }

    fun imagesOf(control: Any): List<Pair<Int, Int>> = controlImages[control] ?: emptyList()

    private fun userRoot(): Uri? = DirectoryInitialization.userPath?.let { Uri.parse(it) }

    private fun childDir(parent: Uri, name: String, create: Boolean): Uri? {
        FileUtil.listFiles(parent).firstOrNull { it.isDirectory && it.filename == name }
            ?.let { return it.uri }
        if (!create) return null
        return FileUtil.createDir(parent.toString(), name)?.uri
    }

    private fun themeRoot(create: Boolean): Uri? {
        val root = userRoot() ?: return null
        return childDir(root, THEME_DIR, create)
    }

    private fun themeFolder(name: String, create: Boolean): Uri? {
        val dir = themeRoot(create) ?: return null
        return childDir(dir, name, create)
    }

    /** Creates the theme folder if needed. Safe to call any time; never throws. */
    fun ensureThemeFolder(): Boolean {
        val dir = try {
            themeRoot(true)
        } catch (e: Exception) {
            Log.error("[OverlayTheme] Cannot create theme folder: ${e.message}")
            null
        } ?: return false
        if (FileUtil.listFiles(dir).none { it.filename == NO_MEDIA }) {
            FileUtil.createFile(dir.toString(), NO_MEDIA)
        }
        return true
    }

    fun listThemes(): List<String> {
        val dir = themeRoot(false) ?: return emptyList()
        return FileUtil.listFiles(dir)
            .filter { it.isDirectory }
            .map { it.filename }
            .sortedBy { it.lowercase() }
    }

    private fun cleanName(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trim('.')

    /** First unused "<prefix> N" name (Theme 1, Theme 2, ...). */
    fun suggestName(prefix: String): String {
        val taken = listThemes().map { it.lowercase() }.toSet()
        var n = 1
        while ("$prefix $n".lowercase() in taken) n++
        return "$prefix $n"
    }

    /** Creates an empty theme folder. Returns its final name (made unique), or null on failure. */
    fun createTheme(raw: String, reuseExisting: Boolean = false): String? {
        if (!ensureThemeFolder()) return null
        val root = themeRoot(false) ?: return null
        val base = cleanName(raw).ifEmpty { return null }
        val existing = listThemes()
        if (reuseExisting) {
            existing.firstOrNull { it.equals(base, ignoreCase = true) }?.let { return it }
        }
        val taken = existing.map { it.lowercase() }.toSet()
        var name = base
        var n = 2
        while (name.lowercase() in taken) name = "$base ($n)".also { n++ }
        return childDir(root, name, true)?.let { name }
    }

    /** Renames a theme folder. Returns the new name or null. Keeps the selection in sync. */
    fun renameTheme(old: String, raw: String): String? {
        val newName = cleanName(raw)
        if (newName.isEmpty() || newName == old) return null
        if (listThemes().any { it.equals(newName, ignoreCase = true) && !it.equals(old, true) }) {
            return null
        }
        val uri = themeFolder(old, false) ?: return null
        if (!FileUtil.renameFile(uri.toString(), newName)) return null
        if (selected == old) selected = newName
        return newName
    }

    /** Deletes a theme folder. Falls back to Default if it was selected. */
    fun deleteTheme(name: String) {
        val uri = themeFolder(name, false) ?: return
        FileUtil.deleteDocument(uri.toString())
        if (selected == name) selected = ""
    }

    /** One thumbnail in the theme preview. [custom] is false when the default image is shown. */
    class Preview(val bitmap: Bitmap, val custom: Boolean)

    private val previewCache = HashMap<String, List<Preview>>()
    private val defaultPreviews = HashMap<String, Bitmap>()

    @Synchronized
    fun clearPreviewCache() = previewCache.clear()

    @Synchronized
    fun cachedPreview(theme: String): List<Preview>? = previewCache[theme]

    /**
     * Thumbnails of every overlay image for [theme] ("" = built-in). Images the theme doesn't
     * replace use the built-in one (custom = false). Call from a background thread.
     */
    fun loadPreview(context: Context, theme: String, size: Int): List<Preview> {
        cachedPreview(theme)?.let { return it }
        val themeFiles: Map<String, Uri> = if (theme.isEmpty()) {
            emptyMap()
        } else {
            themeFolder(theme, false)?.let { dir ->
                FileUtil.listFiles(dir).filter { !it.isDirectory }
                    .associate { it.filename.lowercase() to it.uri }
            } ?: emptyMap()
        }
        val result = ArrayList<Preview>()
        for (id in TEMPLATE_DRAWABLES) {
            val name = context.resources.getResourceEntryName(id)
            val custom = themeFiles["$name.png"]?.let { uri ->
                try {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } catch (e: Exception) {
                    null
                }
            }?.let { Bitmap.createScaledBitmap(it, size, size, true) }
            if (custom != null) {
                result.add(Preview(custom, true))
                continue
            }
            val key = "$id:$size"
            val fallback = synchronized(this) { defaultPreviews[key] }
                ?: renderDefault(context, id)?.let {
                    Bitmap.createScaledBitmap(it, size, size, true)
                }?.also { synchronized(this) { defaultPreviews[key] = it } }
            if (fallback != null) result.add(Preview(fallback, theme.isEmpty()))
        }
        synchronized(this) { previewCache[theme] = result }
        return result
    }

    /** Rescans the selected theme folder. Called whenever the overlay is built. */
    fun refresh() {
        files = emptyMap()
        bitmaps.clear()
        val name = selected
        if (name.isEmpty()) return
        val themeUri = themeFolder(name, false) ?: return
        files = FileUtil.listFiles(themeUri)
            .filter { !it.isDirectory }
            .associate { it.filename.lowercase() to it.uri }
    }

    /** Returns the themed image for a built-in drawable, or null to use the default one. */
    fun loadBitmap(context: Context, drawableId: Int): Bitmap? {
        if (files.isEmpty()) return null
        val name = context.resources.getResourceEntryName(drawableId)
        bitmaps[name]?.let { return it }
        val uri = files["$name.png"] ?: return null
        return try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?.also { bitmaps[name] = it }
        } catch (e: Exception) {
            Log.error("[OverlayTheme] Cannot load $name.png: ${e.message}")
            null
        }
    }

    /** Saves the built-in images as PNG files in theme/Template. */
    fun exportTemplate(context: Context): Boolean {
        if (!ensureThemeFolder()) return false
        val dir = themeFolder(TEMPLATE_THEME, true) ?: return false
        val existing = FileUtil.listFiles(dir).map { it.filename.lowercase() }.toSet()
        var success = true
        for (id in TEMPLATE_DRAWABLES) {
            val name = context.resources.getResourceEntryName(id)
            if ("$name.png" in existing) continue
            val bitmap = renderDefault(context, id)
            success = (bitmap != null && writePng(context, dir, "$name.png", bitmap)) && success
        }
        return success
    }

    /**
     * Imports a theme zip into theme/<zip name>. Folders inside the zip are flattened and only
     * PNG files are kept. Returns the new theme name, or null on failure.
     */
    fun importZip(context: Context, source: Uri): String? {
        if (!ensureThemeFolder()) return null
        val root = themeRoot(false) ?: return null
        val base = cleanName(FileUtil.getFilename(source).substringBeforeLast('.')).ifEmpty { "Imported" }
        val existing = listThemes().map { it.lowercase() }.toSet()
        var name = base
        var n = 2
        while (name.lowercase() in existing) name = "$base ($n)".also { n++ }

        val dir = childDir(root, name, true) ?: return null
        val parent = DocumentFile.fromTreeUri(context, dir) ?: return null
        var count = 0
        try {
            val input = context.contentResolver.openInputStream(source) ?: return null
            ZipInputStream(input.buffered()).use { zis ->
                val seen = HashSet<String>()
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val fileName = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory &&
                        !entry.name.startsWith("__MACOSX") &&
                        !fileName.startsWith(".") &&
                        fileName.endsWith(".png", ignoreCase = true) &&
                        seen.add(fileName.lowercase())
                    ) {
                        val file = parent.createFile("image/png", fileName)
                        if (file != null) {
                            context.contentResolver.openOutputStream(file.uri)?.use {
                                zis.copyTo(it)
                            }
                            count++
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.error("[OverlayTheme] Cannot import zip: ${e.message}")
            count = 0
        }
        if (count == 0) {
            FileUtil.deleteDocument(dir.toString())
            return null
        }
        selected = name
        return name
    }

    /** File name (without .zip) used when exporting [theme] ("" is the default images). */
    fun exportName(theme: String): String = theme.ifEmpty { TEMPLATE_THEME }

    /** Writes [theme] (or the default images when empty) as a zip to [destination]. */
    fun exportZip(context: Context, destination: Uri, theme: String): Boolean {
        return try {
            if (theme.isEmpty() && !exportTemplate(context)) return false
            val dir = themeFolder(exportName(theme), false) ?: return false
            val out = context.contentResolver.openOutputStream(destination) ?: return false
            var count = 0
            ZipOutputStream(BufferedOutputStream(out)).use { zos ->
                for (doc in FileUtil.listFiles(dir).filter { !it.isDirectory }) {
                    if (!doc.filename.endsWith(".png", ignoreCase = true)) continue
                    zos.putNextEntry(ZipEntry(doc.filename))
                    context.contentResolver.openInputStream(doc.uri)?.use { it.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
            }
            count > 0
        } catch (e: Exception) {
            Log.error("[OverlayTheme] Cannot export zip: ${e.message}")
            false
        }
    }

    private fun renderDefault(context: Context, drawableId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: TEMPLATE_MIN_SIZE.toInt()
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: TEMPLATE_MIN_SIZE.toInt()
        val scale = maxOf(1f, TEMPLATE_MIN_SIZE / maxOf(width, height))
        val bitmap = Bitmap.createBitmap(
            (width * scale).toInt(),
            (height * scale).toInt(),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun writePng(context: Context, dir: Uri, name: String, bitmap: Bitmap): Boolean {
        return try {
            val parent = DocumentFile.fromTreeUri(context, dir) ?: return false
            parent.findFile(name)?.delete()
            val file = parent.createFile("image/png", name) ?: return false
            context.contentResolver.openOutputStream(file.uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: false
        } catch (e: Exception) {
            Log.error("[OverlayTheme] Cannot write $name: ${e.message}")
            false
        }
    }

    /**
     * The theme edits are written to: the one selected in settings. With Default selected (or a
     * selected theme that no longer exists) a new numbered theme ("Theme 1", "Theme 2", ...) is
     * created and selected.
     */
    private fun editableTheme(): String? {
        if (!ensureThemeFolder()) return null
        val current = selected
        if (current.isNotEmpty() && themeFolder(current, false) != null) return current
        val prefix = CitraApplication.appContext.getString(R.string.overlay_theme_default_name)
        val name = createTheme(suggestName(prefix)) ?: return null
        selected = name
        return name
    }

    /** Copies a picked image into the theme as a square PNG. Returns the theme name or null. */
    fun saveImage(context: Context, drawableId: Int, source: Uri): String? {
        val name = editableTheme() ?: return null
        val dir = themeFolder(name, false) ?: return null
        val bitmap = decodeSquare(context, source) ?: return null
        val entry = context.resources.getResourceEntryName(drawableId)
        val saved = writePng(context, dir, "$entry.png", bitmap)
        refresh()
        return if (saved) name else null
    }

    private fun decodeSquare(context: Context, uri: Uri): Bitmap? {
        return try {
            val source = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            val ratio = MAX_IMAGE_SIZE.toFloat() / maxOf(source.width, source.height)
            val size = minOf(MAX_IMAGE_SIZE, maxOf(source.width, source.height))
            val width = if (ratio < 1f) source.width * ratio else source.width.toFloat()
            val height = if (ratio < 1f) source.height * ratio else source.height.toFloat()
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val left = (size - width) / 2f
            val top = (size - height) / 2f
            Canvas(result).drawBitmap(
                source,
                null,
                RectF(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
            result
        } catch (e: Exception) {
            Log.error("[OverlayTheme] Cannot read picked image: ${e.message}")
            null
        }
    }

    /** Removes custom images from the selected theme so the default ones show again. */
    fun resetImages(context: Context, drawableIds: List<Int>) {
        val name = selected
        if (name.isEmpty()) return
        val dir = themeFolder(name, false) ?: return
        val names = drawableIds.map { context.resources.getResourceEntryName(it) + ".png" }
        FileUtil.listFiles(dir)
            .filter { doc -> names.any { it.equals(doc.filename, ignoreCase = true) } }
            .forEach { DocumentFile.fromSingleUri(context, it.uri)?.delete() }
        refresh()
    }
}

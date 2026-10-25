package org.mh4u.port

import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * HD texture packs: drop a pack's .zip (its folder tree as textures are named, e.g.
 * textures/0004000000126300/...) into the user folder's texture_packs/ and it is unpacked into
 * load/textures/<title id>/ for Azahar's custom textures on the next start. A pack is unpacked
 * once per version of the zip (its name and date); unpacking again overwrites, so a changed zip
 * with the same name updates the pack.
 */
object TexturePacks {
    private const val TAG = "mh4u-textures"
    const val MH4U_ID = "0004000000126300"

    val packsDir: File get() = File(Storage.root, "texture_packs").also { it.mkdirs() }
    val texturesDir: File get() = File(File(Storage.root, "load"), "textures").also { it.mkdirs() }

    /** Whether the game's texture folder has files in it (the app turns custom textures on then). */
    fun installed(): Boolean =
        File(texturesDir, MH4U_ID).list()?.any { !it.startsWith(".") } == true

    /** Unpacks every pack that changed. Call on a background thread; never throws. */
    fun installAll() {
        runCatching {
            val dest = File(texturesDir, MH4U_ID).also { it.mkdirs() }
            packsDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".zip") }
                ?.sortedBy { it.name }
                ?.forEach { zip -> installOne(zip, dest) }
        }.onFailure { Log.w(TAG, "texture pack install failed: $it") }
    }

    private fun installOne(zip: File, dest: File) {
        val marker = File(dest, ".pack-${zip.name}-${zip.lastModified()}")
        if (marker.exists()) {
            return
        }
        Log.i(TAG, "unpacking ${zip.name} (${zip.length() / 1048576} MiB)")
        var entries = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                val out = File(dest, entry.name)
                if (!out.canonicalPath.startsWith(dest.canonicalPath + File.separator) &&
                    out.canonicalPath != dest.canonicalPath
                ) {
                    Log.w(TAG, "skipping ${entry.name}: outside the texture folder")
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it) }
                    ++entries
                }
                z.closeEntry()
            }
        }
        marker.writeText(zip.name)
        // a pack may have been replaced by a smaller one: markers of packs that are gone go too
        dest.listFiles { f -> f.name.startsWith(".pack-") && !f.name.endsWith("-${zip.lastModified()}") }
            ?.forEach { it.delete() }
        Log.i(TAG, "unpacked $entries files from ${zip.name}")
    }
}

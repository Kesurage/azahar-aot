package org.mh4u.launcher

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

/** One detected game rom. */
data class Game(
    val uri: Uri,          // content:// uri of the rom file
    val treeUri: Uri,      // the library folder it came from
    val title: String,
    val stem: String,      // filename without extension — cover art lookup key
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Scans user-picked SAF folders for 3DS roms and persists the library.
 * Covers: any image file sitting next to the rom with the same stem
 * (game.3ds + game.png), or a "covers" subfolder entry. Offline first,
 * no network calls.
 */
object GamesStore {
    private const val PREFS = "games_store"
    private const val KEY_TREES = "library_trees"
    private const val KEY_RECENT = "recent_games"

    val ROM_EXTENSIONS = setOf("3ds", "cia", "cci", "cxi", "app")
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    fun trees(context: Context): List<Uri> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_TREES, "[]") ?: "[]")
        return (0 until arr.length()).mapNotNull {
            runCatching { Uri.parse(arr.getString(it)) }.getOrNull()
        }
    }

    fun addTree(context: Context, tree: Uri) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_TREES, "[]") ?: "[]")
        val list = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        list.remove(tree.toString())
        list.add(0, tree.toString())
        prefs.edit().putString(KEY_TREES, JSONArray(list).toString()).apply()
    }

    /** Depth-limited recursive scan of one library folder. */
    fun scanTree(context: Context, treeUri: Uri): List<Game> {
        val out = mutableListOf<Game>()
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return out
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > 4) return
            for (f in dir.listFiles()) {
                if (f.isDirectory) {
                    walk(f, depth + 1)
                    continue
                }
                val ext = f.name?.substringAfterLast('.', "")?.lowercase() ?: continue
                if (ext in ROM_EXTENSIONS) {
                    val name = f.name ?: "Unknown"
                    out += Game(
                        uri = f.uri,
                        treeUri = treeUri,
                        title = prettyTitle(name),
                        stem = name.substringBeforeLast('.'),
                        sizeBytes = f.length(),
                        lastModified = f.lastModified(),
                    )
                }
            }
        }
        walk(root, 0)
        return out
    }

    fun coverFor(context: Context, game: Game): Uri? {
        val stem = game.stem
        // Walk the tree once looking for a matching stem (covers are rare, this is fine)
        val root = DocumentFile.fromTreeUri(context, game.treeUri) ?: return null
        fun find(dir: DocumentFile, depth: Int): Uri? {
            if (depth > 4) return null
            // covers/ or covers subfolder convention first
            for (candidate in dir.listFiles()) {
                if (!candidate.isDirectory) continue
                if (candidate.name.equals("covers", ignoreCase = true)) {
                    for (ext in IMAGE_EXTENSIONS) {
                        candidate.findFile("$stem.$ext")?.let { return it.uri }
                    }
                }
            }
            for (candidate in dir.listFiles()) {
                if (candidate.isDirectory) {
                    find(candidate, depth + 1)?.let { return it }
                    continue
                }
                val cName = candidate.name ?: continue
                val cStem = cName.substringBeforeLast('.')
                val cExt = cName.substringAfterLast('.', "").lowercase()
                if (cStem == stem && cExt in IMAGE_EXTENSIONS) return candidate.uri
            }
            return null
        }
        return find(root, 0)
    }

    fun prettyTitle(filename: String): String {
        var t = filename.substringBeforeLast('.')
        t = t.replace('_', ' ').replace('.', ' ')
        // drop trailing (USA) etc? keep region tags — hunters know their dump
        return t.trim()
    }

    // ---- recently played ----

    fun touchRecent(context: Context, game: Game) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_RECENT, "[]") ?: "[]")
        val list = (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        list.remove(game.uri.toString())
        list.add(0, game.uri.toString())
        while (list.size > 10) list.removeAt(list.size - 1)
        prefs.edit().putString(KEY_RECENT, JSONArray(list).toString()).apply()
    }

    fun recentUris(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_RECENT, "[]") ?: "[]")
        return (0 until arr.length()).map { arr.getString(it) }
    }
}

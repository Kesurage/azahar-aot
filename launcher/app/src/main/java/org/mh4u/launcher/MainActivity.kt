package org.mh4u.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var games: List<Game> = emptyList()
    private var query: String = ""
    private lateinit var adapter: GameAdapter
    private lateinit var emptyView: TextView
    private lateinit var recycler: RecyclerView

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            GamesStore.addTree(this, uri)
            rescan()
        }

    private val pickCover =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            // v1: covers are found by filename convention next to the rom or in covers/
            Toast.makeText(this, R.string.cover_hint, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))
        emptyView = findViewById(R.id.empty)
        recycler = findViewById(R.id.games)
        adapter = GameAdapter(this)
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.adapter = adapter
        findViewById<android.widget.EditText>(R.id.search).doAfterTextChanged {
            setQuery(it?.toString().orEmpty())
        }
        if (GamesStore.trees(this).isEmpty()) {
            pickFolder.launch(null)
        }
        rescan()
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val mode = prefs.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun rescan() {
        thread {
            val all = GamesStore.trees(this).flatMap { GamesStore.scanTree(this, it) }
                .distinctBy { it.uri }
            runOnUiThread {
                games = all
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val recent = GamesStore.recentUris(this).toSet()
        val filtered = if (query.isBlank()) games else games.filter {
            it.title.contains(query, ignoreCase = true)
        }
        // recently played first, then alphabetical
        adapter.submit(filtered.sortedWith(
            compareByDescending<Game> { it.uri.toString() in recent }
                .thenBy { it.title.lowercase() }
        ))
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        emptyView.text = if (games.isEmpty()) {
            getString(R.string.empty_library)
        } else {
            getString(R.string.no_matches)
        }
    }

    fun setQuery(q: String) {
        query = q
        applyFilter()
    }

    fun onGameTap(game: Game) {
        val err = EmulatorLauncher.launchBest(this, game)
        GamesStore.touchRecent(this, game)
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
        applyFilter()
    }

    fun onGameLongTap(game: Game) {
        ProfileDialog.show(this, game) { rescan() }
    }

    fun loadCover(target: ImageView, game: Game) {
        thread {
            val uri = GamesStore.coverFor(this, game) ?: return@thread
            val bmp = runCatching {
                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrNull()
            if (bmp != null) runOnUiThread { target.setImageBitmap(bmp) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_add_folder -> { pickFolder.launch(null); true }
        R.id.action_rescan -> { rescan(); true }
        R.id.action_theme -> {
            val prefs = getSharedPreferences("ui", MODE_PRIVATE)
            val cur = prefs.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            val next = when (cur) {
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_YES
                AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            prefs.edit().putInt("theme", next).apply()
            AppCompatDelegate.setDefaultNightMode(next)
            true
        }
        R.id.action_emulators -> { EmulatorsDialog.show(this); true }
        else -> super.onOptionsItemSelected(item)
    }
}

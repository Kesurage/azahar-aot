// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.dialogs

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.DialogLobbyBrowserBinding
import org.citra.citra_emu.databinding.ItemLobbyEmptyRoomBinding
import org.citra.citra_emu.databinding.ItemLobbyRoomBinding
import org.citra.citra_emu.utils.NetPlayManager

class LobbyBrowser(context: Context) : BottomSheetDialog(context) {

    companion object {
        private const val PREFS_NAME = "lobby_history"
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val REFRESH_SPIN_MS = 800L
        private const val CHIP_CORNER_RADIUS_DP = 10f
        private const val CHIP_DOT_SIZE_DP = 6
        private const val CHIP_CROWN_SIZE_DP = 12
        private const val CHIP_SPACING_DP = 6
        private const val SLOT_SPACING_DP = 3
        private const val SLOT_EMPTY_ALPHA = 100
        private const val LAST_JOINED_STROKE_DP = 2
        private const val WIDE_LAYOUT_MIN_WIDTH_DP = 600

        private const val TYPE_ROOM = 0
        private const val TYPE_EMPTY = 1
    }

    private lateinit var binding: DialogLobbyBrowserBinding
    private lateinit var adapter: LobbyRoomAdapter
    private var refreshSpin: ObjectAnimator? = null

    private val activity: Activity? = context as? Activity
        ?: (context as? android.content.ContextWrapper)?.baseContext as? Activity

    private val handler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { adapter.filterAndSearch() }

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Cached preference variables to eliminate disk reads on UI filter passes
    private var lastIp: String? = null
    private var lastPort: Int = -1
    private var lastName: String? = null

    private val density = context.resources.displayMetrics.density

    /**
     * A room plus whether it is the last room the user joined.
     * Rooms without players are drawn as a thin row, unless it's the last joined room.
     */
    data class LobbyItem(
        val room: NetPlayManager.RoomInfo,
        val isLast: Boolean
    ) {
        val compact: Boolean get() = !isLast && room.members.isEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        binding = DialogLobbyBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved username, keep the pill's avatar letter in sync as it's edited
        val savedUsername = NetPlayManager.getUsername(context)
        binding.usernameInput.setText(savedUsername)
        binding.usernameAvatar.text = avatarLetterFor(savedUsername)

        binding.usernameInput.doOnTextChanged { text, _, _, _ ->
            binding.usernameAvatar.text = avatarLetterFor(text?.toString())
            NetPlayManager.setUsername(context, text?.toString().orEmpty())
        }

        // Cache last visited room details in memory
        lastIp = preferences.getString("last_room_ip", null)
        lastPort = preferences.getInt("last_room_port", -1)
        lastName = preferences.getString("last_room_name", "")

        binding.emptyRefreshButton.setOnClickListener {
            refreshRoomList()
        }

        setupRecyclerView()
        setupRefreshButton()
        setupSearchBar()

        // Show local cache instantly while network refresh runs
        refreshRoomList()

        setOnDismissListener {
            NetPlayManager.setUsername(context, binding.usernameInput.text.toString())
            NetPlayDialog(context).show()
        }
    }

    private fun Int.dp(): Int = (this * density).toInt()

    // Resolves a color from the active theme (all Theme.Citra.* variants and Material You)
    @ColorInt
    private fun themeColor(@AttrRes attr: Int): Int =
        MaterialColors.getColor(context, attr, Color.MAGENTA)

    // Accent while the room has a free slot, muted once it is full.
    // Password protected rooms are not muted, you can still see who is inside.
    @ColorInt
    private fun availabilityColor(hasSpace: Boolean): Int {
        val attr = if (hasSpace) {
            MaterialR.attr.colorPrimary
        } else {
            MaterialR.attr.colorOnSurfaceVariant
        }
        return themeColor(attr)
    }

    private fun avatarLetterFor(name: String?): String =
        name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    private fun memberLabel(username: String, nickname: String): String =
        username.ifEmpty { nickname }

    private fun setupRecyclerView() {
        adapter = LobbyRoomAdapter(context) { room -> handleRoomSelection(room) }

        // One column on phones in portrait, two on landscape and tablets.
        // Both row types take a single cell, so the empty room rows pair up too.
        val spanCount =
            if (context.resources.configuration.screenWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP) 2 else 1

        binding.roomList.apply {
            layoutManager = GridLayoutManager(context, spanCount)
            adapter = this@LobbyBrowser.adapter
        }
    }

    private fun setupRefreshButton() {
        binding.refreshButton.setOnClickListener {
            refreshRoomList()
        }
    }

    private fun setupSearchBar() {
        binding.chipHideEmpty.setOnClickListener { adapter.filterAndSearch() }
        binding.chipHideFull.setOnClickListener { adapter.filterAndSearch() }
        binding.chipHideLocked.setOnClickListener { adapter.filterAndSearch() }

        binding.searchText.doOnTextChanged { text: CharSequence?, _: Int, _: Int, _: Int ->
            binding.clearButton.visibility =
                if (text.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
            // Debounce: wait 300ms after user stops typing before filtering
            handler.removeCallbacks(searchRunnable)
            handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
        }

        binding.clearButton.setOnClickListener {
            binding.searchText.setText("")
            handler.removeCallbacks(searchRunnable)
            adapter.filterAndSearch()
        }
    }

    private fun refreshRoomList() {
        setRefreshing(true)

        // 1. Instantly display whatever is already in local memory
        val cachedRooms = NetPlayManager.getPublicRooms()
        if (cachedRooms.isNotEmpty()) {
            adapter.updateRooms(cachedRooms)
            binding.emptyView.visibility = View.GONE
            binding.roomList.visibility = View.VISIBLE
        }

        // 2. Refresh from the network asynchronously
        NetPlayManager.refreshRoomListAsync { rooms ->
            binding.emptyView.visibility = if (rooms.isEmpty()) View.VISIBLE else View.GONE
            binding.roomList.visibility = if (rooms.isEmpty()) View.GONE else View.VISIBLE
            binding.appbar.visibility = if (rooms.isEmpty()) View.GONE else View.VISIBLE

            adapter.filterAndSearch(rooms)
            setRefreshing(false)
        }
    }

    // Spins the tonal refresh button in place instead of swapping in a separate progress bar
    private fun setRefreshing(refreshing: Boolean) {
        binding.refreshButton.isEnabled = !refreshing

        if (refreshing) {
            if (refreshSpin?.isRunning != true) {
                refreshSpin =
                    ObjectAnimator.ofFloat(binding.refreshButton, View.ROTATION, 0f, 360f).apply {
                        duration = REFRESH_SPIN_MS
                        repeatCount = ObjectAnimator.INFINITE
                        start()
                    }
            }
        } else {
            refreshSpin?.cancel()
            refreshSpin = null
            binding.refreshButton.rotation = 0f
        }
    }

    private fun handleRoomSelection(room: NetPlayManager.RoomInfo) {
        if (room.hasPassword) {
            showPasswordDialog(room)
        } else {
            joinRoom(room, "")
        }
    }

    private fun showPasswordDialog(room: NetPlayManager.RoomInfo) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_password_input, null)
        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.password_input)

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.multiplayer_password_required))
            .setView(dialogView)
            .setPositiveButton(R.string.multiplayer_join_room) { _, _ ->
                joinRoom(room, passwordInput.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveLastVisitedRoom(room: NetPlayManager.RoomInfo) {
        lastIp = room.ip
        lastPort = room.port
        lastName = room.name

        preferences.edit()
            .putString("last_room_ip", room.ip)
            .putInt("last_room_port", room.port)
            .putString("last_room_name", room.name)
            .apply()
    }

    private fun isLastVisited(room: NetPlayManager.RoomInfo): Boolean {
        val ip = lastIp
        val port = lastPort

        if (ip == null || port == -1) {
            return false
        }

        val name = lastName
        return room.ip == ip && room.port == port && (name.isNullOrEmpty() || room.name == name)
    }

    /**
     * Flat list without section headers: the last joined room first, then the rooms with
     * the most players, and the empty rooms at the end.
     */
    private fun buildItems(rooms: List<NetPlayManager.RoomInfo>): List<LobbyItem> {
        val last = rooms.firstOrNull { isLastVisited(it) }
        val rest = rooms
            .filter { it !== last }
            .sortedByDescending { it.members.size }

        return listOfNotNull(last?.let { LobbyItem(it, true) }) + rest.map { LobbyItem(it, false) }
    }

    private fun sameContent(old: LobbyItem, new: LobbyItem): Boolean {
        val oldRoom = old.room
        val newRoom = new.room

        return old.isLast == new.isLast &&
            oldRoom.maxPlayers == newRoom.maxPlayers &&
            oldRoom.name == newRoom.name &&
            oldRoom.owner == newRoom.owner &&
            oldRoom.hasPassword == newRoom.hasPassword &&
            oldRoom.preferredGameName == newRoom.preferredGameName &&
            oldRoom.members.map { memberLabel(it.username, it.nickname) } ==
                newRoom.members.map { memberLabel(it.username, it.nickname) }
    }

    private fun joinRoom(room: NetPlayManager.RoomInfo, password: String) {
        // The inline username field saves on every edit, so validate before joining
        if (!NetPlayManager.isUsernameValid(context)) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.username_invalid),
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val username = binding.usernameInput.text.toString().ifEmpty {
            NetPlayManager.getUsername(context)
        }

        Thread {
            val result = NetPlayManager.netPlayJoinRoom(room.ip, room.port, username, password)

            handler.post {
                if (result == 0) {
                    saveLastVisitedRoom(room)
                    dismiss()
                }
            }
        }.start()
    }

    inner class LobbyRoomAdapter(
        private val context: Context,
        private val onRoomSelected: (NetPlayManager.RoomInfo) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<LobbyItem>()
        private var searchJob: Job? = null

        inner class RoomViewHolder(private val binding: ItemLobbyRoomBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: LobbyItem) {
                val room = item.room
                val hasSpace = room.members.size < room.maxPlayers

                binding.roomName.text = room.name
                binding.playerCount.text = "${room.members.size}/${room.maxPlayers}"
                binding.playerCount.setTextColor(availabilityColor(hasSpace))

                binding.lockIcon.isVisible = room.hasPassword
                binding.lastJoinedIcon.isVisible = item.isLast

                // Last joined room gets an accent outline
                binding.card.strokeWidth = if (item.isLast) LAST_JOINED_STROKE_DP.dp() else 0
                binding.card.strokeColor = themeColor(MaterialR.attr.colorPrimary)

                binding.gameName.text =
                    if (room.preferredGameName.isNotEmpty() && room.preferredGameId != 0L) {
                        room.preferredGameName
                    } else {
                        context.getString(R.string.multiplayer_no_game_info)
                    }

                bindSlotBar(room, hasSpace)
                bindPlayerChips(room)

                itemView.setOnClickListener { onRoomSelected(room) }
            }

            // One segment per slot. Filled segments are the players in the room.
            private fun bindSlotBar(room: NetPlayManager.RoomInfo, hasSpace: Boolean) {
                val bar = binding.slotBar
                bar.removeAllViews()

                val filled = room.members.size.coerceAtMost(room.maxPlayers)
                val fillColor = availabilityColor(hasSpace)
                val outline = themeColor(MaterialR.attr.colorOutline)
                val emptyColor = ColorUtils.setAlphaComponent(outline, SLOT_EMPTY_ALPHA)

                repeat(room.maxPlayers) { index ->
                    val params = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                    if (index > 0) {
                        params.marginStart = SLOT_SPACING_DP.dp()
                    }

                    val segment = View(context)
                    segment.layoutParams = params
                    segment.background = GradientDrawable().apply {
                        cornerRadius = 2 * density
                        setColor(if (index < filled) fillColor else emptyColor)
                    }
                    bar.addView(segment)
                }
            }

            // One chip per player, showing their full name. The host (room owner) comes
            // first with a crown. The row scrolls sideways rather than wrapping, so card
            // height stays predictable inside the RecyclerView.
            private fun bindPlayerChips(room: NetPlayManager.RoomInfo) {
                val container = binding.playerChips
                container.removeAllViews()

                val owner = room.owner
                val (hosts, others) = room.members.partition { member ->
                    owner.isNotEmpty() && (member.username == owner || member.nickname == owner)
                }

                hosts.forEach { addChip(container, it.username, it.nickname, isHost = true) }
                others.forEach { addChip(container, it.username, it.nickname, isHost = false) }
            }

            private fun addChip(
                container: LinearLayout,
                username: String,
                nickname: String,
                isHost: Boolean
            ) {
                container.addView(newChipView(memberLabel(username, nickname), isHost))
            }

            private fun crownDrawable(@ColorInt tint: Int): Drawable? {
                val crown = AppCompatResources.getDrawable(context, R.drawable.ic_crown)
                val drawable = crown?.mutate()
                drawable?.setTint(tint)
                drawable?.setBounds(0, 0, CHIP_CROWN_SIZE_DP.dp(), CHIP_CROWN_SIZE_DP.dp())
                return drawable
            }

            private fun dotDrawable(): Drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColor(MaterialR.attr.colorPrimary))
                setBounds(0, 0, CHIP_DOT_SIZE_DP.dp(), CHIP_DOT_SIZE_DP.dp())
            }

            // Colors come from theme attributes, so chips follow whichever theme is active.
            // The host uses the tertiary container to stand out from the other players.
            private fun newChipView(text: String, isHost: Boolean): TextView {
                val paddingHPx = 9.dp()
                val paddingVPx = 3.dp()

                val backgroundAttr: Int
                val textAttr: Int
                if (isHost) {
                    backgroundAttr = MaterialR.attr.colorTertiaryContainer
                    textAttr = MaterialR.attr.colorOnTertiaryContainer
                } else {
                    backgroundAttr = MaterialR.attr.colorSecondaryContainer
                    textAttr = MaterialR.attr.colorOnSecondaryContainer
                }
                val chipBackground = themeColor(backgroundAttr)
                val textColor = themeColor(textAttr)
                val leading = if (isHost) crownDrawable(textColor) else dotDrawable()

                return TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = CHIP_SPACING_DP.dp() }
                    background = GradientDrawable().apply {
                        cornerRadius = CHIP_CORNER_RADIUS_DP * density
                        setColor(chipBackground)
                    }
                    setPadding(paddingHPx, paddingVPx, paddingHPx, paddingVPx)
                    setCompoundDrawablesRelative(leading, null, null, null)
                    compoundDrawablePadding = 4.dp()
                    gravity = Gravity.CENTER_VERTICAL
                    setTextColor(textColor)
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    includeFontPadding = false
                    this.text = text
                }
            }
        }

        inner class EmptyViewHolder(private val binding: ItemLobbyEmptyRoomBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: LobbyItem) {
                val room = item.room

                binding.roomName.text = room.name
                binding.playerCount.text = "${room.members.size}/${room.maxPlayers}"
                binding.lockIcon.isVisible = room.hasPassword

                itemView.setOnClickListener { onRoomSelected(room) }
            }
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position].compact) TYPE_EMPTY else TYPE_ROOM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)

            return when (viewType) {
                TYPE_EMPTY -> {
                    val binding = ItemLobbyEmptyRoomBinding.inflate(inflater, parent, false)
                    EmptyViewHolder(binding)
                }

                else -> {
                    val binding = ItemLobbyRoomBinding.inflate(inflater, parent, false)
                    RoomViewHolder(binding)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]

            when (holder) {
                is RoomViewHolder -> holder.bind(item)
                is EmptyViewHolder -> holder.bind(item)
            }
        }

        override fun getItemCount() = items.size

        /**
         * Sorts the rooms (last joined, most players, empty last) and updates the list
         * smoothly using DiffUtil. Detects player, password, owner and room name changes
         * without re-rendering the whole list.
         */
        fun updateRooms(newRooms: List<NetPlayManager.RoomInfo>) {
            val newItems = buildItems(newRooms)

            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = items.size
                override fun getNewListSize() = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = items[oldItemPosition].room
                    val new = newItems[newItemPosition].room
                    return old.ip == new.ip && old.port == new.port
                }

                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean {
                    return sameContent(items[oldItemPosition], newItems[newItemPosition])
                }
            })

            items.clear()
            items.addAll(newItems)
            diffResult.dispatchUpdatesTo(this)
        }

        /**
         * Offloads filter and search execution to Dispatchers.Default.
         * Cancels previous search jobs on new text keystrokes to preserve UI response time.
         */
        fun filterAndSearch(
            sourceRooms: List<NetPlayManager.RoomInfo> = NetPlayManager.getPublicRooms()
        ) {
            searchJob?.cancel()

            val query = binding.searchText.text.toString().trim().lowercase(Locale.getDefault())
            val hideEmpty = binding.chipHideEmpty.isChecked
            val hideFull = binding.chipHideFull.isChecked
            val hideLocked = binding.chipHideLocked.isChecked

            searchJob = CoroutineScope(Dispatchers.Default).launch {
                var filteredList = sourceRooms

                if (hideEmpty) {
                    filteredList = filteredList.filter { it.members.isNotEmpty() }
                }
                if (hideFull) {
                    filteredList = filteredList.filter { it.members.size < it.maxPlayers }
                }
                if (hideLocked) {
                    filteredList = filteredList.filter { !it.hasPassword }
                }

                if (query.isNotEmpty()) {
                    fun String.hasQuery() = lowercase(Locale.getDefault()).contains(query)

                    filteredList = filteredList.filter { room ->
                        room.name.hasQuery() ||
                            room.owner.hasQuery() ||
                            room.preferredGameName.hasQuery() ||
                            room.members.any { it.nickname.hasQuery() || it.username.hasQuery() }
                    }
                }

                withContext(Dispatchers.Main) {
                    updateRooms(filteredList)
                }
            }
        }
    }
}

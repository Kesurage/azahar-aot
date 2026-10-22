// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.overlay

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.citra.citra_emu.R
import org.citra.citra_emu.dialogs.ChatDialog
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.utils.NetPlayManager

class ChatOverlayManager(
    private val chatContainer: View,
    private val chatRecycler: RecyclerView,
    private val chatButton: FloatingActionButton,
    private val context: Context
) {
    
    private val chatAdapter = ChatOverlayAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var autoHideRunnable: Runnable? = null
    
    private val messageListener: (Int, String) -> Unit = { type, msg ->
        (context as? android.app.Activity)?.runOnUiThread {
            if (type == NetPlayManager.NetPlayStatus.ROOM_IDLE) {
                clearAllMessages()
                chatButton.visibility = View.GONE
                return@runOnUiThread
            }
            if (!NetPlayManager.netPlayIsJoined()) {
                clearAllMessages()
                chatButton.visibility = View.GONE
                return@runOnUiThread
            }
            displayNewMessage(type, msg)
            updateChatButtonVisibility()
        }
    }

    private var maxChatLines = 8
    private val chatButtonPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val chatButtonXKey = "chat_button_position_x"
    private val chatButtonYKey = "chat_button_position_y"

    init {
        setupRecyclerView()
        setupChatButton()
        setupNetPlayListener()
        loadSettings()
        updateChatButtonVisibility()
        applyTouchPassthrough()
    }
    
    private fun setupRecyclerView() {
        chatRecycler.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = chatAdapter
            itemAnimator = null
            addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean = false
                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
            })
            isEnabled = false
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }
    
    private fun setupChatButton() {
        chatButton.setOnClickListener { ChatDialog(context).show() }
        chatButton.setPadding(0, 0, 0, 0)
        chatButton.scaleType = android.widget.ImageView.ScaleType.CENTER
        setupDraggableChatButton()
        chatButton.visibility = if (NetPlayManager.netPlayIsJoined()) View.VISIBLE else View.GONE
    }
    
    private fun setupNetPlayListener() {
        NetPlayManager.addOnMessageReceivedListener(messageListener)
    }
    
    fun loadSettings() {
        applyTextSize(IntSetting.CHAT_TEXT_SIZE.int.toFloat())
        applyBackgroundOpacity(IntSetting.CHAT_BACKGROUND_OPACITY.int)
        applyFabOpacity(IntSetting.CHAT_FAB_OPACITY.int)
        applyFabSize(IntSetting.CHAT_FAB_SIZE.int)
        applyShadow(IntSetting.CHAT_SHADOW_RADIUS.int.toFloat(), IntSetting.CHAT_SHADOW_DX.int.toFloat(), IntSetting.CHAT_SHADOW_DY.int.toFloat())
        maxChatLines = IntSetting.CHAT_MAX_LINES.int
    }
    
    private fun applyTextSize(size: Float) {
        chatAdapter.setTextSize(size)
    }
    
    private fun applyBackgroundOpacity(opacity: Int) {
        val alpha = opacity / 100f
        val backgroundColor = Color.argb((alpha * 255).toInt(), 0, 0, 0)
        chatContainer.setBackgroundColor(backgroundColor)
    }
    
    private fun applyFabOpacity(opacity: Int) {
        chatButton.alpha = opacity / 100f
    }
    
    private fun applyFabSize(sizeDp: Int) {
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            sizeDp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
        val layoutParams = chatButton.layoutParams
        layoutParams.width = sizePx
        layoutParams.height = sizePx
        chatButton.layoutParams = layoutParams
        chatButton.requestLayout()
        chatButton.setPadding(0, 0, 0, 0)
    }
    
    private fun applyShadow(radius: Float, dx: Float, dy: Float) {
        chatAdapter.setShadow(radius, dx, dy)
    }
    
    fun applyTouchPassthrough() {
        chatContainer.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            setOnTouchListener { _, _ -> false }
        }
    }
    
    fun displayNewMessage(type: Int, msg: String) {
        val formattedMessage = formatMessageByType(type, msg).trim()
        if (formattedMessage.isEmpty()) return
        chatAdapter.add(formattedMessage)
        while (chatAdapter.itemCount > maxChatLines) {
            chatAdapter.removeFirst()
        }
        showChatContainer()
        scrollToLatestMessage()
        scheduleAutoHide()
    }
    
    fun clearAllMessages() {
        mainHandler.removeCallbacksAndMessages(null)
        autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
        chatAdapter.clear()
        chatContainer.clearAnimation()
        chatContainer.animate().cancel()
        chatContainer.alpha = 1f
        chatContainer.visibility = View.GONE
    }
    
    fun updateChatButtonVisibility() {
        if (NetPlayManager.netPlayIsJoined()) {
            chatButton.visibility = View.VISIBLE
            chatButton.post { restoreChatButtonPosition() }
        } else {
            chatButton.visibility = View.GONE
            clearAllMessages()
        }
    }
    
    fun onFragmentResume() {
        updateChatButtonVisibility()
        loadSettings()
    }
    
    fun getChatAdapter(): ChatOverlayAdapter = chatAdapter
    
    fun cleanup() {
        NetPlayManager.removeOnMessageReceivedListener(messageListener)
        clearAllMessages()
    }
    
    private fun formatMessageByType(type: Int, msg: String): String {
        return when (type) {
            NetPlayManager.NetPlayStatus.CHAT_MESSAGE -> msg.trim()
            NetPlayManager.NetPlayStatus.MEMBER_JOIN -> "➕ ${msg.trim()}"
            NetPlayManager.NetPlayStatus.MEMBER_LEAVE -> "➖ ${msg.trim()}"
            NetPlayManager.NetPlayStatus.MEMBER_KICKED -> "❌ ${msg.trim()}"
            NetPlayManager.NetPlayStatus.MEMBER_BANNED -> "🚫 ${msg.trim()}"
            else -> msg.trim()
        }
    }
    
    private fun saveChatButtonPosition() {
        val parent = chatButton.parent as? View ?: return
        val maxX = parent.width - chatButton.width
        val maxY = parent.height - chatButton.height
        if (maxX <= 0 || maxY <= 0) return
        val xPercent = (chatButton.x / maxX).coerceIn(0f, 1f)
        val yPercent = (chatButton.y / maxY).coerceIn(0f, 1f)
        chatButtonPreferences.edit()
            .putFloat(chatButtonXKey, xPercent)
            .putFloat(chatButtonYKey, yPercent)
            .apply()
    }

    private fun restoreChatButtonPosition() {
        val xPercent = chatButtonPreferences.getFloat(chatButtonXKey, -1f)
        val yPercent = chatButtonPreferences.getFloat(chatButtonYKey, -1f)
        if (xPercent < 0 || yPercent < 0) return
        val parent = chatButton.parent as? View ?: return
        parent.post {
            val maxX = parent.width - chatButton.width
            val maxY = parent.height - chatButton.height
            if (maxX > 0 && maxY > 0) {
                chatButton.x = maxX * xPercent
                chatButton.y = maxY * yPercent
            }
        }
    }
    
    private fun setupDraggableChatButton() {
        var dX = 0f
        var dY = 0f
        var downRawX = 0f
        var downRawY = 0f
        var dragging = false
        chatButton.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    dX = view.x - downRawX
                    dY = view.y - downRawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.rawX - downRawX) > 8 ||
                        kotlin.math.abs(event.rawY - downRawY) > 8) {
                        dragging = true
                    }
                    if (dragging) {
                        val parent = view.parent as View
                        view.x = (event.rawX + dX)
                            .coerceIn(0f, (parent.width - view.width).toFloat())
                        view.y = (event.rawY + dY)
                            .coerceIn(0f, (parent.height - view.height).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) saveChatButtonPosition()
                    else view.performClick()
                    true
                }
                else -> false
            }
        }
    }
    
    private fun showChatContainer() {
        chatContainer.visibility = View.VISIBLE
        chatContainer.alpha = 1f
    }
    
    private fun scrollToLatestMessage() {
        val itemCount = chatAdapter.itemCount
        if (itemCount > 0) {
            chatRecycler.scrollToPosition(itemCount - 1)
        }
    }
    
    private fun scheduleAutoHide() {
        mainHandler.removeCallbacksAndMessages(null)
        autoHideRunnable = Runnable {
            chatContainer.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    chatContainer.visibility = View.GONE
                    chatContainer.alpha = 1f
                }
        }
        mainHandler.postDelayed(autoHideRunnable!!, 10000)
    }
}

class ChatOverlayAdapter : RecyclerView.Adapter<ChatOverlayAdapter.ChatViewHolder>() {
    
    private val messages = mutableListOf<String>()
    private var textSize = 14f
    private var shadowRadius = 2f
    private var shadowDx = 1f
    private var shadowDy = 1f

    class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.chat_overlay_message_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_overlay, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.textView.text = messages[position]
        holder.textView.textSize = textSize
        holder.textView.setShadowLayer(shadowRadius, shadowDx, shadowDy, Color.BLACK)
    }

    override fun getItemCount(): Int = messages.size

    fun add(message: String) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun removeFirst() {
        if (messages.isNotEmpty()) {
            messages.removeAt(0)
            notifyItemRemoved(0)
        }
    }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }

    fun setTextSize(size: Float) {
        textSize = size
        notifyDataSetChanged()
    }

    fun setShadow(radius: Float, dx: Float, dy: Float) {
        shadowRadius = radius
        shadowDx = dx
        shadowDy = dy
        notifyDataSetChanged()
    }
}

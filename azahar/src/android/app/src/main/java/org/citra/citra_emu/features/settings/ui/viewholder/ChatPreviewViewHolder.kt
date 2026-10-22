// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.ui.viewholder

import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import org.citra.citra_emu.databinding.ListItemChatPreviewBinding
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.view.SettingsItem
import org.citra.citra_emu.features.settings.ui.SettingsAdapter

/**
 * Shows how the chat overlay will look with the current settings, and offers a few presets.
 * While a slider dialog is open the adapter feeds the dragged value in through [previewValue],
 * so the card changes live without saving anything.
 */
class ChatPreviewViewHolder(
    private val binding: ListItemChatPreviewBinding,
    adapter: SettingsAdapter
) : SettingViewHolder(binding.root, adapter) {

    // Values that are being tried out in an open dialog and are not saved yet
    private val trial = HashMap<String, Int>()

    init {
        itemView.setOnClickListener(null)
        itemView.setOnLongClickListener(null)
        itemView.isClickable = false
    }

    override fun bind(item: SettingsItem) {
        binding.textSettingName.setText(item.nameId)
        binding.textSettingDescription.setText(item.descriptionId)
        adapter.chatPreview = this
        trial.clear()

        binding.chipPresetDefault.setOnClickListener {
            adapter.applyChatPreset(
                mapOf(
                    IntSetting.CHAT_TEXT_SIZE to IntSetting.CHAT_TEXT_SIZE.defaultValue,
                    IntSetting.CHAT_SHADOW_RADIUS to IntSetting.CHAT_SHADOW_RADIUS.defaultValue,
                    IntSetting.CHAT_SHADOW_DX to IntSetting.CHAT_SHADOW_DX.defaultValue,
                    IntSetting.CHAT_SHADOW_DY to IntSetting.CHAT_SHADOW_DY.defaultValue,
                    IntSetting.CHAT_BACKGROUND_OPACITY to
                        IntSetting.CHAT_BACKGROUND_OPACITY.defaultValue,
                    IntSetting.CHAT_FAB_OPACITY to IntSetting.CHAT_FAB_OPACITY.defaultValue,
                    IntSetting.CHAT_FAB_SIZE to IntSetting.CHAT_FAB_SIZE.defaultValue,
                    IntSetting.CHAT_MAX_LINES to IntSetting.CHAT_MAX_LINES.defaultValue
                )
            )
        }
        binding.chipPresetReadable.setOnClickListener {
            adapter.applyChatPreset(
                mapOf(
                    IntSetting.CHAT_TEXT_SIZE to 18,
                    IntSetting.CHAT_SHADOW_RADIUS to 3,
                    IntSetting.CHAT_SHADOW_DX to 1,
                    IntSetting.CHAT_SHADOW_DY to 1,
                    IntSetting.CHAT_BACKGROUND_OPACITY to 60,
                    IntSetting.CHAT_FAB_OPACITY to 100,
                    IntSetting.CHAT_FAB_SIZE to 56,
                    IntSetting.CHAT_MAX_LINES to 6
                )
            )
        }
        binding.chipPresetSubtle.setOnClickListener {
            adapter.applyChatPreset(
                mapOf(
                    IntSetting.CHAT_TEXT_SIZE to 12,
                    IntSetting.CHAT_SHADOW_RADIUS to 2,
                    IntSetting.CHAT_SHADOW_DX to 1,
                    IntSetting.CHAT_SHADOW_DY to 1,
                    IntSetting.CHAT_BACKGROUND_OPACITY to 15,
                    IntSetting.CHAT_FAB_OPACITY to 50,
                    IntSetting.CHAT_FAB_SIZE to 44,
                    IntSetting.CHAT_MAX_LINES to 4
                )
            )
        }
        binding.chipPresetLarge.setOnClickListener {
            adapter.applyChatPreset(
                mapOf(
                    IntSetting.CHAT_TEXT_SIZE to 22,
                    IntSetting.CHAT_SHADOW_RADIUS to 4,
                    IntSetting.CHAT_SHADOW_DX to 2,
                    IntSetting.CHAT_SHADOW_DY to 2,
                    IntSetting.CHAT_BACKGROUND_OPACITY to 55,
                    IntSetting.CHAT_FAB_OPACITY to 100,
                    IntSetting.CHAT_FAB_SIZE to 64,
                    IntSetting.CHAT_MAX_LINES to 5
                )
            )
        }
        render()
    }

    override fun onClick(clicked: View) {}

    override fun onLongClick(clicked: View): Boolean = true

    /** Shows [value] for the setting with [key] without saving it. */
    fun previewValue(key: String?, value: Int) {
        if (key == null) return
        trial[key] = value
        render()
    }

    /** Goes back to the saved values. */
    fun clearPreview() {
        trial.clear()
        render()
    }

    private fun value(setting: IntSetting): Int = trial[setting.key] ?: setting.int

    private fun render() {
        val density = itemView.resources.displayMetrics.density

        val textSize = value(IntSetting.CHAT_TEXT_SIZE).toFloat()
        val radius = value(IntSetting.CHAT_SHADOW_RADIUS).toFloat()
        val dx = value(IntSetting.CHAT_SHADOW_DX).toFloat()
        val dy = value(IntSetting.CHAT_SHADOW_DY).toFloat()
        val lines = value(IntSetting.CHAT_MAX_LINES)

        val messages: List<TextView> = listOf(
            binding.chatPreviewMsg1,
            binding.chatPreviewMsg2,
            binding.chatPreviewMsg3
        )
        // Shows the last few messages, like the real overlay does when its line limit is lower
        messages.forEachIndexed { index, view ->
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            view.setShadowLayer(radius, dx, dy, Color.BLACK)
            view.visibility = if (messages.size - index <= lines) View.VISIBLE else View.GONE
        }

        val backgroundAlpha = (value(IntSetting.CHAT_BACKGROUND_OPACITY) / 100f * 255).toInt()
        binding.chatPreviewBox.setBackgroundColor(Color.argb(backgroundAlpha, 0, 0, 0))

        val fabSize = (value(IntSetting.CHAT_FAB_SIZE) * density).toInt()
        val params = binding.chatPreviewFab.layoutParams
        params.width = fabSize
        params.height = fabSize
        binding.chatPreviewFab.layoutParams = params
        binding.chatPreviewFab.radius = fabSize / 2f
        binding.chatPreviewFab.alpha = value(IntSetting.CHAT_FAB_OPACITY) / 100f
    }
}

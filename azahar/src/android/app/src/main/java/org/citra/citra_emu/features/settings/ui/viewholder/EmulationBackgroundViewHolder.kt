// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.ui.viewholder

import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.ListItemEmulationBackgroundBinding
import org.citra.citra_emu.features.settings.model.view.SettingsItem
import org.citra.citra_emu.features.settings.ui.SettingsAdapter
import org.citra.citra_emu.overlay.EmulationBackground

class EmulationBackgroundViewHolder(
    private val binding: ListItemEmulationBackgroundBinding,
    adapter: SettingsAdapter
) : SettingViewHolder(binding.root, adapter) {

    init {
        itemView.setOnClickListener(null)
        itemView.setOnLongClickListener(null)
        itemView.isClickable = false
    }

    override fun bind(item: SettingsItem) {
        binding.textSettingName.setText(item.nameId)
        binding.textSettingDescription.setText(item.descriptionId)
        setup(true, binding.portraitCard)
        setup(false, binding.landscapeCard)
        refresh()
    }

    override fun onClick(clicked: View) {}

    override fun onLongClick(clicked: View): Boolean = true

    private val activity: AppCompatActivity?
        get() = adapter.context as? AppCompatActivity

    private fun setup(portrait: Boolean, card: MaterialCardView) {
        card.setOnClickListener {
            activity?.let { EmulationBackground.pick(it, portrait) { refresh() } }
        }
        card.setOnLongClickListener {
            val context = itemView.context
            if (EmulationBackground.exists(context, portrait)) {
                EmulationBackground.clear(context, portrait)
                Toast.makeText(
                    context,
                    R.string.emulation_background_removed,
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
            true
        }
    }

    private fun refresh() {
        refreshOne(true, binding.portraitCard, binding.portraitImage, binding.portraitAdd)
        refreshOne(false, binding.landscapeCard, binding.landscapeImage, binding.landscapeAdd)
    }

    private fun refreshOne(
        portrait: Boolean,
        card: MaterialCardView,
        image: ImageView,
        addIcon: ImageView
    ) {
        val context = itemView.context.applicationContext
        Thread {
            val thumbnail = EmulationBackground.loadThumbnail(context, portrait, THUMBNAIL_SIZE)
            card.post {
                val density = card.resources.displayMetrics.density
                if (thumbnail != null) {
                    image.setImageBitmap(thumbnail)
                    image.visibility = View.VISIBLE
                    addIcon.visibility = View.GONE
                    card.strokeWidth = (2 * density).toInt()
                    card.strokeColor = MaterialColors.getColor(
                        card,
                        com.google.android.material.R.attr.colorPrimary
                    )
                } else {
                    image.setImageDrawable(null)
                    image.visibility = View.GONE
                    addIcon.visibility = View.VISIBLE
                    card.strokeWidth = (1 * density).toInt()
                    card.strokeColor = MaterialColors.getColor(
                        card,
                        com.google.android.material.R.attr.colorOutline
                    )
                }
            }
        }.start()
    }

    private companion object {
        const val THUMBNAIL_SIZE = 400
    }
}

// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.features.settings.ui.viewholder

import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import androidx.recyclerview.widget.RecyclerView
import org.citra.citra_emu.features.settings.model.view.SettingsItem
import org.citra.citra_emu.features.settings.ui.SettingsAdapter

abstract class SettingViewHolder(itemView: View, protected val adapter: SettingsAdapter) :
    RecyclerView.ViewHolder(itemView),
    View.OnClickListener,
    View.OnLongClickListener {

    init {
        itemView.setOnClickListener(this)
        itemView.setOnLongClickListener(this)
    }

    /**
     * Called by the adapter to set this ViewHolder's child views to display the list item
     * it must now represent.
     *
     * @param item The list item that should be represented by this ViewHolder.
     */
    abstract fun bind(item: SettingsItem)

    /**
     * Called when this ViewHolder's view is clicked on. Implementations should usually pass
     * this event up to the adapter.
     *
     * @param clicked The view that was clicked on.
     */
    abstract override fun onClick(clicked: View)

    abstract override fun onLongClick(clicked: View): Boolean

    private var defaultTitleColors: android.content.res.ColorStateList? = null

    /**
     * When editing the settings of a single game, highlight the ones that have their own value
     * (as opposed to following the global settings). Long press resets them to global.
     */
    protected fun markOverride(item: SettingsItem, title: TextView) {
        if (defaultTitleColors == null) {
            defaultTitleColors = title.textColors
        }
        if (adapter.isOverridden(item)) {
            title.setTypeface(null, Typeface.BOLD)
            title.setTextColor(
                MaterialColors.getColor(title, androidx.appcompat.R.attr.colorPrimary)
            )
        } else {
            title.setTypeface(null, Typeface.NORMAL)
            title.setTextColor(defaultTitleColors)
        }
    }
}

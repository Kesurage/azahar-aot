// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.ui.viewholder

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.GestureDetector
import android.view.MotionEvent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.DialogSoftwareKeyboardBinding
import org.citra.citra_emu.databinding.ItemOverlayThemeRowBinding
import org.citra.citra_emu.databinding.ListItemOverlayThemeBinding
import org.citra.citra_emu.features.settings.model.view.SettingsItem
import org.citra.citra_emu.features.settings.ui.SettingsAdapter
import org.citra.citra_emu.overlay.OverlayTheme
import org.citra.citra_emu.overlay.OverlayThemeDialog

class OverlayThemeViewHolder(
    private val binding: ListItemOverlayThemeBinding,
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
        OverlayTheme.clearPreviewCache()
        reload()
    }

    override fun onClick(clicked: View) {}

    override fun onLongClick(clicked: View): Boolean = true

    private val activity: AppCompatActivity?
        get() = adapter.context as? AppCompatActivity

    private fun toast(message: String) {
        Toast.makeText(itemView.context, message, Toast.LENGTH_SHORT).show()
    }

    private fun askName(titleId: Int, initial: String, onName: (String) -> Unit) {
        val input = DialogSoftwareKeyboardBinding.inflate(LayoutInflater.from(itemView.context))
        input.root.isErrorEnabled = false
        input.editTextInput.setText(initial)
        input.editTextInput.setSelectAllOnFocus(true)
        input.editTextInput.setSingleLine()
        MaterialAlertDialogBuilder(itemView.context)
            .setTitle(titleId)
            .setView(input.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.editTextInput.text.toString().trim()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        input.editTextInput.requestFocus()
    }

    private fun createTheme() {
        val suggestion = OverlayTheme.suggestName(
            itemView.context.getString(R.string.overlay_theme_default_name)
        )
        askName(R.string.overlay_theme_new, suggestion) { name ->
            val created = OverlayTheme.createTheme(name)
            if (created == null) {
                toast(itemView.context.getString(R.string.overlay_theme_create_failed))
            } else {
                OverlayTheme.selected = created
                reload()
            }
        }
    }

    private fun showMenu(theme: String) {
        val context = itemView.context
        val isDefault = theme.isEmpty()
        val labels = if (isDefault) {
            arrayOf(context.getString(R.string.overlay_theme_menu_export))
        } else {
            arrayOf(
                context.getString(R.string.overlay_theme_menu_export),
                context.getString(R.string.overlay_theme_menu_rename),
                context.getString(R.string.overlay_theme_menu_delete)
            )
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(if (isDefault) context.getString(R.string.overlay_theme_default) else theme)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> activity?.let { OverlayThemeDialog.exportZip(it, theme) }
                    1 -> askName(R.string.overlay_theme_menu_rename, theme) { name ->
                        if (OverlayTheme.renameTheme(theme, name) == null) {
                            toast(context.getString(R.string.overlay_theme_rename_failed))
                        }
                        reload()
                    }
                    2 -> MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.overlay_theme_menu_delete)
                        .setMessage(context.getString(R.string.overlay_theme_delete_confirm, theme))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            OverlayTheme.deleteTheme(theme)
                            reload()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            .show()
    }

    /** Rebuilds the list of theme rows: Default, each theme folder, then "New theme". */
    private fun reload() {
        val container = binding.themeContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(itemView.context)
        val themes = listOf("") + OverlayTheme.listThemes()
        for (theme in themes) {
            val row = ItemOverlayThemeRowBinding.inflate(inflater, container, false)
            bindThemeRow(row, theme)
            container.addView(row.root)
        }
        val add = ItemOverlayThemeRowBinding.inflate(inflater, container, false)
        bindNewRow(add)
        container.addView(add.root)
    }

    private fun styleCard(row: ItemOverlayThemeRowBinding, selected: Boolean) {
        row.themeCard.isChecked = selected
    }

    private fun bindNewRow(row: ItemOverlayThemeRowBinding) {
        row.strip.visibility = View.GONE
        row.addIcon.visibility = View.VISIBLE
        row.themeName.setText(R.string.overlay_theme_new)
        styleCard(row, false)
        row.themeCard.setOnClickListener { createTheme() }
    }

    private fun bindThemeRow(row: ItemOverlayThemeRowBinding, theme: String) {
        val context = itemView.context
        val density = context.resources.displayMetrics.density
        row.themeName.text =
            if (theme.isEmpty()) context.getString(R.string.overlay_theme_default) else theme
        styleCard(row, theme == OverlayTheme.selected)

        val select = {
            OverlayTheme.selected = theme
            reload()
        }
        row.themeCard.setOnClickListener { select() }
        row.themeCard.setOnLongClickListener {
            showMenu(theme)
            true
        }

        // A HorizontalScrollView swallows taps, so forward taps and long-presses from it.
        val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    select()
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    showMenu(theme)
                }
            }
        )
        row.strip.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }

        val size = (44 * density).toInt()
        val margin = (3 * density).toInt()
        val fill = { previews: List<OverlayTheme.Preview> ->
            row.stripContent.removeAllViews()
            for (preview in previews) {
                val image = ImageView(context)
                image.setImageBitmap(preview.bitmap)
                image.alpha = if (preview.custom) 1f else 0.35f
                image.layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                    setMargins(margin, 0, margin, 0)
                }
                row.stripContent.addView(image)
            }
        }
        val cached = OverlayTheme.cachedPreview(theme)
        if (cached != null) {
            fill(cached)
        } else {
            Thread {
                val previews = OverlayTheme.loadPreview(context, theme, size)
                row.themeCard.post { fill(previews) }
            }.start()
        }
    }
}

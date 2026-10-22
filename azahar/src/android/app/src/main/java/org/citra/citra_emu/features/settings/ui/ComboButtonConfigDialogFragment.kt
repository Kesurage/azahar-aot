// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.ui

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import android.transition.TransitionManager
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.citra.citra_emu.R
import org.citra.citra_emu.utils.ComboHelper

/**
 * One compact row per combo button: number badge, name, a one-line summary of what it presses and
 * the enable switch (same state as the in-game Toggle Controls menu). Tapping a row expands it in
 * place to show every button as a toggle chip; only one row is open at a time. Every change is
 * saved immediately, so closing the dialog in any way (Done, back, tapping outside) keeps it.
 */
class ComboButtonConfigDialogFragment : DialogFragment() {

    private class Row(val card: MaterialCardView, val chips: ChipGroup)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)
        ComboHelper.restoreFromConfig()
        val root = inflater.inflate(R.layout.dialog_combo_buttons, null)
        val container = root.findViewById<LinearLayout>(R.id.combo_container)

        val colorPrimary = MaterialColors.getColor(root, com.google.android.material.R.attr.colorPrimary)
        val colorOnPrimary = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnPrimary)
        val colorOutline = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOutline)
        val colorRow = MaterialColors.layer(
            root,
            com.google.android.material.R.attr.colorSurface,
            com.google.android.material.R.attr.colorOnSurface,
            0.08f
        )
        val colorRowOpen = MaterialColors.layer(
            root,
            com.google.android.material.R.attr.colorSurface,
            com.google.android.material.R.attr.colorPrimary,
            0.12f
        )
        val strokePx = (2 * ctx.resources.displayMetrics.density).toInt()

        val working = Array(ComboHelper.COMBO_COUNT) { ComboHelper.getButtons(it).toMutableList() }
        val switches = ArrayList<MaterialSwitch>()
        val rows = ArrayList<Row>()

        fun setOpen(row: Row, open: Boolean) {
            row.chips.visibility = if (open) View.VISIBLE else View.GONE
            row.card.setCardBackgroundColor(if (open) colorRowOpen else colorRow)
            row.card.strokeWidth = if (open) strokePx else 0
            row.card.setStrokeColor(colorPrimary)
        }

        for (i in 0 until ComboHelper.COMBO_COUNT) {
            val card = inflater.inflate(R.layout.item_combo_button, container, false) as MaterialCardView
            val badge = card.findViewById<TextView>(R.id.combo_badge)
            val title = card.findViewById<TextView>(R.id.combo_title)
            val summary = card.findViewById<TextView>(R.id.combo_summary)
            val sw = card.findViewById<MaterialSwitch>(R.id.combo_switch)
            val chips = card.findViewById<ChipGroup>(R.id.combo_chips)

            badge.text = (i + 1).toString()
            title.text = getString(R.string.combo_button_n, i + 1)
            sw.isChecked = ComboHelper.isEnabled(ctx, i)
            switches.add(sw)

            fun persist() {
                ComboHelper.setEnabled(ctx, i, sw.isChecked)
                ComboHelper.setButtons(ctx, i, working[i])
            }

            fun update() {
                summary.text = ComboHelper.describe(ctx, working[i])
                val on = sw.isChecked
                val circle = GradientDrawable()
                circle.shape = GradientDrawable.OVAL
                circle.setColor(if (on) colorPrimary else colorOutline)
                badge.background = circle
                badge.setTextColor(if (on) colorOnPrimary else colorRow)
                title.alpha = if (on) 1f else 0.6f
                summary.alpha = if (on) 1f else 0.6f
            }

            for ((value, label) in ComboHelper.options(ctx)) {
                val chip = inflater.inflate(R.layout.item_combo_chip_filter, chips, false) as Chip
                chip.text = label
                chip.isChecked = working[i].contains(value)
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        if (!working[i].contains(value)) working[i].add(value)
                    } else {
                        working[i].remove(value)
                    }
                    update()
                    persist()
                }
                chips.addView(chip)
            }

            val row = Row(card, chips)
            rows.add(row)
            update()
            setOpen(row, false)
            sw.setOnCheckedChangeListener { _, _ ->
                update()
                persist()
            }

            card.setOnClickListener {
                TransitionManager.beginDelayedTransition(container)
                val willOpen = chips.visibility != View.VISIBLE
                rows.forEach { setOpen(it, false) }
                setOpen(row, willOpen)
                if (willOpen) {
                    // Bring the opened row to the top so the chips are visible
                    val scroll = root.findViewById<ScrollView>(R.id.combo_scroll)
                    scroll.post { scroll.smoothScrollTo(0, container.top + card.top - 8) }
                }
            }
            card.findViewById<View>(R.id.combo_header).setOnClickListener { card.performClick() }
            container.addView(card)
        }

        return MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.combo_button_settings)
            .setView(root)
            .setPositiveButton(R.string.overlay_control_done, null)
            .setNeutralButton(R.string.reset_to_default) { _, _ ->
                for (i in 0 until ComboHelper.COMBO_COUNT) {
                    ComboHelper.setEnabled(ctx, i, false)
                    ComboHelper.resetButtons(ctx, i)
                }
            }
            .create()
    }

    companion object {
        const val TAG = "ComboButtonConfigDialogFragment"
    }
}

// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version.
// Refer to the license.txt file included.

package org.citra.citra_emu.features.touchinput

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.ItemTouchInputBindingBinding

/** Card-style list of the bindings; the selected one gets a primary-colored outline. */
class TouchInputBindingAdapter(
    private val onRowClicked: (TouchInputBinding) -> Unit,
    private val onDeleteClicked: (TouchInputBinding) -> Unit
) : ListAdapter<TouchInputBinding, TouchInputBindingAdapter.ViewHolder>(DiffCallback) {
    /** The binding whose row is highlighted, or null for none. */
    var selectedBinding: TouchInputBinding? = null
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_REFRESH)
        }

    /** Row numbers depend on the position, so rows that only moved still need a silent refresh. */
    override fun submitList(list: List<TouchInputBinding>?) {
        super.submitList(list) {
            notifyItemRangeChanged(0, itemCount, PAYLOAD_REFRESH)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemTouchInputBindingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(position, getItem(position))
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.refresh(position, getItem(position))
        }
    }

    inner class ViewHolder(
        private val binding: ItemTouchInputBindingBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(position: Int, touchBinding: TouchInputBinding) {
            refresh(position, touchBinding)
            binding.bindingName.text = touchBinding.displayName()
            binding.bindingCoordinates.text = touchBinding.positionLabel()
            binding.root.setOnClickListener { onRowClicked(touchBinding) }
            binding.deleteButton.setOnClickListener { onDeleteClicked(touchBinding) }
        }

        fun refresh(position: Int, touchBinding: TouchInputBinding) {
            binding.bindingNumber.text = (position + 1).toString()
            binding.root.setBackgroundResource(
                if (touchBinding == selectedBinding) {
                    R.drawable.bg_touch_input_row_selected
                } else {
                    R.drawable.bg_touch_input_row
                }
            )
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<TouchInputBinding>() {
        // A binding is identified by the physical input it listens to
        override fun areItemsTheSame(old: TouchInputBinding, new: TouchInputBinding) =
            old.hasSameInputAs(new)

        override fun areContentsTheSame(old: TouchInputBinding, new: TouchInputBinding) =
            old == new
    }

    private companion object {
        const val PAYLOAD_REFRESH = "refresh"
    }
}

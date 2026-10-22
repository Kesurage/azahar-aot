// Copyright 2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package org.citra.citra_emu.features.settings.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ScrollView

/**
 * A ScrollView that never grows taller than a share of the screen, so the buttons of the dialog
 * it lives in always stay on screen even when its content gets long.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val capped = when (mode) {
            View.MeasureSpec.EXACTLY -> heightMeasureSpec
            View.MeasureSpec.AT_MOST ->
                View.MeasureSpec.makeMeasureSpec(minOf(size, maxHeight), View.MeasureSpec.AT_MOST)
            else -> View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, capped)
    }
}

package com.ruangfoto.app

import android.content.res.Configuration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout

/** Portrait stacks the work areas; landscape keeps both visible without wider controls. */
internal fun LinearLayout.arrangeWorkspace(scrollTogether: Boolean = false) {
    val sideways = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    orientation = if (sideways) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    val gap = resources.getDimensionPixelSize(R.dimen.workspace_gap)
    for (index in 0 until childCount) {
        val pane = getChildAt(index)
        pane.layoutParams = (pane.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (sideways) 0 else MATCH_PARENT
            height = if (scrollTogether) WRAP_CONTENT else if (sideways) MATCH_PARENT else 0
            marginStart = if (sideways && index != 0) gap else 0
            topMargin = if (!sideways && index != 0) gap else 0
        }
    }
}

package com.ruangfoto.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/** Selection box and corner handles for the selected layer on the live preview. */
class LayerSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = ContextCompat.getColor(context, R.color.transfer_accent)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.transfer_accent)
    }
    private val box = RectF()
    private var shown = false

    fun showSelection(rect: RectF?) {
        shown = rect != null
        if (rect != null) box.set(rect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        canvas.drawRect(box, stroke)
        val radius = 4f * density
        val corners = floatArrayOf(box.left, box.top, box.right, box.top, box.left, box.bottom, box.right, box.bottom)
        for (index in corners.indices step 2) {
            canvas.drawCircle(corners[index], corners[index + 1], radius, handle)
        }
    }
}

package com.zenmaestro.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class ZenMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padding = width.coerceAtMost(height) * .06f
        val bounds = RectF(padding, padding, width - padding, height - padding)
        ZenMarkRenderer.draw(
            canvas = canvas,
            bounds = bounds,
            primaryColor = ContextCompat.getColor(context, R.color.chalk_white),
            accentColor = ContextCompat.getColor(context, R.color.chalk_gold)
        )
    }
}

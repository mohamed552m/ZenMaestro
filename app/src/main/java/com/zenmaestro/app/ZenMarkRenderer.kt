package com.zenmaestro.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import kotlin.math.min

internal object ZenMarkRenderer {
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        primaryColor: Int,
        accentColor: Int,
        progress: Float = 1f
    ) {
        val scale = min(bounds.width(), bounds.height()) / 100f
        val left = bounds.centerX() - 50f * scale
        val top = bounds.centerY() - 50f * scale

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)

        val primary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val ring = Path().apply {
            moveTo(72f, 25f)
            cubicTo(57f, 11f, 31f, 13f, 20f, 31f)
            cubicTo(6f, 54f, 18f, 81f, 43f, 86f)
            cubicTo(61f, 90f, 78f, 80f, 82f, 64f)
        }
        drawTrimmedPath(canvas, ring, primary, progress)

        val body = Path().apply {
            moveTo(29f, 65f)
            cubicTo(40f, 56f, 48f, 56f, 52f, 68f)
            cubicTo(63f, 59f, 71f, 44f, 76f, 30f)
        }
        primary.strokeWidth = 10f
        drawTrimmedPath(canvas, body, primary, ((progress - .16f) / .84f).coerceIn(0f, 1f))

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.FILL
        }
        if (progress > .45f) {
            fill.alpha = (((progress - .45f) / .3f).coerceIn(0f, 1f) * 255).toInt()
            canvas.drawCircle(52f, 40f, 6.5f, fill)
        }

        if (progress > .7f) {
            val starProgress = ((progress - .7f) / .3f).coerceIn(0f, 1f)
            val star = Path().apply {
                moveTo(78f, 15f)
                lineTo(80f, 21f)
                lineTo(86f, 23f)
                lineTo(80f, 25f)
                lineTo(78f, 31f)
                lineTo(76f, 25f)
                lineTo(70f, 23f)
                lineTo(76f, 21f)
                close()
            }
            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = accentColor
                style = Paint.Style.FILL
                alpha = (255 * starProgress).toInt()
            }
            canvas.save()
            canvas.scale(starProgress, starProgress, 78f, 23f)
            canvas.drawPath(star, accent)
            canvas.restore()
        }

        canvas.restore()
    }

    private fun drawTrimmedPath(canvas: Canvas, source: Path, paint: Paint, progress: Float) {
        if (progress <= 0f) return
        val measure = PathMeasure(source, false)
        val segment = Path()
        measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), segment, true)
        canvas.drawPath(segment, paint)
    }
}

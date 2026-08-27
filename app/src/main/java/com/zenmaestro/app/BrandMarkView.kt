package com.zenmaestro.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class BrandMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var animationProgress = 0f
    private var animator: ValueAnimator? = null

    private val chalkboard = color(R.color.chalkboard)
    private val chalkboardLight = color(R.color.chalkboard_light)
    private val chalkLine = color(R.color.chalk_line)
    private val chalkWhite = color(R.color.chalk_white)
    private val sage = color(R.color.sage)
    private val sageGreen = color(R.color.sage_green)
    private val dustyBlue = color(R.color.dusty_blue)
    private val coral = color(R.color.coral)
    private val gold = color(R.color.chalk_gold)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1450L
            interpolator = DecelerateInterpolator(1.25f)
            addUpdateListener {
                animationProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val scale = size / 360f
        val dx = (width - size) / 2f
        val dy = (height - size) / 2f

        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(scale, scale)

        drawParticles(canvas)
        drawOrbits(canvas)

        val coreProgress = ((animationProgress - .12f) / .58f).coerceIn(0f, 1f)
        val coreBounds = RectF(105f, 103f, 255f, 253f)
        paint.setShadowLayer(9f, 0f, 0f, sageGreen)
        ZenMarkRenderer.draw(canvas, coreBounds, chalkboard, gold, coreProgress)
        paint.clearShadowLayer()

        drawBadge(canvas, 180f, 48f, Badge.CALENDAR, coral, .42f)
        drawBadge(canvas, 312f, 180f, Badge.CHECKLIST, dustyBlue, .54f)
        drawBadge(canvas, 180f, 312f, Badge.NOTEBOOK, gold, .66f)
        drawBadge(canvas, 48f, 180f, Badge.PROGRESS, sageGreen, .78f)

        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas) {
        val points = listOf(
            Triple(70f, 95f, coral), Triple(290f, 87f, dustyBlue),
            Triple(320f, 245f, gold), Triple(77f, 275f, sageGreen),
            Triple(120f, 42f, dustyBlue), Triple(247f, 318f, sageGreen),
            Triple(40f, 220f, gold), Triple(326f, 139f, coral)
        )
        paint.style = Paint.Style.FILL
        points.forEachIndexed { index, point ->
            val delay = index * .045f
            val alpha = ((animationProgress - delay) / .35f).coerceIn(0f, 1f)
            paint.color = point.third
            paint.alpha = (210 * alpha).toInt()
            canvas.drawCircle(point.first, point.second, if (index % 3 == 0) 3f else 2f, paint)
        }
        paint.alpha = 255
    }

    private fun drawOrbits(canvas: Canvas) {
        val orbitProgress = (animationProgress / .48f).coerceIn(0f, 1f)
        val rect = RectF(35f, 35f, 325f, 325f)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 3f
        paint.color = sage
        paint.alpha = 120
        canvas.drawArc(rect, -103f, 318f * orbitProgress, false, paint)
        paint.strokeWidth = 1.5f
        paint.color = chalkLine
        paint.alpha = 100
        canvas.drawArc(RectF(44f, 44f, 316f, 316f), 74f, 286f * orbitProgress, false, paint)
        paint.strokeWidth = 2f
        paint.color = sageGreen
        paint.alpha = 115
        canvas.drawArc(RectF(51f, 51f, 309f, 309f), 155f, 204f * orbitProgress, false, paint)
        paint.alpha = 255
    }

    private fun drawBadge(canvas: Canvas, cx: Float, cy: Float, badge: Badge, accent: Int, delay: Float) {
        val p = ((animationProgress - delay) / .22f).coerceIn(0f, 1f)
        if (p <= 0f) return

        canvas.save()
        canvas.scale(p, p, cx, cy)
        paint.style = Paint.Style.FILL
        paint.color = chalkboardLight
        paint.alpha = (255 * p).toInt()
        paint.setShadowLayer(9f, 0f, 2f, accent)
        canvas.drawCircle(cx, cy, 30f, paint)
        paint.clearShadowLayer()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = accent
        canvas.drawCircle(cx, cy, 32f, paint)
        paint.strokeWidth = 2.4f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = chalkWhite
        paint.alpha = (255 * p).toInt()

        when (badge) {
            Badge.CALENDAR -> drawCalendar(canvas, cx, cy)
            Badge.CHECKLIST -> drawChecklist(canvas, cx, cy)
            Badge.NOTEBOOK -> drawNotebook(canvas, cx, cy)
            Badge.PROGRESS -> drawProgress(canvas, cx, cy)
        }
        paint.alpha = 255
        canvas.restore()
    }

    private fun drawCalendar(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawRoundRect(RectF(cx - 15f, cy - 13f, cx + 15f, cy + 15f), 3f, 3f, paint)
        canvas.drawLine(cx - 15f, cy - 5f, cx + 15f, cy - 5f, paint)
        canvas.drawLine(cx - 8f, cy - 17f, cx - 8f, cy - 9f, paint)
        canvas.drawLine(cx + 8f, cy - 17f, cx + 8f, cy - 9f, paint)
        for (row in 0..1) for (column in 0..2) {
            canvas.drawCircle(cx - 8f + column * 8f, cy + 1f + row * 8f, 1.3f, paint)
        }
    }

    private fun drawChecklist(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawRoundRect(RectF(cx - 15f, cy - 17f, cx + 13f, cy + 16f), 3f, 3f, paint)
        for (i in 0..2) {
            val y = cy - 9f + i * 9f
            canvas.drawLine(cx - 9f, y, cx - 6f, y + 3f, paint)
            canvas.drawLine(cx - 6f, y + 3f, cx - 2f, y - 2f, paint)
            canvas.drawLine(cx + 2f, y, cx + 9f, y, paint)
        }
        canvas.drawCircle(cx + 13f, cy + 14f, 8f, paint)
    }

    private fun drawNotebook(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawRoundRect(RectF(cx - 17f, cy - 15f, cx + 17f, cy + 15f), 3f, 3f, paint)
        canvas.drawLine(cx, cy - 15f, cx, cy + 15f, paint)
        for (i in 0..2) {
            val y = cy - 8f + i * 7f
            canvas.drawLine(cx - 12f, y, cx - 4f, y, paint)
            canvas.drawLine(cx + 4f, y, cx + 12f, y, paint)
        }
    }

    private fun drawProgress(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawLine(cx - 16f, cy + 14f, cx + 16f, cy + 14f, paint)
        canvas.drawLine(cx - 16f, cy + 14f, cx - 16f, cy - 14f, paint)
        canvas.drawRect(cx - 11f, cy + 5f, cx - 6f, cy + 14f, paint)
        canvas.drawRect(cx - 2f, cy - 1f, cx + 3f, cy + 14f, paint)
        canvas.drawRect(cx + 7f, cy - 8f, cx + 12f, cy + 14f, paint)
        canvas.drawLine(cx - 11f, cy - 6f, cx + 10f, cy - 17f, paint)
        canvas.drawLine(cx + 10f, cy - 17f, cx + 7f, cy - 11f, paint)
        canvas.drawLine(cx + 10f, cy - 17f, cx + 3f, cy - 17f, paint)
    }

    private fun color(resource: Int) = ContextCompat.getColor(context, resource)

    private enum class Badge { CALENDAR, CHECKLIST, NOTEBOOK, PROGRESS }
}

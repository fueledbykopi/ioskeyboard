package com.ikeys.keyboard.settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Authentic animated iOS toggle switch (51dp x 31dp, iOS green #34C759 active track, crisp elevated thumb).
 */
class IOSSwitchView(context: Context) : View(context) {

    var onCheckedChangeListener: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private var checked: Boolean = false
    private var darkMode: Boolean = false
    private var animProgress: Float = 0f
    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x29000000
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val trackRect = RectF()

    init {
        isClickable = true
        setOnClickListener {
            setChecked(!checked, animate = true, notify = true)
        }
    }

    fun setDarkMode(isDark: Boolean) {
        darkMode = isDark
        invalidate()
    }

    fun isChecked(): Boolean = checked

    fun setChecked(value: Boolean, animate: Boolean = false, notify: Boolean = false) {
        if (checked == value && !animate) {
            animProgress = if (value) 1f else 0f
            invalidate()
            return
        }
        checked = value
        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(animProgress, if (value) 1f else 0f).apply {
                duration = 180L
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener {
                    animProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animProgress = if (value) 1f else 0f
            invalidate()
        }
        if (notify) {
            onCheckedChangeListener?.invoke(checked)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((51f * density).toInt(), (31f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h * 0.5f

        val offColor = if (darkMode) 0xFF39393D.toInt() else 0xFFE5E5EA.toInt()
        val onColor = 0xFF34C759.toInt()
        trackPaint.color = blendColors(offColor, onColor, animProgress)

        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, r, r, trackPaint)

        val pad = 2f * density
        val thumbR = (h - pad * 2f) * 0.5f
        val startCx = pad + thumbR
        val endCx = w - pad - thumbR
        val cx = startCx + (endCx - startCx) * animProgress
        val cy = h * 0.5f

        canvas.drawCircle(cx, cy + 1.2f * density, thumbR, shadowPaint)
        canvas.drawCircle(cx, cy, thumbR, thumbPaint)
    }

    private fun blendColors(from: Int, to: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(from) * inv + Color.red(to) * ratio).toInt()
        val g = (Color.green(from) * inv + Color.green(to) * ratio).toInt()
        val b = (Color.blue(from) * inv + Color.blue(to) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
}

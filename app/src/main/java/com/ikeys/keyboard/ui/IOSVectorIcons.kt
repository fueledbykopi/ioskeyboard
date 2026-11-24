package com.ikeys.keyboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.ikeys.keyboard.layout.ShiftState

/**
 * Zero-allocation Canvas vector icon renderer matching authentic iOS SF-Symbol keyboard glyphs.
 */
object IOSVectorIcons {

    fun drawShiftIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        state: ShiftState,
        color: Int,
        paint: Paint,
        path: Path
    ) {
        val halfW = size * 0.48f
        val stemHalfW = size * 0.21f
        val topY = cy - size * 0.44f
        val shoulderY = cy + size * 0.02f
        val bottomY = if (state == ShiftState.CAPS_LOCK) cy + size * 0.28f else cy + size * 0.40f

        path.reset()
        path.moveTo(cx, topY)
        path.lineTo(cx + halfW, shoulderY)
        path.lineTo(cx + stemHalfW, shoulderY)
        path.lineTo(cx + stemHalfW, bottomY)
        path.lineTo(cx - stemHalfW, bottomY)
        path.lineTo(cx - stemHalfW, shoulderY)
        path.lineTo(cx - halfW, shoulderY)
        path.close()

        paint.color = color
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND

        if (state == ShiftState.OFF) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.095f
            canvas.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
            if (state == ShiftState.CAPS_LOCK) {
                val barY = cy + size * 0.44f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.11f
                canvas.drawLine(cx - stemHalfW * 1.05f, barY, cx + stemHalfW * 1.05f, barY, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    fun drawBackspaceIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        isPressed: Boolean,
        fgColor: Int,
        bgColor: Int,
        paint: Paint,
        path: Path
    ) {
        val halfW = size * 0.56f
        val halfH = size * 0.36f
        val tipX = cx - halfW
        val shoulderX = cx - halfW * 0.38f
        val rightX = cx + halfW
        val topY = cy - halfH
        val bottomY = cy + halfH

        path.reset()
        path.moveTo(tipX, cy)
        path.lineTo(shoulderX, topY)
        path.lineTo(rightX, topY)
        path.lineTo(rightX, bottomY)
        path.lineTo(shoulderX, bottomY)
        path.close()

        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND

        if (isPressed) {
            paint.style = Paint.Style.FILL
            paint.color = fgColor
            canvas.drawPath(path, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.085f
            paint.color = fgColor
            canvas.drawPath(path, paint)
        }

        // Centered 'x' inside the pentagon
        val xCx = cx + size * 0.12f
        val xR = size * 0.155f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.09f
        paint.color = if (isPressed) bgColor else fgColor
        canvas.drawLine(xCx - xR, cy - xR, xCx + xR, cy + xR, paint)
        canvas.drawLine(xCx + xR, cy - xR, xCx - xR, cy + xR, paint)
        paint.style = Paint.Style.FILL
    }

    fun drawEmojiSmileyIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        paint: Paint,
        rect: RectF
    ) {
        val r = size * 0.44f
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.082f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(cx, cy, r, paint)

        // Eyes
        paint.style = Paint.Style.FILL
        val eyeR = size * 0.055f
        canvas.drawCircle(cx - r * 0.34f, cy - r * 0.18f, eyeR, paint)
        canvas.drawCircle(cx + r * 0.34f, cy - r * 0.18f, eyeR, paint)

        // Smile arc
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.075f
        val smileR = r * 0.56f
        rect.set(cx - smileR, cy - smileR * 0.72f, cx + smileR, cy + smileR * 0.92f)
        canvas.drawArc(rect, 22f, 136f, false, paint)
        paint.style = Paint.Style.FILL
    }

    fun drawGlobeIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        paint: Paint,
        rect: RectF
    ) {
        val r = size * 0.45f
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.072f
        paint.strokeCap = Paint.Cap.ROUND

        // Outer circle
        canvas.drawCircle(cx, cy, r, paint)
        // Central meridian oval
        rect.set(cx - r * 0.48f, cy - r, cx + r * 0.48f, cy + r)
        canvas.drawOval(rect, paint)
        // Vertical axis
        canvas.drawLine(cx, cy - r, cx, cy + r, paint)
        // Equator
        canvas.drawLine(cx - r, cy, cx + r, cy, paint)
        // Upper and lower latitude curves
        val latY = r * 0.46f
        val latHalfW = r * 0.87f
        canvas.drawLine(cx - latHalfW, cy - latY, cx + latHalfW, cy - latY, paint)
        canvas.drawLine(cx - latHalfW, cy + latY, cx + latHalfW, cy + latY, paint)

        paint.style = Paint.Style.FILL
    }

    fun drawKeyboardDismissIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        paint: Paint,
        rect: RectF,
        path: Path
    ) {
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.078f
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND

        // Mini keyboard outline
        val halfW = size * 0.46f
        val halfH = size * 0.28f
        val topY = cy - size * 0.34f
        rect.set(cx - halfW, topY, cx + halfW, topY + halfH * 2f)
        canvas.drawRoundRect(rect, size * 0.08f, size * 0.08f, paint)

        // Downward chevron below
        val chevY = topY + halfH * 2f + size * 0.14f
        val chevW = size * 0.18f
        val chevH = size * 0.12f
        path.reset()
        path.moveTo(cx - chevW, chevY)
        path.lineTo(cx, chevY + chevH)
        path.lineTo(cx + chevW, chevY)
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
    }
}

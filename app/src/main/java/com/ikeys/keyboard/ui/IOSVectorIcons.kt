package com.ikeys.keyboard.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.ikeys.keyboard.layout.ShiftState

/**
 * Zero-allocation Canvas vector icon renderer matching latest iOS 26 SF-Symbol keyboard glyphs.
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
        val bottomY = if (state == ShiftState.CAPS_LOCK) cy + size * 0.26f else cy + size * 0.40f

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
                val barY = cy + size * 0.43f
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.115f
                canvas.drawLine(cx - stemHalfW * 1.1f, barY, cx + stemHalfW * 1.1f, barY, paint)
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
        val shoulderX = cx - halfW * 0.36f
        val rightX = cx + halfW
        val topY = cy - halfH
        val bottomY = cy + halfH
        val r = size * 0.10f

        path.reset()
        path.moveTo(tipX, cy)
        path.lineTo(shoulderX, topY)
        path.lineTo(rightX - r, topY)
        path.quadTo(rightX, topY, rightX, topY + r)
        path.lineTo(rightX, bottomY - r)
        path.quadTo(rightX, bottomY, rightX - r, bottomY)
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
            paint.strokeWidth = size * 0.088f
            paint.color = fgColor
            canvas.drawPath(path, paint)
        }

        // Centered 'x' inside the delete.left glyph
        val xCx = cx + size * 0.13f
        val xR = size * 0.15f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.092f
        paint.color = if (isPressed) bgColor else fgColor
        canvas.drawLine(xCx - xR, cy - xR, xCx + xR, cy + xR, paint)
        canvas.drawLine(xCx + xR, cy - xR, xCx - xR, cy + xR, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * Crisp iOS 26 SF-Symbol return arrow (return.left).
     */
    fun drawReturnArrowIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        paint: Paint,
        path: Path
    ) {
        val halfW = size * 0.44f
        val topY = cy - size * 0.24f
        val midY = cy + size * 0.12f
        val cornerR = size * 0.11f
        val headSize = size * 0.20f

        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.095f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        // Hooked stem
        path.reset()
        path.moveTo(cx + halfW, topY)
        path.lineTo(cx + halfW, midY - cornerR)
        path.quadTo(cx + halfW, midY, cx + halfW - cornerR, midY)
        path.lineTo(cx - halfW + size * 0.05f, midY)
        canvas.drawPath(path, paint)

        // Left-pointing arrowhead
        path.reset()
        path.moveTo(cx - halfW + headSize, midY - headSize)
        path.lineTo(cx - halfW, midY)
        path.lineTo(cx - halfW + headSize, midY + headSize)
        canvas.drawPath(path, paint)

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
        paint.strokeWidth = size * 0.084f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(cx, cy, r, paint)

        // Eyes
        paint.style = Paint.Style.FILL
        val eyeR = size * 0.058f
        canvas.drawCircle(cx - r * 0.34f, cy - r * 0.17f, eyeR, paint)
        canvas.drawCircle(cx + r * 0.34f, cy - r * 0.17f, eyeR, paint)

        // Smile arc
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.078f
        val smileR = r * 0.56f
        rect.set(cx - smileR, cy - smileR * 0.70f, cx + smileR, cy + smileR * 0.90f)
        canvas.drawArc(rect, 22f, 136f, false, paint)
        paint.style = Paint.Style.FILL
    }

    /**
     * Authentic iOS SF-Symbol Globe icon with curved upper & lower latitude arcs.
     */
    fun drawGlobeIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        paint: Paint,
        rect: RectF,
        path: Path
    ) {
        val r = size * 0.45f
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.074f
        paint.strokeCap = Paint.Cap.ROUND

        // Outer sphere
        canvas.drawCircle(cx, cy, r, paint)
        // Central meridian oval
        rect.set(cx - r * 0.48f, cy - r, cx + r * 0.48f, cy + r)
        canvas.drawOval(rect, paint)
        // Equator
        canvas.drawLine(cx - r, cy, cx + r, cy, paint)
        // Vertical axis
        canvas.drawLine(cx, cy - r, cx, cy + r, paint)

        // Curved upper & lower latitudes (matches SF Symbols `globe`)
        val latY = r * 0.48f
        val latHalfW = r * 0.87f
        val curveDepth = r * 0.18f

        path.reset()
        path.moveTo(cx - latHalfW, cy - latY)
        path.quadTo(cx, cy - latY + curveDepth, cx + latHalfW, cy - latY)
        canvas.drawPath(path, paint)

        path.reset()
        path.moveTo(cx - latHalfW, cy + latY)
        path.quadTo(cx, cy + latY - curveDepth, cx + latHalfW, cy + latY)
        canvas.drawPath(path, paint)

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
        paint.strokeWidth = size * 0.076f
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND

        // Mini keyboard outline
        val halfW = size * 0.47f
        val halfH = size * 0.28f
        val topY = cy - size * 0.35f
        rect.set(cx - halfW, topY, cx + halfW, topY + halfH * 2f)
        canvas.drawRoundRect(rect, size * 0.09f, size * 0.09f, paint)

        // Mini key dots inside the keyboard frame (authentic SF Symbol detail)
        paint.style = Paint.Style.FILL
        val dotR = size * 0.036f
        val row1Y = topY + halfH * 0.65f
        val row2Y = topY + halfH * 1.35f
        val spacing = halfW * 0.42f
        for (i in -1..1) {
            canvas.drawCircle(cx + i * spacing, row1Y, dotR, paint)
        }
        // Mini spacebar line
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.068f
        canvas.drawLine(cx - halfW * 0.42f, row2Y, cx + halfW * 0.42f, row2Y, paint)

        // Downward chevron below
        paint.strokeWidth = size * 0.078f
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

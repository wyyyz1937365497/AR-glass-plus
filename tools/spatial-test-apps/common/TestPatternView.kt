package com.arglass.spatialtest.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Display
import android.view.MotionEvent
import android.view.View

/**
 * Spatial calibration card — pure framework Canvas, zero dependencies.
 *
 * Every element answers a specific debugging question (Gate 2+ regression
 * tool for renderer, stereo, head tracking and picking):
 *  - giant window digit ........... texture swapped between windows?
 *  - TL/TR/BL/BR .................. UV mirror / vertical flip?
 *  - TOP/RIGHT arrows ............. rotation direction?
 *  - X+/Y+ axes ................... content coordinate orientation?
 *  - center cross + grid + circles  projection center; non-uniform stretch
 *                                   (circle -> ellipse is instantly visible)
 *  - outer border ................. crop / overscan
 *  - displayId / WxH / dpi ........ which VirtualDisplay, which content res
 *  - frame counter + moving dot ... OES texture actually refreshing?
 *  - last touch cross + coords .... picking -> injected input chain
 */
class TestPatternView(
    context: Context,
    private val windowIndex: Int,
) : View(context) {

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101418.toInt() }
    private val grid = Paint().apply { color = 0x30354040.toInt() }
    private val midGrid = Paint().apply { color = 0x50607080.toInt() }
    private val frame = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8C4D0.toInt()
        textSize = 20f
    }
    private val digit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val cross = Paint().apply {
        color = 0xFF00E5FF.toInt()
        strokeWidth = 4f
    }
    private val touchCross = Paint().apply {
        color = 0xFFFF5252.toInt()
        strokeWidth = 4f
    }
    private val movingDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF76FF03.toInt()
    }
    private val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD740.toInt()
        textSize = 22f
    }

    private var frameCount = 0L
    private var lastTouchX = -1f
    private var lastTouchY = -1f

    init {
        // Continuous animation: proves the OES texture keeps updating.
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        frameCount++
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bg)

        // Regular grid (8 columns x 4.5-ish rows scaled).
        val cell = w / 12f
        var x = cell
        var i = 0
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, if (i % 3 == 0) midGrid else grid)
            x += cell
            i++
        }
        var y = cell
        i = 0
        while (y < h) {
            canvas.drawLine(0f, y, w, y, if (i % 3 == 0) midGrid else grid)
            y += cell
            i++
        }

        // Stretch-probe circles: inscribed + centered (ellipse = wrong scale).
        val mid = Paint().apply {
            color = 0x6000E5FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(w / 2f, h / 2f, h / 3f, mid)
        canvas.drawCircle(w / 2f, h / 2f, h / 6f, mid)

        // Giant window digit (dominant identity marker).
        digit.textSize = h * 0.55f
        canvas.drawText(windowIndex.toString(), w / 2f, h / 2f + h * 0.2f, digit)

        // Center cross.
        canvas.drawLine(w / 2f - 30f, h / 2f, w / 2f + 30f, h / 2f, cross)
        canvas.drawLine(w / 2f, h / 2f - 30f, w / 2f, h / 2f + 30f, cross)

        // Corner labels: mirror/flip probe.
        val pad = 34f
        canvas.drawText("TL", pad, pad + 10f, text)
        canvas.drawText("TR", w - pad - 34f, pad + 10f, text)
        canvas.drawText("BL", pad, h - pad, text)
        canvas.drawText("BR", w - pad - 40f, h - pad, text)

        // Direction arrows (top edge / right edge).
        canvas.drawText("TOP ↑", w / 2f - 34f, pad + 10f, text)
        canvas.drawText("RIGHT →", w - pad - 108f, h / 2f + 8f, text)

        // Android content axes: origin top-left, +X right, +Y down.
        canvas.drawText("X+ →", w / 2f - 130f, h / 2f + 8f, axis)
        canvas.drawText("Y+ ↓", w / 2f + 40f, h / 2f + 46f, axis)

        // Outer border: crop / overscan probe.
        canvas.drawRect(3f, 3f, w - 3f, h - 3f, frame)

        // Telemetry strip.
        val display: Display? = this.display
        val dpi = resources.displayMetrics.densityDpi
        val line1 = "window=$windowIndex display=${display?.displayId ?: -1}"
        val line2 = "${width}×${height} @${dpi}dpi"
        val line3 = "frame=$frameCount"
        canvas.drawText(line1, pad, h - pad - 92f, small)
        canvas.drawText(line2, pad, h - pad - 64f, small)
        canvas.drawText(line3, pad, h - pad - 36f, small)

        // Touch feedback: last injected/input position.
        if (lastTouchX >= 0f) {
            canvas.drawLine(lastTouchX - 26f, lastTouchY, lastTouchX + 26f, lastTouchY, touchCross)
            canvas.drawLine(lastTouchX, lastTouchY - 26f, lastTouchX, lastTouchY + 26f, touchCross)
            canvas.drawText("TOUCH x=${lastTouchX.toInt()} y=${lastTouchY.toInt()}", lastTouchX + 32f, lastTouchY - 20f, small)
        }

        // Moving marker: horizontal sweep proves live updates.
        val sweep = (frameCount % 180) / 180f
        canvas.drawCircle(pad + (w - 2 * pad) * sweep, h * 0.12f, 12f, movingDot)

        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastTouchX = event.x
        lastTouchY = event.y
        invalidate()
        return true
    }
}

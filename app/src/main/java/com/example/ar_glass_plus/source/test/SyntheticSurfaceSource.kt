package com.example.ar_glass_plus.source.test

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Surface
import com.example.ar_glass_plus.source.FrameSource
import com.example.ar_glass_plus.source.SourceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Test-only frame producer: draws dynamic frames (moving block, scroll bar,
 * frame counter, timestamp) into the input Surface with Canvas — no GL, no
 * extra renderer, keeping the debug chain simple. Frame rate is visible on
 * the glasses, so stalls/flips/UV issues are immediately obvious.
 */
class SyntheticSurfaceSource(private val fps: Int = 30) : FrameSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var frameCount = 0

    override suspend fun start(output: Surface, config: SourceConfig) {
        job?.cancel()
        frameCount = 0
        val startMs = System.currentTimeMillis()
        job = scope.launch {
            while (isActive) {
                drawFrame(output, frameCount, startMs)
                frameCount++
                delay((1000L / fps).coerceAtLeast(1L))
            }
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
    }

    private fun drawFrame(surface: Surface, frame: Int, startMs: Long) {
        val canvas = try {
            surface.lockCanvas(null) ?: return
        } catch (e: Exception) {
            return
        }
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val t = (frame % 100) / 100f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // background alternates per frame (liveness probe)
        canvas.drawColor(if (frame % 2 == 0) 0xFF101418.toInt() else 0xFF1A222C.toInt())

        // color strip across the top
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, w / 4f, h * 0.08f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(w / 4f, 0f, w / 2f, h * 0.08f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(w / 2f, 0f, 3f * w / 4f, h * 0.08f, paint)
        paint.color = Color.WHITE
        canvas.drawRect(3f * w / 4f, 0f, w, h * 0.08f, paint)

        // diagonal-moving block (motion probe)
        val bx = t * w
        val by = (1f - t) * h
        paint.color = 0xFFFFA500.toInt()
        canvas.drawRect(bx - 40f, by - 40f, bx + 40f, by + 40f, paint)

        // horizontal scroll bar at mid height (scroll/phase probe)
        paint.color = 0xFF00E5FF.toInt()
        canvas.drawRect(t * w, h * 0.5f, t * w + w * 0.2f, h * 0.5f + h * 0.02f, paint)

        // frame counter + timestamp + resolution
        paint.color = Color.WHITE
        paint.textSize = h * 0.07f
        canvas.drawText("FRAME %05d".format(frame), w * 0.05f, h * 0.85f, paint)
        val elapsed = (System.currentTimeMillis() - startMs) / 1000f
        canvas.drawText(String.format("%.1fs", elapsed), w * 0.05f, h * 0.92f, paint)
        paint.textSize = h * 0.05f
        canvas.drawText("${canvas.width}x${canvas.height}", w * 0.05f, h * 0.99f, paint)

        surface.unlockCanvasAndPost(canvas)
    }
}

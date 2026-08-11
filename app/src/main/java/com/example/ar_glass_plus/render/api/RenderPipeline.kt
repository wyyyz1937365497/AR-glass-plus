package com.example.ar_glass_plus.render.api

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Caller-facing pipeline: drives the backend frame clock and releases it.
 * FrameSource wiring lands with the GL implementation — this stays the single
 * entry point so UI code never touches the backend directly.
 */
class RenderPipeline(private val backend: RenderBackend) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var frameJob: Job? = null
    private var started = false

    val isStarted: Boolean get() = started

    /** Start rendering at [target] with [config] and an optional frame rate. */
    fun start(
        target: RenderTarget,
        config: RenderConfig,
        targetFps: Int = 60,
    ) {
        if (started) return
        backend.initialize(target, config)
        started = true
        val frameIntervalMs = 1000L / targetFps
        frameJob = scope.launch {
            while (isActive) {
                val t0 = System.nanoTime()
                backend.renderFrame()
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                val wait = frameIntervalMs - elapsedMs
                if (wait > 0) delay(wait)
            }
        }
        Log.i(TAG, "pipeline started: ${config.outputWidth}x${config.outputHeight} ${config.mode}")
    }

    fun stop() {
        if (!started) return
        frameJob?.cancel()
        frameJob = null
        backend.release()
        started = false
        Log.i(TAG, "pipeline stopped")
    }

    fun setRenderMode(mode: RenderMode) {
        backend.setRenderMode(mode)
    }

    private companion object {
        const val TAG = "RenderPipeline"
    }
}

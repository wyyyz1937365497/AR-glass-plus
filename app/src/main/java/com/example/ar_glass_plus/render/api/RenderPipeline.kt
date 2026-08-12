package com.example.ar_glass_plus.render.api

import android.util.Log
import com.example.ar_glass_plus.render.geometry.GeometryConfig

/**
 * Caller-facing coordinator between UI and backend. Does NOT own a frame
 * clock: while a GLSurfaceView drives the frames (P2.1), the surface view's
 * own render loop calls the backend; this only wires lifecycle and mode.
 * A custom frame scheduler is only re-evaluated when late-update/
 * reprojection/head-pose pacing requires it.
 */
class RenderPipeline(private val backend: RenderBackend) {

    private var started = false

    val isStarted: Boolean get() = started

    fun start(target: RenderTarget, config: RenderConfig) {
        if (started) return
        backend.initialize(target, config)
        started = true
        Log.i(TAG, "started: ${config.outputWidth}x${config.outputHeight} ${config.mode}")
    }

    fun setRenderMode(mode: RenderMode) {
        backend.setRenderMode(mode)
    }

    fun setGeometryConfig(config: GeometryConfig) {
        backend.setGeometryConfig(config)
    }

    fun stop() {
        if (!started) return
        backend.release()
        started = false
        Log.i(TAG, "stopped")
    }

    private companion object {
        const val TAG = "RenderPipeline"
    }
}

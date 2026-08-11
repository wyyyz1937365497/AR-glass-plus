package com.example.ar_glass_plus.render.gl

import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import com.example.ar_glass_plus.source.FrameSource
import com.example.ar_glass_plus.source.SourceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Generic GPU frame input: OES texture ← SurfaceTexture ← Surface ← FrameSource.
 *
 * Threading contract:
 * - create()/draw()/release() run on the GL thread only.
 * - OnFrameAvailableListener only sets a flag and requests a render; it never
 *   touches GL.
 * - The producer (FrameSource) runs on its own coroutine writing into Surface.
 */
class GlFrameInput(
    private val surfaceView: GLSurfaceView,
    private val source: FrameSource,
    private val program: GlExternalTextureProgram,
    private val config: SourceConfig,
) {

    private var texture: GlExternalTexture? = null
    private var producerJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob())
    @Volatile
    private var framePending = false

    private val frameListener = SurfaceTexture.OnFrameAvailableListener {
        framePending = true
        surfaceView.requestRender()
    }

    /** GL thread only. */
    fun create() {
        texture = GlExternalTexture().apply {
            create()
            setDefaultBufferSize(config.width, config.height)
            surfaceTexture?.setOnFrameAvailableListener(frameListener)
        }
        producerJob = scope.launch(Dispatchers.Default) {
            source.start(texture!!.surface!!, config)
        }
    }

    /** GL thread only. */
    fun draw() {
        val tex = texture ?: return
        if (framePending) {
            tex.updateTexImage()
            framePending = false
        }
        program.draw(tex.textureId, tex.transformMatrix)
    }

    /** GL thread only. */
    fun release() {
        source.stop()
        producerJob?.cancel()
        producerJob = null
        texture?.release()
        texture = null
        framePending = false
        scope.cancel()
    }
}

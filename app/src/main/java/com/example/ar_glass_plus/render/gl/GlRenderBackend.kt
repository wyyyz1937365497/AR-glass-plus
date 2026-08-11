package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.example.ar_glass_plus.render.api.RenderBackend
import com.example.ar_glass_plus.render.api.RenderConfig
import com.example.ar_glass_plus.render.api.RenderMode
import com.example.ar_glass_plus.render.api.RenderTarget
import com.example.ar_glass_plus.source.FrameSource
import com.example.ar_glass_plus.source.SourceConfig

/**
 * OpenGL ES 3.0 backend. GL surface lifecycle is driven by GLSurfaceView:
 * [onGlContextCreated] runs on the GL thread after the EGL context exists;
 * [resize]/[renderFrame] come from the surface view's own render loop.
 */
class GlRenderBackend : RenderBackend {

    private var program: GlProgram? = null
    private var pattern: GlTestPattern? = null
    private var externalProgram: GlExternalTextureProgram? = null
    private var frameInput: GlFrameInput? = null
    private var surfaceViewRef: GLSurfaceView? = null
    private var source: FrameSource? = null
    private var sourceConfig: SourceConfig? = null
    private var mode = RenderMode.PASSTHROUGH_2D
    private var viewportW = 0
    private var viewportH = 0
    private var contextReady = false

    /**
     * Attach a frame producer before the GL context exists (called from the
     * activity on the main thread). Only stores references — all GL resource
     * creation is deferred to onGlContextCreated (GL thread). When set, drawn
     * content comes from the OES input instead of the built-in test pattern.
     */
    fun attachSource(surfaceView: GLSurfaceView, source: FrameSource, config: SourceConfig) {
        surfaceViewRef = surfaceView
        this.source = source
        sourceConfig = config
    }

    override fun initialize(target: RenderTarget, config: RenderConfig) {
        mode = config.mode
        Log.i(TAG, "initialize: target ${config.outputWidth}x${config.outputHeight}, mode=$mode")
        // Actual GL resource creation happens in onGlContextCreated (GL thread).
    }

    /** Called from GlSurfaceRenderer.onSurfaceCreated — EGL context is live. */
    fun onGlContextCreated() {
        program = GlProgram(VERTEX_SRC, FRAGMENT_SRC)
        pattern = GlTestPattern(program!!)
        val src = source
        val sv = surfaceViewRef
        val cfg = sourceConfig
        if (src != null && sv != null && cfg != null) {
            externalProgram = GlExternalTextureProgram()
            frameInput = GlFrameInput(sv, src, externalProgram!!, cfg)
            frameInput!!.create()
        }
        contextReady = true
        Log.i(TAG, "GL context ready (GLES 3.0)")
    }

    override fun resize(width: Int, height: Int) {
        viewportW = width
        viewportH = height
        // Acceptance: log the REAL framebuffer size, never assume 1920x1080.
        Log.i(TAG, "framebuffer: ${width}x${height}")
    }

    override fun setRenderMode(mode: RenderMode) {
        this.mode = mode
        Log.i(TAG, "mode -> $mode")
    }

    override fun renderFrame() {
        if (!contextReady) return
        GLES30.glClearColor(0.05f, 0.05f, 0.08f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        when (mode) {
            RenderMode.PASSTHROUGH_2D ->
                drawViewport(0, 0, viewportW, viewportH)

            RenderMode.SBS_DUPLICATE -> {
                val left = viewportW / 2
                drawViewport(0, 0, left, viewportH)
                drawViewport(left, 0, viewportW - left, viewportH)
            }

            RenderMode.SBS_STEREO -> {
                Log.w(TAG, "SBS_STEREO not implemented; falling back to PASSTHROUGH_2D")
                drawViewport(0, 0, viewportW, viewportH)
            }
        }
    }

    override fun release() {
        frameInput?.release()
        frameInput = null
        externalProgram?.delete()
        externalProgram = null
        pattern?.delete()
        pattern = null
        program?.delete()
        program = null
        contextReady = false
        Log.i(TAG, "released")
    }

    private fun drawViewport(x: Int, y: Int, w: Int, h: Int) {
        GLES30.glViewport(x, y, w, h)
        val input = frameInput
        if (input != null) {
            input.draw()
        } else {
            program?.use()
            pattern?.draw()
        }
    }

    private companion object {
        const val TAG = "GlRenderBackend"

        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            out vec4 fragColor;

            void main() {
                vec2 uv = vUv;
                vec4 base;

                // RGB quadrants + white
                if (uv.x < 0.5 && uv.y > 0.5)      base = vec4(1.0, 0.0, 0.0, 1.0);
                else if (uv.x >= 0.5 && uv.y > 0.5) base = vec4(0.0, 1.0, 0.0, 1.0);
                else if (uv.x < 0.5)                base = vec4(0.0, 0.0, 1.0, 1.0);
                else                                base = vec4(1.0, 1.0, 1.0, 1.0);

                // smooth gradient ring (aspect-ratio probe)
                vec2 c = uv - 0.5;
                if (max(abs(c.x), abs(c.y)) < 0.30) {
                    base = vec4(uv, 0.5, 1.0);
                }

                // checkerboard core (resolution/scaling probe)
                if (max(abs(c.x), abs(c.y)) < 0.20) {
                    vec2 cell = floor(uv * 20.0);
                    float on = mod(cell.x + cell.y, 2.0);
                    base = mix(vec4(0.05), vec4(0.95), on);
                }

                // center cross (alignment probe)
                if (abs(c.x) < 0.015 || abs(c.y) < 0.015) {
                    base = mix(base, vec4(0.0, 0.0, 0.0, 1.0), 0.8);
                }

                // border (overscan probe)
                float b = 0.015;
                if (uv.x < b || uv.x > 1.0 - b || uv.y < b || uv.y > 1.0 - b) {
                    base = vec4(1.0, 1.0, 1.0, 1.0);
                }

                // L/R markers: green bar left half, red bar right half
                if (uv.x < 0.03 && uv.y > 0.5) base = vec4(0.0, 1.0, 0.0, 1.0);
                if (uv.x > 0.97 && uv.y > 0.5) base = vec4(1.0, 0.0, 0.0, 1.0);

                fragColor = base;
            }
        """.trimIndent()
    }
}

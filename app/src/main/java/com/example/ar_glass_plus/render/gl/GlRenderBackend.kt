package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.example.ar_glass_plus.render.api.RenderBackend
import com.example.ar_glass_plus.render.api.RenderConfig
import com.example.ar_glass_plus.render.geometry.RenderMode
import com.example.ar_glass_plus.render.api.RenderTarget
import com.example.ar_glass_plus.render.geometry.GeometryConfig
import com.example.ar_glass_plus.render.geometry.GeometryResolver
import com.example.ar_glass_plus.render.geometry.PixelRect
import com.example.ar_glass_plus.render.geometry.PixelSize
import com.example.ar_glass_plus.render.geometry.RenderLayoutSnapshot
import com.example.ar_glass_plus.render.geometry.RenderLayoutStore
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
    private var geometryConfig = GeometryConfig()
    private val resolver = GeometryResolver()
    private var viewportW = 0
    private var viewportH = 0
    private var contextReady = false
    private var sourceSize: PixelSize = PixelSize(0f, 0f)
    private var generation = 0L
    private var lastMode: RenderMode? = null
    private var lastConfig: GeometryConfig? = null

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
        sourceSize = PixelSize(config.width.toFloat(), config.height.toFloat())
    }

    /** Runtime geometry update — re-resolves next frame, rebuilds nothing. */
    override fun setGeometryConfig(config: GeometryConfig) {
        geometryConfig = config
        Log.i(TAG, "geometry -> ${config.aspectMode} ${config.rotation}")
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
        GLES30.glClearColor(0.02f, 0.02f, 0.03f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val input = frameInput
        if (input != null) {
            if (mode != lastMode || geometryConfig != lastConfig) {
                generation++
                lastMode = mode
                lastConfig = geometryConfig
            }
            val regions = modeRegions(mode, viewportW, viewportH)
            val resolved = regions.map { resolver.resolve(sourceSize, it, geometryConfig) }
            RenderLayoutStore.publish(
                RenderLayoutSnapshot(
                    generation = generation,
                    outputWidth = viewportW,
                    outputHeight = viewportH,
                    mode = mode,
                    regions = resolved,
                ),
            )
            for (geometry in resolved) {
                input.draw(geometry, viewportW, viewportH)
            }
        } else {
            // No producer attached: fall back to the built-in test pattern.
            program?.use()
            pattern?.draw()
        }
    }

    private fun modeRegions(mode: RenderMode, w: Int, h: Int): List<PixelRect> = when (mode) {
        RenderMode.PASSTHROUGH_2D -> listOf(PixelRect(0f, 0f, w.toFloat(), h.toFloat()))
        RenderMode.SBS_DUPLICATE -> {
            val half = w / 2
            listOf(
                PixelRect(0f, 0f, half.toFloat(), h.toFloat()),
                PixelRect(half.toFloat(), 0f, w.toFloat(), h.toFloat()),
            )
        }
        RenderMode.SBS_STEREO -> {
            Log.w(TAG, "SBS_STEREO not implemented; falling back to PASSTHROUGH_2D")
            listOf(PixelRect(0f, 0f, w.toFloat(), h.toFloat()))
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
        RenderLayoutStore.clear()
        Log.i(TAG, "released")
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

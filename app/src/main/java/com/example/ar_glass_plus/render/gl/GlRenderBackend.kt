package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.example.ar_glass_plus.render.api.RenderBackend
import com.example.ar_glass_plus.render.api.RenderConfig
import com.example.ar_glass_plus.render.api.RenderTarget
import com.example.ar_glass_plus.render.geometry.GeometryConfig
import com.example.ar_glass_plus.render.geometry.GeometryMapper
import com.example.ar_glass_plus.render.geometry.GeometryResolver
import com.example.ar_glass_plus.render.geometry.PixelPoint
import com.example.ar_glass_plus.render.geometry.PixelRect
import com.example.ar_glass_plus.render.geometry.PixelSize
import com.example.ar_glass_plus.render.geometry.RenderLayoutSnapshot
import com.example.ar_glass_plus.render.geometry.RenderLayoutStore
import com.example.ar_glass_plus.render.geometry.RenderMode
import com.example.ar_glass_plus.render.geometry.ResolvedGeometry
import com.example.ar_glass_plus.render.geometry.TileLayout
import com.example.ar_glass_plus.render.geometry.WindowLayoutSnapshot
import com.example.ar_glass_plus.render.overlay.CursorOverlayState
import com.example.ar_glass_plus.source.FrameSource
import com.example.ar_glass_plus.source.SourceConfig
import com.example.ar_glass_plus.workspace.SpatialWindowModel

/**
 * OpenGL ES 3.0 backend, multi-window: each SpatialWindow contributes one
 * WindowNode (FrameSource + slot + OES input); nodes compose into the 2x2
 * tile grid (multi-VD bring-up compositor) inside a single GLSurfaceView
 * frame. Threading contract:
 *
 * - The window map is GL-thread confined. Every mutation is posted through
 *   [GLSurfaceView.queueEvent]; renderFrame reads it lock-free on the same
 *   thread.
 * - All GL resources (inputs, programs, renderers) are created AND deleted
 *   on the GL thread — including teardown (release posts deletes).
 */
class GlRenderBackend : RenderBackend {

    private class WindowNode(
        val key: Long,
        val source: FrameSource,
        val config: SourceConfig,
        var slot: Int,
        var input: GlFrameInput? = null,
    ) {
        val sourceSize: PixelSize get() = PixelSize(config.width.toFloat(), config.height.toFloat())
    }

    private var program: GlProgram? = null
    private var pattern: GlTestPattern? = null
    private var externalProgram: GlExternalTextureProgram? = null
    private var cursorRenderer: GlCursorRenderer? = null
    private var outlineRenderer: GlTileOutlineRenderer? = null
    private var surfaceViewRef: GLSurfaceView? = null

    /** GL-thread confined (mutations posted via queueEvent). */
    private val windows = LinkedHashMap<Long, WindowNode>()
    private var focusedKey: Long? = null

    private var mode = RenderMode.PASSTHROUGH_2D
    private var geometryConfig = GeometryConfig()
    private val resolver = GeometryResolver()
    private var viewportW = 0
    private var viewportH = 0
    private var contextReady = false
    private var generation = 0L
    private var lastMode: RenderMode? = null
    private var lastConfig: GeometryConfig? = null
    private var released = false

    /**
     * Register a window's frame producer. Main thread; safe before or after
     * GL context creation (resource creation is deferred to the GL thread).
     */
    fun attachWindowSource(
        key: Long,
        surfaceView: GLSurfaceView,
        source: FrameSource,
        config: SourceConfig,
        slot: Int,
    ) {
        surfaceViewRef = surfaceView
        postMutation(surfaceView, key) {
            windows[key] = WindowNode(key, source, config, slot)
            if (contextReady) createNodeInput(windows.getValue(key))
            Log.i(TAG, "window $key attach slot $slot (nodes=${windows.size})")
        }
    }

    /** Drop a window and release its OES input (GL thread). */
    fun detachWindowSource(key: Long) {
        postMutation(key) { node ->
            node.input?.release()
            windows.remove(key)
            if (focusedKey == key) focusedKey = null
            Log.i(TAG, "window $key detach (nodes=${windows.size})")
        }
    }

    /** Slot reuse after a close: reposition the window's tile. No-op when unchanged. */
    fun setWindowSlot(key: Long, slot: Int) {
        postMutation(key) { node ->
            if (node.slot != slot) {
                node.slot = slot
                Log.i(TAG, "window $key slot -> $slot")
            }
        }
    }

    /** Focus marker (cursor + outline target); null clears it. */
    fun setFocusedWindow(key: Long?) {
        val sv = surfaceViewRef ?: return
        sv.queueEvent {
            if (released) return@queueEvent
            if (focusedKey != key) {
                focusedKey = key
                Log.i(TAG, "focused window -> ${key ?: "none"}")
            }
        }
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
        // Surface re-creation: release stale inputs first (leak fix). Their
        // sources observe stop(), the host tears the windows down, and fresh
        // ones re-attach — equivalent to the previous single-input behavior.
        if (contextReady) {
            Log.w(TAG, "GL context re-created; releasing ${windows.size} stale window input(s)")
            windows.values.forEach { it.input?.release() }
            windows.values.forEach { it.input = null }
            externalProgram?.delete()
            externalProgram = null
            cursorRenderer?.delete()
            outlineRenderer?.delete()
        }
        program = GlProgram(VERTEX_SRC, FRAGMENT_SRC)
        pattern = GlTestPattern(program!!)
        if (windows.isNotEmpty()) externalProgram = GlExternalTextureProgram()
        cursorRenderer = GlCursorRenderer().also { it.onContextCreated() }
        outlineRenderer = GlTileOutlineRenderer().also { it.onContextCreated() }
        windows.values.forEach { createNodeInput(it) }
        contextReady = true
        Log.i(TAG, "GL context ready (GLES 3.0, windows=${windows.size})")
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

        val nodes = windows.values.toList()
        if (nodes.isEmpty()) {
            // No window attached: fall back to the built-in test pattern.
            program?.use()
            pattern?.draw()
            return
        }

        if (mode != lastMode || geometryConfig != lastConfig) {
            generation++
            lastMode = mode
            lastConfig = geometryConfig
        }
        val regions = modeRegions(mode, viewportW, viewportH)
        val layoutNodes = nodes.sortedBy { it.slot }
        val geometries = layoutNodes.associateWith { node ->
            regions.map {
                resolver.resolve(
                    node.sourceSize,
                    TileLayout.tileRect(node.slot, SpatialWindowModel.TILE_COLUMNS, SpatialWindowModel.TILE_ROWS, it),
                    geometryConfig,
                )
            }
        }
        RenderLayoutStore.publish(
            RenderLayoutSnapshot(
                generation = generation,
                outputWidth = viewportW,
                outputHeight = viewportH,
                mode = mode,
                windows = layoutNodes.map { node ->
                    WindowLayoutSnapshot(
                        windowKey = node.key,
                        slot = node.slot,
                        regions = geometries.getValue(node),
                    )
                },
            ),
        )
        for (node in layoutNodes) {
            val nodeGeometries = geometries.getValue(node)
            val input = node.input
            if (input != null) {
                nodeGeometries.forEach { input.draw(it, viewportW, viewportH) }
            }
            if (node.key == focusedKey) {
                nodeGeometries.forEach { drawCursor(it) }
                nodeGeometries.forEach {
                    outlineRenderer?.draw(it.targetRegion, OUTLINE_THICKNESS_PX, viewportW, viewportH, OUTLINE_COLOR)
                }
            }
        }
    }

    override fun release() {
        val sv = surfaceViewRef
        if (sv != null) {
            // GL-thread deletes (leak fix: never call GL from the main
            // thread). Runs unconditionally — this IS the terminal cleanup.
            sv.queueEvent {
                released = true
                windows.values.forEach { it.input?.release() }
                windows.clear()
                externalProgram?.delete()
                externalProgram = null
                cursorRenderer?.delete()
                cursorRenderer = null
                outlineRenderer?.delete()
                outlineRenderer = null
                pattern?.delete()
                pattern = null
                program?.delete()
                program = null
                contextReady = false
                Log.i(TAG, "released (GL thread)")
            }
        } else {
            released = true
            Log.w(TAG, "released without surface view; GL objects die with the context")
        }
        RenderLayoutStore.clear()
    }

    // ── helpers ──

    /**
     * Posts a window mutation to the GL thread. [key] is informational for
     * logging when the surface view is gone. Mutations after release are
     * dropped (their sources are released by then).
     */
    private inline fun postMutation(surfaceView: GLSurfaceView, key: Long, crossinline block: () -> Unit) {
        surfaceView.queueEvent {
            if (released) {
                Log.w(TAG, "drop post-release mutation for window $key")
                return@queueEvent
            }
            block()
        }
    }

    private inline fun postMutation(key: Long, crossinline block: (WindowNode) -> Unit) {
        val sv = surfaceViewRef
        if (sv == null) {
            Log.w(TAG, "no surface view; dropping mutation for window $key")
            return
        }
        postMutation(sv, key) {
            windows[key]?.let(block)
                ?: Log.w(TAG, "mutation skipped: window $key not attached")
        }
    }

    /** GL thread only: create the OES input for one node. */
    private fun createNodeInput(node: WindowNode) {
        val sv = surfaceViewRef ?: return
        val shared = externalProgram ?: GlExternalTextureProgram().also { externalProgram = it }
        node.input = GlFrameInput(sv, node.source, shared, node.config).also { it.create() }
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

    /** Draw the shared cursor (content space) inside one resolved region. */
    private fun drawCursor(geometry: ResolvedGeometry) {
        val c = CursorOverlayState.cursor.value ?: return
        if (!c.visible) return
        val out = GeometryMapper.mapContentToOutput(
            PixelPoint(c.x, c.y),
            geometry,
        ) ?: return
        cursorRenderer?.draw(
            out,
            CursorOverlayState.style.value,
            viewportW,
            viewportH,
        )
    }

    private companion object {
        const val TAG = "GlRenderBackend"
        const val OUTLINE_THICKNESS_PX = 2f
        const val OUTLINE_COLOR = 0xFFFFFFFF.toInt()

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

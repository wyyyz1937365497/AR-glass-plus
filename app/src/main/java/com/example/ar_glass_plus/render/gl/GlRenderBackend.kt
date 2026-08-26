package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.example.ar_glass_plus.render.api.RenderBackend
import com.example.ar_glass_plus.render.api.RenderConfig
import com.example.ar_glass_plus.render.api.RenderTarget
import com.example.ar_glass_plus.render.geometry.GeometryConfig
import com.example.ar_glass_plus.render.geometry.PixelPoint
import com.example.ar_glass_plus.render.geometry.PixelRect
import com.example.ar_glass_plus.render.geometry.RenderLayoutSnapshot
import com.example.ar_glass_plus.render.geometry.RenderLayoutStore
import com.example.ar_glass_plus.render.geometry.RenderMode
import com.example.ar_glass_plus.render.geometry.WindowLayoutSnapshot
import com.example.ar_glass_plus.render.overlay.CursorOverlayState
import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.Quat
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.SpatialProjection
import com.example.ar_glass_plus.render.spatial.SpatialPoseRef
import com.example.ar_glass_plus.render.spatial.StereoCamera
import com.example.ar_glass_plus.render.spatial.Vec3
import com.example.ar_glass_plus.source.FrameSource
import com.example.ar_glass_plus.source.SourceConfig

/**
 * OpenGL ES 3.0 backend, spatial (Gate 2): each SpatialWindow is one
 * world-space quad rendered under projection·view·model. Window placement
 * comes EXCLUSIVELY from SpatialPoseRef (position/orientation/size meters);
 * there are no slots or tiles in the final layout. Threading contract
 * unchanged from Gate 1:
 *
 * - The window map is GL-thread confined; mutations are posted through
 *   [GLSurfaceView.queueEvent], renderFrame reads it lock-free.
 * - All GL resources are created AND deleted on the GL thread.
 *
 * Render modes:
 * - PASSTHROUGH_2D: one full-viewport region, mono camera.
 * - SBS_DUPLICATE: two half regions, SAME mono camera duplicated (Gate 1
 *   behavior preserved).
 * - SBS_STEREO: two half regions with a parallel StereoCamera rig
 *   (left eye → left half, right eye → right half).
 */
class GlRenderBackend : RenderBackend {

    /** Shared by all GlFrameInputs; unused on the spatial draw path. */
    private var legacyProgram: GlExternalTextureProgram? = null

    private class WindowNode(
        val key: Long,
        val source: FrameSource,
        val config: SourceConfig,
        var pose: SpatialPoseRef,
        var input: GlFrameInput? = null,
    )

    private var testProgram: GlProgram? = null
    private var pattern: GlTestPattern? = null
    private var spatialProgram: GlSpatialOesProgram? = null
    private var cursorRenderer: GlCursorRenderer? = null
    private var outlineRenderer: GlQuadLineRenderer? = null
    private var surfaceViewRef: GLSurfaceView? = null

    /** GL-thread confined (mutations posted via queueEvent). */
    private val windows = LinkedHashMap<Long, WindowNode>()
    private var focusedKey: Long? = null

    private var mode = RenderMode.PASSTHROUGH_2D
    private var camera: SpatialCamera = SpatialCamera.STATIC_HEAD
    private var stereo = StereoCamera()
    private var viewportW = 0
    private var viewportH = 0
    private var contextReady = false
    private var generation = 0L
    private var lastMode: RenderMode? = null
    private var lastCameraPos: Vec3? = null
    private var lastCameraOrient: Quat? = null
    private var released = false

    /**
     * Register a window's frame producer with its spatial pose. Main thread;
     * safe before or after GL context creation.
     */
    fun attachWindowSource(
        key: Long,
        surfaceView: GLSurfaceView,
        source: FrameSource,
        config: SourceConfig,
        pose: SpatialPoseRef,
    ) {
        surfaceViewRef = surfaceView
        postMutation(surfaceView, key) {
            windows[key] = WindowNode(key, source, config, pose)
            if (contextReady) createNodeInput(windows.getValue(key))
            Log.i(TAG, "window $key attach at ${pose.position} (nodes=${windows.size})")
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

    /** Window pose changed (move/rotate/resize) — re-render next frame. */
    fun setWindowPose(key: Long, pose: SpatialPoseRef) {
        postMutation(key) { node ->
            node.pose = pose
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

    /** Head/camera update (static in Gate 2; head tracking later). */
    fun setCamera(newCamera: SpatialCamera) {
        camera = newCamera
        stereo = StereoCamera(head = newCamera)
    }

    /**
     * Tile-era geometry knobs (FIT/FILL/rotation). The spatial renderer
     * places windows purely by pose; content-fit inside a quad returns with
     * the adaptive-resolution stage.
     */
    override fun setGeometryConfig(config: GeometryConfig) {
        Log.i(TAG, "geometry config ignored in spatial renderer (${config.aspectMode} ${config.rotation})")
    }

    override fun initialize(target: RenderTarget, config: RenderConfig) {
        mode = config.mode
        Log.i(TAG, "initialize: target ${config.outputWidth}x${config.outputHeight}, mode=$mode")
        // Actual GL resource creation happens in onGlContextCreated (GL thread).
    }

    /** Called from GlSurfaceRenderer.onSurfaceCreated — EGL context is live. */
    fun onGlContextCreated() {
        if (contextReady) {
            Log.w(TAG, "GL context re-created; releasing ${windows.size} stale window input(s)")
            windows.values.forEach { it.input?.release() }
            windows.values.forEach { it.input = null }
            spatialProgram?.delete()
            spatialProgram = null
            legacyProgram?.delete()
            legacyProgram = null
            cursorRenderer?.delete()
            outlineRenderer?.delete()
        }
        testProgram = GlProgram(VERTEX_SRC, FRAGMENT_SRC)
        pattern = GlTestPattern(testProgram!!)
        if (windows.isNotEmpty()) spatialProgram = GlSpatialOesProgram()
        cursorRenderer = GlCursorRenderer().also { it.onContextCreated() }
        outlineRenderer = GlQuadLineRenderer().also { it.onContextCreated() }
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
            GLES30.glViewport(0, 0, viewportW, viewportH)
            testProgram?.use()
            pattern?.draw()
            return
        }

        if (mode != lastMode || camera.position != lastCameraPos || camera.orientation != lastCameraOrient) {
            generation++
            lastMode = mode
            lastCameraPos = camera.position
            lastCameraOrient = camera.orientation
        }
        val regions = modeRegions(mode, viewportW, viewportH)
        var canonicalQuads: MutableMap<Long, List<PixelPoint>?>? = null

        for ((index, region) in regions.withIndex()) {
            val regionW = region.width.toInt()
            val regionH = region.height.toInt()
            GLES30.glViewport(region.left.toInt(), flipY(region.bottom, viewportH), regionW.coerceAtLeast(1), regionH.coerceAtLeast(1))

            val eye = when (mode) {
                RenderMode.SBS_STEREO -> if (index == 0) stereo.leftEye else stereo.rightEye
                else -> camera
            }
            val aspect = regionW.toFloat() / regionH.coerceAtLeast(1)
            val viewProj = Mat4.perspective(
                SpatialCamera.DEFAULT_FOV_Y_DEGREES,
                aspect,
                SpatialCamera.DEFAULT_NEAR_METERS,
                SpatialCamera.DEFAULT_FAR_METERS,
            ) * eye.viewMatrix()

            // Painter's order: farthest window first (quads do not intersect).
            val ordered = nodes.sortedByDescending { node ->
                val view = eye.viewMatrix().transform(node.pose.position.x, node.pose.position.y, node.pose.position.z, 1f)
                -view[2] // camera-space -z distance
            }

            for (node in ordered) {
                val mvp = viewProj * SpatialProjection.windowModelMatrix(node.pose)
                node.input?.drawSpatial(program(), mvp)
                if (node.key == focusedKey) {
                    drawCursor(node.pose, node.config.width.toFloat(), node.config.height.toFloat(), viewProj, regionW, regionH)
                    drawOutline(node.pose, viewProj, regionW, regionH)
                }
                if (index == 0) {
                    // Canonical region (mono / left eye) feeds the snapshot.
                    val quads = canonicalQuads ?: LinkedHashMap<Long, List<PixelPoint>?>().also { canonicalQuads = it }
                    quads[node.key] = SpatialProjection.projectWindowCorners(node.pose, viewProj, regionW.toFloat(), regionH.toFloat())
                }
            }
        }

        RenderLayoutStore.publish(
            RenderLayoutSnapshot(
                generation = generation,
                outputWidth = viewportW,
                outputHeight = viewportH,
                mode = mode,
                windows = nodes.map { node ->
                    WindowLayoutSnapshot(
                        windowKey = node.key,
                        quad = canonicalQuads?.get(node.key),
                    )
                },
            ),
        )
    }

    override fun release() {
        val sv = surfaceViewRef
        if (sv != null) {
            sv.queueEvent {
                released = true
                windows.values.forEach { it.input?.release() }
                windows.clear()
                spatialProgram?.delete()
                spatialProgram = null
                cursorRenderer?.delete()
                cursorRenderer = null
                outlineRenderer?.delete()
                outlineRenderer = null
                pattern?.delete()
                pattern = null
                testProgram?.delete()
                testProgram = null
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

    private fun program(): GlSpatialOesProgram =
        spatialProgram ?: GlSpatialOesProgram().also { spatialProgram = it }

    /** GL viewport origin is bottom-left; our regions are top-left based. */
    private fun flipY(bottom: Float, fbHeight: Int): Int = (fbHeight - bottom).toInt()

    private fun drawCursor(
        pose: SpatialPoseRef,
        contentWidth: Float,
        contentHeight: Float,
        viewProj: Mat4,
        regionW: Int,
        regionH: Int,
    ) {
        val c = CursorOverlayState.cursor.value ?: return
        if (!c.visible) return
        val out = SpatialProjection.contentPointToOutputPixels(
            c.x, c.y, contentWidth, contentHeight, pose, viewProj, regionW.toFloat(), regionH.toFloat(),
        ) ?: return
        cursorRenderer?.draw(out, CursorOverlayState.style.value, regionW, regionH)
    }

    private fun drawOutline(
        pose: SpatialPoseRef,
        viewProj: Mat4,
        regionW: Int,
        regionH: Int,
    ) {
        val quad = SpatialProjection.projectWindowCorners(pose, viewProj, regionW.toFloat(), regionH.toFloat()) ?: return
        outlineRenderer?.draw(quad, regionW, regionH, OUTLINE_COLOR)
    }

    /** GL thread only: create the OES input for one node. */
    private fun createNodeInput(node: WindowNode) {
        val sv = surfaceViewRef ?: return
        val legacyQuadProgram = legacyProgram ?: GlExternalTextureProgram().also { legacyProgram = it }
        node.input = GlFrameInput(sv, node.source, legacyQuadProgram, node.config).also { it.create() }
    }

    private fun modeRegions(mode: RenderMode, w: Int, h: Int): List<PixelRect> = when (mode) {
        RenderMode.PASSTHROUGH_2D -> listOf(PixelRect(0f, 0f, w.toFloat(), h.toFloat()))
        RenderMode.SBS_DUPLICATE, RenderMode.SBS_STEREO -> {
            val half = w / 2
            listOf(
                PixelRect(0f, 0f, half.toFloat(), h.toFloat()),
                PixelRect(half.toFloat(), 0f, w.toFloat(), h.toFloat()),
            )
        }
    }

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


    private companion object {
        const val TAG = "GlRenderBackend"
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

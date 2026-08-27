package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import android.util.Log
import com.example.ar_glass_plus.render.api.RenderBackend
import com.example.ar_glass_plus.render.api.RenderConfig
import com.example.ar_glass_plus.render.api.RenderTarget
import com.example.ar_glass_plus.render.geometry.PixelPoint
import com.example.ar_glass_plus.render.overlay.CursorOverlayState
import com.example.ar_glass_plus.render.spatial.Mat4
import com.example.ar_glass_plus.render.spatial.Quat
import com.example.ar_glass_plus.render.spatial.SpatialCamera
import com.example.ar_glass_plus.render.spatial.SpatialProjection
import com.example.ar_glass_plus.render.spatial.SpatialPoseRef
import com.example.ar_glass_plus.render.spatial.StereoCamera
import com.example.ar_glass_plus.render.spatial.Vec3
import com.example.ar_glass_plus.render.spatial.calibration.CalibrationScene
import com.example.ar_glass_plus.render.spatial.calibration.StereoCalibration
import com.example.ar_glass_plus.render.spatial.calibration.StereoCalibrationProfile
import com.example.ar_glass_plus.source.FrameSource
import com.example.ar_glass_plus.source.SourceConfig
import android.opengl.GLSurfaceView
import com.example.ar_glass_plus.render.geometry.PixelRect
import com.example.ar_glass_plus.render.geometry.RenderLayoutSnapshot
import com.example.ar_glass_plus.render.geometry.RenderLayoutStore
import com.example.ar_glass_plus.render.geometry.RenderMode
import com.example.ar_glass_plus.render.geometry.WindowLayoutSnapshot
import com.example.ar_glass_plus.interaction.spatial.WindowChrome

/**
 * OpenGL ES 3.0 backend, spatial (Gate 2/3): each SpatialWindow is one
 * world-space quad rendered under projection·view·model, plus window chrome
 * (title bar / border / resize handle) drawn as solid quads around the
 * content, and an optional CALIBRATION render mode that draws the Gate 3A
 * stereo calibration scene.
 *
 * Threading contract unchanged:
 * - The window map is GL-thread confined; mutations are posted through
 *   [GLSurfaceView.queueEvent], renderFrame reads it lock-free.
 * - All GL resources are created AND deleted on the GL thread.
 *
 * Render modes:
 * - PASSTHROUGH_2D / SBS_DUPLICATE / SBS_STEREO: window scene (stereo uses
 *   the parallel StereoCamera rig; duplicate uses the mono camera twice).
 * - CALIBRATION: the [CalibrationScene] replaces the window scene; per-eye
 *   world quads + viewport-local NDC overlays (arrows/cross) from the
 *   [StereoCalibrationProfile].
 */
class GlRenderBackend : RenderBackend {

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
    private var solidProgram: GlSolidQuadProgram? = null
    private var ndcProgram: GlNdcTriangleProgram? = null
    private var cursorRenderer: GlCursorRenderer? = null
    private var outlineRenderer: GlQuadLineRenderer? = null
    private var surfaceViewRef: GLSurfaceView? = null

    /** GL-thread confined (mutations posted via queueEvent). */
    private val windows = LinkedHashMap<Long, WindowNode>()
    private var focusedKey: Long? = null

    private var mode = RenderMode.PASSTHROUGH_2D
    private var camera: SpatialCamera = SpatialCamera.STATIC_HEAD
    private var stereo = StereoCamera()
    private var calibrationProfile: StereoCalibrationProfile? = null
    private var calibrationScene: CalibrationScene.Scene? = null
    private var calibrationLogged = false
    private var viewportW = 0
    private var viewportH = 0
    private var contextReady = false
    private var generation = 0L
    private var lastMode: RenderMode? = null
    private var lastCameraPos: Vec3? = null
    private var lastCameraOrient: Quat? = null
    private var released = false

    // ── render stats (telemetry, drained by the host) ──
    @Volatile
    private var lastStats = SpatialRenderStats()

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

    /** Focus marker (cursor + chrome highlight target); null clears it. */
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

    /** Head/camera update (static in Gate 3; head tracking later). */
    fun setCamera(newCamera: SpatialCamera) {
        camera = newCamera
        stereo = StereoCamera(head = newCamera)
    }

    /** Calibration profile for CALIBRATION mode / stereo eye params. */
    fun setCalibrationProfile(profile: StereoCalibrationProfile?) {
        calibrationProfile = profile
        calibrationScene = if (profile != null) CalibrationScene.build() else null
    }

    /** Latest render telemetry; safe to poll from any thread. */
    fun stats(): SpatialRenderStats = lastStats

    /** CALIBRATION is signaled through the normal mode plumbing. */
    override fun setRenderMode(mode: RenderMode) {
        this.mode = mode
        Log.i(TAG, "mode -> $mode")
    }

    /**
     * Tile-era geometry knobs (FIT/FILL/rotation). The spatial renderer
     * places windows purely by pose.
     */
    override fun setGeometryConfig(config: com.example.ar_glass_plus.render.geometry.GeometryConfig) {
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
            solidProgram?.delete()
            solidProgram = null
            ndcProgram?.delete()
            ndcProgram = null
            cursorRenderer?.delete()
            outlineRenderer?.delete()
        }
        testProgram = GlProgram(VERTEX_SRC, FRAGMENT_SRC)
        pattern = GlTestPattern(testProgram!!)
        if (windows.isNotEmpty()) spatialProgram = GlSpatialOesProgram()
        solidProgram = GlSolidQuadProgram()
        ndcProgram = GlNdcTriangleProgram()
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

    override fun renderFrame() {
        if (!contextReady) return
        val frameStartNs = System.nanoTime()
        GLES30.glClearColor(0.02f, 0.02f, 0.03f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val calib = calibrationProfile
        if (mode == RenderMode.CALIBRATION && calib != null) {
            renderCalibration(calib)
            recordStats(frameStartNs, 0)
            return
        }

        val nodes = windows.values.toList()
        if (nodes.isEmpty()) {
            GLES30.glViewport(0, 0, viewportW, viewportH)
            testProgram?.use()
            pattern?.draw()
            recordStats(frameStartNs, 0)
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
            GLES30.glViewport(
                region.left.toInt(),
                flipY(region.bottom, viewportH),
                regionW.coerceAtLeast(1),
                regionH.coerceAtLeast(1),
            )

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
                -view[2]
            }

            for (node in ordered) {
                val mvp = viewProj * SpatialProjection.windowModelMatrix(node.pose)
                drawChrome(node.pose, viewProj, focused = node.key == focusedKey)
                node.input?.drawSpatial(program(), mvp)
                if (node.key == focusedKey) {
                    drawCursor(node.pose, node.config.width.toFloat(), node.config.height.toFloat(), viewProj, regionW, regionH)
                    drawOutline(node.pose, viewProj, regionW, regionH)
                }
                if (index == 0) {
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
        recordStats(frameStartNs, nodes.size)
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
                solidProgram?.delete()
                solidProgram = null
                ndcProgram?.delete()
                ndcProgram = null
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

    // ── calibration rendering (Gate 3A) ──

    private fun renderCalibration(profile: StereoCalibrationProfile) {
        val scene = calibrationScene ?: return
        val stereo = StereoCamera(head = camera, ipdMeters = profile.ipdMeters)
        val eyes = StereoCalibration.renderEyes(profile, stereo)
        for (eye in eyes) {
            val vp = eye.viewport
            GLES30.glViewport(
                vp.left.toInt(),
                flipY(vp.bottom, viewportH),
                vp.width.toInt().coerceAtLeast(1),
                vp.height.toInt().coerceAtLeast(1),
            )
            // World quads through the calibrated projection.
            for (quad in scene.quads) {
                val mvp = eye.viewProjection * SpatialProjection.windowModelMatrix(quad)
                solidProgram?.draw(mvp, quad.colorArgb)
            }
            // Viewport-local overlay (arrows + cross) for THIS eye slot.
            val overlay = if (eye === eyes[0] && profile.eyeOrder == com.example.ar_glass_plus.render.spatial.calibration.EyeOrder.LEFT_FIRST) {
                scene.leftOverlay
            } else if (eye === eyes[0]) {
                scene.rightOverlay

            } else if (profile.eyeOrder == com.example.ar_glass_plus.render.spatial.calibration.EyeOrder.LEFT_FIRST) {
                scene.rightOverlay
            } else {
                scene.leftOverlay
            }
            ndcProgram?.draw(overlay.triangles)
        }
        if (!calibrationLogged) {
            Log.i(TAG, "calibration frame drawn (${scene.quads.size} quads)")
            calibrationLogged = true
        }
    }

    // ── chrome ──

    /** Title bar above content + resize handle square, focused tint. */
    private fun drawChrome(pose: SpatialPoseRef, viewProj: Mat4, focused: Boolean) {
        val solid = solidProgram ?: return

        // Title bar spans the content width, sitting above it.
        val titleModel = Mat4.translation(
            pose.position + pose.orientation.rotate(
                Vec3(0f, pose.heightMeters / 2f + WindowChrome.TITLE_BAR_METERS / 2f, 0f),
            ),
        ) * Mat4.rotation(pose.orientation) *
            Mat4.scale(pose.widthMeters, WindowChrome.TITLE_BAR_METERS, 1f)
        solid.draw(
            viewProj * titleModel,
            if (focused) CHROME_TITLE_FOCUSED else CHROME_TITLE,
        )

        // Resize handle: square at the content bottom-right corner.
        val handleModel = Mat4.translation(
            pose.position + pose.orientation.rotate(
                Vec3(
                    pose.widthMeters / 2f - WindowChrome.RESIZE_HANDLE_METERS / 2f,
                    -pose.heightMeters / 2f + WindowChrome.RESIZE_HANDLE_METERS / 2f,
                    0f,
                ),
            ),
        ) * Mat4.rotation(pose.orientation) *
            Mat4.scale(WindowChrome.RESIZE_HANDLE_METERS, WindowChrome.RESIZE_HANDLE_METERS, 1f)
        solid.draw(viewProj * handleModel, CHROME_HANDLE)
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

    private fun recordStats(frameStartNs: Long, windowCount: Int) {
        val frameNs = System.nanoTime() - frameStartNs
        val nowMs = frameNs / 1_000_000.0
        lastStats = lastStats.copy(
            frameCount = lastStats.frameCount + 1,
            windowCount = windowCount,
            lastFrameMs = nowMs.toFloat(),
            maxFrameMs = maxOf(lastStats.maxFrameMs, nowMs.toFloat()),
        )
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
        RenderMode.CALIBRATION -> {
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

    /** GL thread only: create the OES input for one node. */
    private fun createNodeInput(node: WindowNode) {
        val sv = surfaceViewRef ?: return
        node.input = GlFrameInput(sv, node.source, GlExternalTextureProgram(), node.config).also { it.create() }
    }

    private companion object {
        const val TAG = "GlRenderBackend"
        const val OUTLINE_COLOR = 0xFFFFFFFF.toInt()
        const val CHROME_TITLE = 0x66405060.toInt()
        const val CHROME_TITLE_FOCUSED = 0xAA2060A0.toInt()
        const val CHROME_HANDLE = 0xCCFFFFFF.toInt()

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

/** Simple per-renderer telemetry (polled by the debug UI). */
data class SpatialRenderStats(
    val frameCount: Long = 0,
    val windowCount: Int = 0,
    val lastFrameMs: Float = 0f,
    val maxFrameMs: Float = 0f,
)

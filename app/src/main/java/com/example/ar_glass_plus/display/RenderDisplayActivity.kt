package com.example.ar_glass_plus.display

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ar_glass_plus.render.api.RenderConfig
import com.example.ar_glass_plus.render.api.RenderPipeline
import com.example.ar_glass_plus.render.api.RenderTarget
import com.example.ar_glass_plus.render.geometry.GeometryConfig
import com.example.ar_glass_plus.render.gl.GlRenderBackend
import com.example.ar_glass_plus.render.gl.GlSurfaceRenderer
import com.example.ar_glass_plus.render.overlay.CursorOverlayState
import com.example.ar_glass_plus.render.spatial.calibration.StereoCalibrationProfile
import com.example.ar_glass_plus.source.SourceConfig
import com.example.ar_glass_plus.source.VirtualDisplayConfig
import com.example.ar_glass_plus.source.VirtualDisplaySource
import com.example.ar_glass_plus.source.VirtualDisplayState
import com.example.ar_glass_plus.workspace.SpatialWindowId
import com.example.ar_glass_plus.workspace.SpatialWindowState
import com.example.ar_glass_plus.workspace.WorkspaceController
import com.example.ar_glass_plus.workspace.WorkspaceSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Rendered-host activity on the RayNeo output display. Materializes the
 * workspace scene: one VirtualDisplaySource + one backend window per
 * SpatialWindow (store-driven diff), and feeds per-window content
 * ready/gone back into the controller. One window == one content VD at a
 * fixed 1280x720 — the VD is an offscreen app canvas whose resolution is
 * decoupled from any spatial placement.
 */
class RenderDisplayActivity : ComponentActivity() {

    private class WindowHost(
        val source: VirtualDisplaySource,
        var job: Job?,
    )

    private var pipeline: RenderPipeline? = null
    private var backend: GlRenderBackend? = null
    private var controller: WorkspaceController? = null
    private var displayListenerRegistered = false
    private lateinit var glView: GLSurfaceView
    private var hostedDisplayId: Int = Display.INVALID_DISPLAY

    /** Windows currently materialized here, keyed by SpatialWindowId. */
    private val windowHosts = LinkedHashMap<SpatialWindowId, WindowHost>()

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == hostedDisplayId) {
                Log.i(TAG, "output display $displayId removed, finishing")
                finish()
            }
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostedDisplayId = display?.displayId ?: Display.INVALID_DISPLAY
        val registeredController = WorkspaceSession.controllerOrNull()
        if (
            registeredController == null ||
            hostedDisplayId == Display.DEFAULT_DISPLAY ||
            hostedDisplayId == Display.INVALID_DISPLAY
        ) {
            Log.w(TAG, "invalid host: outputDisplayId=$hostedDisplayId controller=${registeredController != null}")
            finish()
            return
        }
        controller = registeredController

        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        displayListenerRegistered = true

        val glBackend = GlRenderBackend()
        backend = glBackend
        pipeline = RenderPipeline(glBackend)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(GlSurfaceRenderer(glBackend))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        setContentView(glView)
        observeWindows()
        observeWorkspace()

        val outputWidth = display?.width ?: 0
        val outputHeight = display?.height ?: 0
        pipeline?.start(
            RenderTarget(glView.holder.surface),
            RenderConfig(outputWidth, outputHeight, registeredController.state.renderMode),
        )
        pipeline?.setGeometryConfig(
            GeometryConfig(
                registeredController.state.aspectMode,
                registeredController.state.rotation,
            ),
        )
        Log.i(TAG, "started: outputDisplayId=$hostedDisplayId config=${outputWidth}x$outputHeight")
    }

    /**
     * Materializes scene windows as (VirtualDisplay + backend window) pairs.
     * Diff-driven: new windows get a VD + a content-ready/gone collector;
     * removed windows lose both; pose/focus changes are forwarded.
     */
    private fun observeWindows() {
        val registeredController = controller ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkspaceSession.store.state.collect { state ->
                    if (state.outputDisplayId != hostedDisplayId) return@collect

                    // Attach new windows.
                    for (window in state.scene.windows.values) {
                        if (windowHosts.containsKey(window.id)) continue
                        attachWindow(registeredController, window)
                    }
                    // Detach vanished windows.
                    val live = state.scene.windows.keys
                    windowHosts.keys.filter { it !in live }.forEach { detachWindow(it) }
                    // Forward pose (position/orientation/size) + focus.
                    for (window in state.scene.windows.values) {
                        backend?.setWindowPose(window.id.value, window.pose)
                    }
                    backend?.setFocusedWindow(state.scene.focusedWindowId?.value)
                }
            }
        }
    }

    private fun attachWindow(
        registeredController: WorkspaceController,
        window: SpatialWindowState,
    ) {
        val glBackend = backend ?: return
        val source = VirtualDisplaySource(this, WINDOW_VD_CONFIG)
        glBackend.attachWindowSource(
            key = window.id.value,
            surfaceView = glView,
            source = source,
            config = WINDOW_SOURCE_CONFIG,
            pose = window.pose,
        )
        val host = WindowHost(source, job = null)
        windowHosts[window.id] = host
        Log.i(TAG, "window ${window.id.value} attach at ${window.pose.position}")
        host.job = lifecycleScope.launch {
            // VD creation failure (e.g. a system cap on concurrent displays)
            // resolves as a 5s timeout -> window is torn down again.
            val running = withTimeoutOrNull(VD_START_TIMEOUT_MS) {
                source.state.first { it is VirtualDisplayState.Running }
            } as? VirtualDisplayState.Running
            if (running == null) {
                Log.w(TAG, "window ${window.id.value} VD start timeout; removing")
                registeredController.onWindowContentGone(window.id, Display.INVALID_DISPLAY)
                return@launch
            }
            var lastDisplayId: Int? = running.displayId
            Log.i(TAG, "window ${window.id.value} contentReady display ${running.displayId}")
            registeredController.onWindowContentReady(
                window.id,
                running.displayId,
                running.width,
                running.height,
                running.densityDpi,
            )
            // Subsequent Running -> Stopped means the VD died (system
            // reclaim / surface teardown) — report exactly once per death.
            source.state.collect { vdState ->
                when (vdState) {
                    is VirtualDisplayState.Running -> lastDisplayId = vdState.displayId
                    VirtualDisplayState.Stopped -> {
                        val id = lastDisplayId
                        if (id != null) {
                            lastDisplayId = null
                            registeredController.onWindowContentGone(window.id, id)
                        }
                    }
                }
            }
        }
    }

    private fun detachWindow(id: SpatialWindowId) {
        val host = windowHosts.remove(id) ?: return
        host.job?.cancel()
        backend?.detachWindowSource(id.value)
        Log.i(TAG, "window ${id.value} detach")
    }

    private fun observeWorkspace() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WorkspaceSession.store.state.collect { state ->
                    if (state.outputDisplayId != hostedDisplayId) {
                        Log.i(TAG, "workspace no longer targets outputDisplayId=$hostedDisplayId")
                        finish()
                        return@collect
                    }
                    // Render mode is live state. The initial RenderConfig only
                    // covers Activity creation; UI mode changes must reach the
                    // existing GL backend without rebuilding the session.
                    pipeline?.setRenderMode(state.renderMode)
                    pipeline?.setGeometryConfig(
                        GeometryConfig(state.aspectMode, state.rotation),
                    )
                    // Gate 3R: live calibration draft from the workspace
                    // state; applied every emission without rebuilds.
                    val outW = display?.width ?: 0
                    val outH = display?.height ?: 0
                    backend?.setCalibrationProfile(
                        state.calibration.applyTo(outW.coerceAtLeast(2), outH.coerceAtLeast(2)),
                    )
                    CursorOverlayState.setCursor(state.cursor)
                    // Telemetry bridge to the tablet UI.
                    (application as? com.example.ar_glass_plus.App)
                        ?.publishRenderStats(backend?.stats() ?: com.example.ar_glass_plus.render.gl.SpatialRenderStats())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::glView.isInitialized) glView.onResume()
    }

    override fun onPause() {
        if (::glView.isInitialized) glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        val changingConfig = isChangingConfigurations
        pipeline?.stop()
        pipeline = null
        backend = null
        windowHosts.values.forEach { it.job?.cancel() }
        // Belt & suspenders: if the GL thread is already gone (surface torn
        // down first), its queued release never runs — stop the VDs here.
        // Double release (GL thread + here) is benign; a leaked
        // VirtualDisplay is not.
        windowHosts.values.forEach { it.source.stop() }
        windowHosts.clear()
        if (!changingConfig) {
            controller?.let { registeredController ->
                WorkspaceSession.launchHostCleanup(registeredController, hostedDisplayId)
            }
        }
        controller = null
        if (displayListenerRegistered) {
            displayManager.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        super.onDestroy()
        Log.i(TAG, "destroyed: outputDisplayId=$hostedDisplayId")
    }

    private companion object {
        const val TAG = "RenderDispAct"
        const val VD_START_TIMEOUT_MS = 5_000L

        /**
         * Every window renders into the same fixed-resolution offscreen
         * canvas; VD resolution is a content-quality knob, decoupled from
         * window placement/size (spatial resize is a later stage).
         */
        val WINDOW_VD_CONFIG = VirtualDisplayConfig(width = 1280, height = 720, densityDpi = 240)
        val WINDOW_SOURCE_CONFIG = SourceConfig(width = 1280, height = 720, densityDpi = 240)
    }
}

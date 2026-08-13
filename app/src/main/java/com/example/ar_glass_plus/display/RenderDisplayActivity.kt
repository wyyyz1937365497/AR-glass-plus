package com.example.ar_glass_plus.display

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ar_glass_plus.app.AppLauncher
import com.example.ar_glass_plus.app.AppPickerState
import com.example.ar_glass_plus.app.RootAppLauncher
import com.example.ar_glass_plus.input.CursorController
import com.example.ar_glass_plus.render.api.RenderConfig
import com.example.ar_glass_plus.render.api.RenderDisplaySession
import com.example.ar_glass_plus.render.api.RenderPipeline
import com.example.ar_glass_plus.render.api.RenderTarget
import com.example.ar_glass_plus.render.gl.GlRenderBackend
import com.example.ar_glass_plus.render.gl.GlSurfaceRenderer
import com.example.ar_glass_plus.root.RootShellImpl
import com.example.ar_glass_plus.source.SourceConfig
import com.example.ar_glass_plus.source.VirtualDisplayConfig
import com.example.ar_glass_plus.source.VirtualDisplaySource
import com.example.ar_glass_plus.source.VirtualDisplayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Rendered-display host on the glasses: owns a GLSurfaceView (GLES 3.0) whose
 * render loop drives GlRenderBackend via RenderPipeline. Deliberately thin —
 * no shaders, no GL calls, no displayId knowledge here beyond self-finish on
 * display removal. Runs on whatever display the launch options specify.
 */
class RenderDisplayActivity : ComponentActivity() {

    private var pipeline: RenderPipeline? = null
    private lateinit var glView: GLSurfaceView
    private var hostedDisplayId: Int = Display.INVALID_DISPLAY

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == hostedDisplayId) {
                Log.i(TAG, "host display $displayId removed, finishing")
                finish()
            }
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    @SuppressLint("NewApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hostedDisplayId = display?.displayId ?: Display.INVALID_DISPLAY

        // The system migrates the task to the built-in display and recreates
        // this activity when the glasses unplug. A render host on the built-in
        // display is invalid — finish immediately so the control panel
        // returns, instead of rendering content at main-screen size.
        if (hostedDisplayId == Display.DEFAULT_DISPLAY || hostedDisplayId == Display.INVALID_DISPLAY) {
            Log.w(TAG, "not on an external display (id=$hostedDisplayId), finishing")
            finish()
            return
        }
        RenderDisplaySession.setRenderActive(true)

        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        val backend = GlRenderBackend()
        pipeline = RenderPipeline(backend)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(GlSurfaceRenderer(backend))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // P2.3: hidden content VirtualDisplay (1280x720@240) feeds the OES
        // input; the RayNeo output is 1920x1080 — producer/output sizes stay
        // decoupled by design.
        val contentSource = VirtualDisplaySource(
            this,
            VirtualDisplayConfig(width = 1280, height = 720, densityDpi = 240),
        )
        val cursorController = CursorController(
            onContentSize = {
                (contentSource.state.value as? VirtualDisplayState.Running)?.let {
                    it.width to it.height
                }
            },
        )
        backend.attachSource(
            surfaceView = glView,
            source = contentSource,
            config = SourceConfig(width = 1280, height = 720, densityDpi = 240),
        )
        setContentView(glView)

        // Launch the target app onto the content display once it exists.
        // Standard API first; ColorOS denies some packages, then root fallback.
        val rootAppLauncher = RootAppLauncher(RootShellImpl())

        // Cursor lifecycle: recenter on new content display, hide on teardown.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                contentSource.state.collect { state ->
                    when (state) {
                        is VirtualDisplayState.Running -> {
                            RenderDisplaySession.setContentDisplayId(state.displayId)
                            RenderDisplaySession.setContentSize(state.width, state.height)
                            cursorController.onVirtualDisplayCreated()
                            val launched = AppLauncher.launchComponentOnDisplay(
                                this@RenderDisplayActivity,
                                TARGET_PACKAGE,
                                TARGET_ACTIVITY,
                                state.displayId,
                            )
                            if (!launched) {
                                rootAppLauncher.launchComponentOnDisplay(
                                    TARGET_PACKAGE,
                                    TARGET_ACTIVITY,
                                    state.displayId,
                                )
                            }
                        }
                        else -> {
                            RenderDisplaySession.setContentDisplayId(Display.INVALID_DISPLAY)
                            cursorController.onVirtualDisplayDestroyed()
                        }
                    }
                }
            }
        }

        // App-switch requests from the control-panel picker (P4.1): start the
        // picked package on the CURRENT content display; session/renderer/input
        // stay untouched (same VirtualDisplay).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppPickerState.launchRequests.collect { request ->
                    val id = RenderDisplaySession.contentDisplayId.value
                    if (id < 0) {
                        Log.w(TAG, "app switch ignored, no content display")
                        return@collect
                    }
                    val cls = request.launcherClassName
                    val launched = if (cls != null) {
                        AppLauncher.launchComponentOnDisplay(
                            this@RenderDisplayActivity,
                            request.packageName,
                            cls,
                            id,
                        )
                    } else {
                        AppLauncher.launchOnDisplay(this@RenderDisplayActivity, request.packageName, id)
                    }
                    if (!launched) {
                        if (cls != null) {
                            rootAppLauncher.launchComponentOnDisplay(request.packageName, cls, id)
                        } else {
                            rootAppLauncher.launchOnDisplay(request.packageName, id)
                        }
                    }
                }
            }
        }

        // Stop requested from the tablet sidebar.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RenderDisplaySession.stopRequested.collect { stop ->
                    if (stop) {
                        Log.i(TAG, "stop requested from control panel, finishing")
                        finish()
                    }
                }
            }
        }

        // Config from the host display (real size, never hardcoded).
        val w = display?.width ?: 0
        val h = display?.height ?: 0
        pipeline?.start(RenderTarget(glView.holder.surface), RenderConfig(w, h))

        // Runtime control from the tablet control panel (no restart).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RenderDisplaySession.mode.collect { mode ->
                    Log.i(TAG, "session mode -> $mode")
                    pipeline?.setRenderMode(mode)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RenderDisplaySession.geometry.collect { config ->
                    Log.i(TAG, "session geometry -> ${config.aspectMode} ${config.rotation}")
                    pipeline?.setGeometryConfig(config)
                }
            }
        }
        Log.i(
            TAG,
            "started: outputDisplayId=$hostedDisplayId (RayNeo), config ${w}x$h",
        )
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        pipeline?.stop()
        pipeline = null
        displayManager.unregisterDisplayListener(displayListener)
        RenderDisplaySession.reset()
        RenderDisplaySession.setContentDisplayId(Display.INVALID_DISPLAY)
        RenderDisplaySession.setRenderActive(false)
        cleanupContentApp()
        super.onDestroy()
        Log.i(TAG, "destroyed")
    }

    /**
     * When the glasses unplug, the system migrates the content app's task to
     * the built-in display, covering the control panel. Bring the panel back
     * by force-stopping the content app (it is relaunched on replug).
     */
    private fun cleanupContentApp() {
        CoroutineScope(Dispatchers.IO).launch {
            val result = RootShellImpl().exec("am force-stop $TARGET_PACKAGE")
            Log.i(TAG, "content cleanup: exit=${result.exitCode}")
        }
    }

    private companion object {
        const val TAG = "RenderDispAct"

        // Safe in-app test target for the content VirtualDisplay — never a
        // system app (testing against Settings can toggle wireless debugging).
        const val TARGET_PACKAGE = "com.example.ar_glass_plus"
        const val TARGET_ACTIVITY = "com.example.ar_glass_plus.TestTargetActivity"
    }
}

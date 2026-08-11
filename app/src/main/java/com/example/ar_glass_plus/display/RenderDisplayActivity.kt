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
import com.example.ar_glass_plus.render.api.RenderConfig
import com.example.ar_glass_plus.render.api.RenderDisplaySession
import com.example.ar_glass_plus.render.api.RenderPipeline
import com.example.ar_glass_plus.render.api.RenderTarget
import com.example.ar_glass_plus.render.gl.GlRenderBackend
import com.example.ar_glass_plus.render.gl.GlSurfaceRenderer
import com.example.ar_glass_plus.source.SourceConfig
import com.example.ar_glass_plus.source.test.SyntheticSurfaceSource
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
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        val backend = GlRenderBackend()
        pipeline = RenderPipeline(backend)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(GlSurfaceRenderer(backend))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        // P2.2: test producer writes 1280x720 into the OES input; the output
        // framebuffer is 1920x1080 — intentionally different, proving the
        // scale path without any hardcoded size coupling.
        backend.attachSource(
            surfaceView = glView,
            source = SyntheticSurfaceSource(fps = 30),
            config = SourceConfig(width = 1280, height = 720, densityDpi = 213),
        )
        setContentView(glView)

        // Config from the host display (real size, never hardcoded).
        val w = display?.width ?: 0
        val h = display?.height ?: 0
        pipeline?.start(RenderTarget(glView.holder.surface), RenderConfig(w, h))

        // Runtime mode switch from the tablet control panel (no restart).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RenderDisplaySession.mode.collect { mode ->
                    Log.i(TAG, "session mode -> $mode")
                    pipeline?.setRenderMode(mode)
                }
            }
        }
        Log.i(TAG, "started on display $hostedDisplayId, config ${w}x$h")
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
        super.onDestroy()
        Log.i(TAG, "destroyed")
    }

    private companion object {
        const val TAG = "RenderDispAct"
    }
}

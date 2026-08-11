package com.example.ar_glass_plus.display

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ar_glass_plus.capture.CaptureSessionState
import com.example.ar_glass_plus.capture.CaptureSessionStore
import kotlinx.coroutines.launch

/**
 * Fullscreen SurfaceView on the glasses display that receives the mirrored
 * frames. Publishes its Surface to [CaptureSessionStore]; the capture service
 * drives a VirtualDisplay into it. Auto-finishes when capture stops.
 */
class MirrorDisplayActivity : Activity() {

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            CaptureSessionStore.setSurface(holder.surface)
            Log.i(TAG, "surface created -> published to store")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            CaptureSessionStore.setSurface(null)
            Log.i(TAG, "surface destroyed -> unpublished")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surfaceView = SurfaceView(this)
        surfaceView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        surfaceView.holder.addCallback(holderCallback)
        setContentView(surfaceView)

        // Leave when the capture session ends; keep the activity while capturing.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CaptureSessionStore.state.collect { state ->
                    if (state is CaptureSessionState.Idle || state is CaptureSessionState.Error) {
                        Log.i(TAG, "capture ${state}, finishing mirror activity")
                        finish()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        CaptureSessionStore.setSurface(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MirrorAct"
    }
}

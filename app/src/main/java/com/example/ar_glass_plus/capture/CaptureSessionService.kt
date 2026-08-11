package com.example.ar_glass_plus.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service owning the MediaProjection + VirtualDisplay. Does NOT hold
 * any Activity: the output Surface is published via [CaptureSessionStore] by
 * MirrorDisplayActivity, and the service reacts to its lifecycle.
 */
class CaptureSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var surfaceJob: Job? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val resultCode = intent?.getIntExtra(MediaProjectionController.EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra(MediaProjectionController.EXTRA_DATA)
        if (resultCode == 0 || data == null) {
            Log.w(TAG, "missing resultCode/data, ignoring start")
            stopSelf()
            return START_NOT_STICKY
        }
        // startForeground BEFORE getMediaProjection (Android 14+ requirement).
        val mpm = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mpm.getMediaProjection(resultCode, data)
        CaptureSessionStore.setState(CaptureSessionState.Capturing)
        Log.i(TAG, "capture session started")

        surfaceJob = scope.launch {
            CaptureSessionStore.surface.collectLatest { surface -> handleSurface(surface) }
        }
        return START_NOT_STICKY
    }

    /** (Re)create the VirtualDisplay for the current output Surface. */
    private fun handleSurface(surface: Surface?) {
        virtualDisplay?.release()
        virtualDisplay = null
        if (surface == null) {
            Log.i(TAG, "surface released, virtual display stopped")
            return
        }
        val wm = getSystemService(WindowManager::class.java)
        val metrics = android.util.DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            VD_NAME,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null,
        )
        Log.i(
            TAG,
            "virtual display created: ${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}dpi",
        )
    }

    override fun onDestroy() {
        Log.i(TAG, "capture service destroyed")
        surfaceJob?.cancel()
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
        CaptureSessionStore.reset()
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen capture",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AR-glass-plus")
            .setContentText("正在镜像屏幕到眼镜")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    private companion object {
        const val TAG = "CaptureSvc"
        const val NOTIF_ID = 1
        const val CHANNEL_ID = "capture"
        const val VD_NAME = "ARGlassMirror"
    }
}

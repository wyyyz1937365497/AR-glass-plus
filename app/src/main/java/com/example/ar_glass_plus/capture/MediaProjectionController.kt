package com.example.ar_glass_plus.capture

import android.content.Context
import android.content.Intent

/**
 * Entry points for starting/stopping a capture session. The permission UI
 * lives in its own task (Android 14+ MediaProjection requirement).
 */
object MediaProjectionController {

    const val EXTRA_RESULT_CODE = "capture_result_code"
    const val EXTRA_DATA = "capture_data"

    /** Launch the MediaProjection consent flow. */
    fun requestCapture(context: Context) {
        context.startActivity(
            Intent(context, CapturePermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Tear down the foreground capture session. */
    fun stopCapture(context: Context) {
        context.stopService(Intent(context, CaptureSessionService::class.java))
    }
}

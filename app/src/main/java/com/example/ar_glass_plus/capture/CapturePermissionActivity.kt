package com.example.ar_glass_plus.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * MediaProjection consent screen in its own task (required on Android 14+).
 * On success hands the result to CaptureSessionService and finishes.
 */
class CapturePermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.i(TAG, "permission granted, starting capture service")
                val service = Intent(this, CaptureSessionService::class.java)
                    .putExtra(MediaProjectionController.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(MediaProjectionController.EXTRA_DATA, data)
                startForegroundService(service)
            } else {
                Log.i(TAG, "permission denied")
            }
            finish()
        }
    }

    private companion object {
        const val TAG = "CapturePerm"
        const val REQ_CAPTURE = 1001
    }
}

package com.example.ar_glass_plus.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Launches third-party apps onto a target display (the hidden content
 * VirtualDisplay). Uses the standard public API first:
 * ActivityOptions.setLaunchDisplayId. A root fallback (`am start --display`)
 * is only considered if ColorOS rejects normal launches for some apps.
 */
object AppLauncher {

    fun launchOnDisplay(context: Context, packageName: String, contentDisplayId: Int): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w(TAG, "no launch intent for $packageName")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, context, contentDisplayId)
    }

    /** Launch an explicit component (same-app activity) onto the content display. */
    fun launchComponentOnDisplay(
        context: Context,
        packageName: String,
        className: String,
        contentDisplayId: Int,
    ): Boolean {
        val intent = Intent().setClassName(packageName, className)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(intent, context, contentDisplayId)
    }

    private fun launch(intent: Intent, context: Context, contentDisplayId: Int): Boolean {
        return try {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(contentDisplayId)
            context.startActivity(intent, options.toBundle())
            Log.i(TAG, "launched ${intent.component?.className} onto contentDisplayId=$contentDisplayId")
            true
        } catch (e: SecurityException) {
            // ColorOS/Android denies some packages for third-party launch onto
            // secondary displays — caller falls back.
            Log.w(TAG, "standard launch denied: ${e.message}")
            false
        }
    }

    private const val TAG = "AppLauncher"
}

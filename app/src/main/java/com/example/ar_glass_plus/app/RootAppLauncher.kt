package com.example.ar_glass_plus.app

import android.util.Log
import com.example.ar_glass_plus.root.RootResult
import com.example.ar_glass_plus.root.RootShell

/**
 * Root fallback for launching apps onto the content VirtualDisplay when the
 * standard ActivityOptions.setLaunchDisplayId path is denied (observed on
 * ColorOS for e.g. Settings). Uses `am start --display <id>` via su.
 */
class RootAppLauncher(private val shell: RootShell) {

    suspend fun launchOnDisplay(packageName: String, contentDisplayId: Int): RootResult =
        launchComponentOnDisplay(packageName, null, contentDisplayId)

    suspend fun launchComponentOnDisplay(
        packageName: String,
        className: String?,
        contentDisplayId: Int,
    ): RootResult {
        val target = if (className != null) {
            "$packageName/$className"
        } else {
            packageName
        }
        val command = "am start --display $contentDisplayId " +
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
            "-n $target"
        val result = shell.exec(command)
        Log.i(
            TAG,
            "root launch $target -> contentDisplayId=$contentDisplayId " +
                "exit=${result.exitCode} ${result.stdout.trim().take(120)} ${result.stderr.trim().take(120)}",
        )
        return result
    }

    private companion object {
        const val TAG = "RootAppLauncher"
    }
}

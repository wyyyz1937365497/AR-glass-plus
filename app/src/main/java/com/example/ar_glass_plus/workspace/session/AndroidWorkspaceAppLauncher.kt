package com.example.ar_glass_plus.workspace

import android.content.Context
import android.util.Log
import com.example.ar_glass_plus.app.AppLauncher
import com.example.ar_glass_plus.app.RootAppLauncher
import com.example.ar_glass_plus.root.RootShell

class AndroidWorkspaceAppLauncher(
    context: Context,
    private val shell: RootShell,
) : WorkspaceAppLauncherPort {
    private val applicationContext = context.applicationContext
    private val rootAppLauncher = RootAppLauncher(shell)

    override suspend fun launch(app: ActiveApp, contentDisplayId: Int): Boolean {
        val launcherClassName = app.launcherClassName
        val launched = if (launcherClassName != null) {
            AppLauncher.launchComponentOnDisplay(
                applicationContext,
                app.packageName,
                launcherClassName,
                contentDisplayId,
            )
        } else {
            AppLauncher.launchOnDisplay(applicationContext, app.packageName, contentDisplayId)
        }
        if (launched) return true

        val result = if (launcherClassName != null) {
            rootAppLauncher.launchComponentOnDisplay(
                app.packageName,
                launcherClassName,
                contentDisplayId,
            )
        } else {
            rootAppLauncher.launchOnDisplay(app.packageName, contentDisplayId)
        }
        return result.exitCode == 0
    }

    override suspend fun stop(app: ActiveApp) {
        val result = shell.exec("am force-stop ${app.packageName}")
        Log.i(TAG, "force-stop ${app.packageName} exit=${result.exitCode}")
    }

    private companion object {
        const val TAG = "WorkspaceApp"
    }
}

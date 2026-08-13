package com.example.ar_glass_plus.app

import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Enumerates launchable apps (ACTION_MAIN + CATEGORY_LAUNCHER) for the
 * control-panel picker. Queries on an IO thread; icons stay lazy per entry.
 * The AR-glass-plus control panel itself is excluded (launching it onto the
 * content display would be a no-op/loop).
 */
class AppRepository(private val packageManager: PackageManager) {

    /** Query + sort by label. Call on a background thread. */
    fun loadLaunchableApps(): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        return resolveInfos
            .asSequence()
            .filterNot { it.activityInfo.packageName == SELF_PACKAGE }
            .map { ri ->
                AppEntry(
                    packageName = ri.activityInfo.packageName,
                    label = ri.loadLabel(packageManager)?.toString() ?: ri.activityInfo.packageName,
                    launcherClassName = ri.activityInfo.name,
                    iconProvider = { ri.loadIcon(packageManager) },
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
            .also { Log.i(TAG, "loaded ${it.size} launchable apps") }
    }

    private companion object {
        const val TAG = "AppRepository"
        const val SELF_PACKAGE = "com.example.ar_glass_plus"
    }
}

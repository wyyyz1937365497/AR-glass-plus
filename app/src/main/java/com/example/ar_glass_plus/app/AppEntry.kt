package com.example.ar_glass_plus.app

import android.graphics.drawable.Drawable

/**
 * A launchable app in the picker. [iconProvider] is invoked lazily on an IO
 * thread only when the row is actually composed (decoding every icon up front
 * would stall the sidebar).
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val launcherClassName: String?,
    val iconProvider: () -> Drawable?,
)

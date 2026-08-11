package com.example.ar_glass_plus.display

/** Snapshot of the physical external (glasses/HDMI) display, if any. */
sealed interface ExternalDisplayState {
    data object Disconnected : ExternalDisplayState

    data class Connected(
        val displayId: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val refreshRate: Float,
        val densityDpi: Int,
    ) : ExternalDisplayState
}

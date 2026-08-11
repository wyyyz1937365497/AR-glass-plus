package com.example.ar_glass_plus.source

/** Lifecycle of the hidden content VirtualDisplay. */
sealed interface VirtualDisplayState {
    data object Stopped : VirtualDisplayState

    data class Running(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    ) : VirtualDisplayState
}

package com.example.ar_glass_plus.capture

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Capture session lifecycle state, shared between service and UI. */
sealed interface CaptureSessionState {
    data object Idle : CaptureSessionState
    data object Capturing : CaptureSessionState
    data class Error(val message: String) : CaptureSessionState
}

/**
 * Process-wide mediator between CaptureSessionService (owns MediaProjection +
 * VirtualDisplay) and MirrorDisplayActivity (owns the output Surface).
 *
 * The service never holds an Activity; the Activity publishes its Surface here
 * and the service reacts. Activities may be recreated (hotplug/rotation/reclaim)
 * without tearing down the capture session.
 */
object CaptureSessionStore {
    private val _state = MutableStateFlow<CaptureSessionState>(CaptureSessionState.Idle)
    val state: StateFlow<CaptureSessionState> = _state.asStateFlow()

    private val _surface = MutableStateFlow<Surface?>(null)
    val surface: StateFlow<Surface?> = _surface.asStateFlow()

    fun setState(state: CaptureSessionState) {
        _state.value = state
    }

    /** Called by MirrorDisplayActivity when its Surface is created/destroyed. */
    fun setSurface(surface: Surface?) {
        _surface.value = surface
    }

    fun reset() {
        _state.value = CaptureSessionState.Idle
        _surface.value = null
    }
}

package com.example.ar_glass_plus.render.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime render-mode switch, driven from the control panel on the tablet
 * (the glasses have no usable touch input yet). RenderDisplayActivity
 * observes this and reconfigures its pipeline without restarting.
 */
object RenderDisplaySession {
    private val _mode = MutableStateFlow<RenderMode>(RenderMode.PASSTHROUGH_2D)
    val mode: StateFlow<RenderMode> = _mode.asStateFlow()

    fun setMode(mode: RenderMode) {
        _mode.value = mode
    }

    fun reset() {
        _mode.value = RenderMode.PASSTHROUGH_2D
    }
}

package com.example.ar_glass_plus.render.api

import com.example.ar_glass_plus.render.geometry.RenderMode
import android.view.Display
import com.example.ar_glass_plus.render.geometry.AspectMode
import com.example.ar_glass_plus.render.geometry.ContentRotation
import com.example.ar_glass_plus.render.geometry.GeometryConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime render control (mode + geometry), driven from the tablet control
 * panel. RenderDisplayActivity observes and reconfigures its pipeline without
 * restarting — no VD/OES/app rebuild.
 */
object RenderDisplaySession {
    private val _mode = MutableStateFlow<RenderMode>(RenderMode.PASSTHROUGH_2D)
    val mode: StateFlow<RenderMode> = _mode.asStateFlow()

    private val _geometry = MutableStateFlow(GeometryConfig())
    val geometry: StateFlow<GeometryConfig> = _geometry.asStateFlow()

    private val _contentDisplayId = MutableStateFlow(Display.INVALID_DISPLAY)
    val contentDisplayId: StateFlow<Int> = _contentDisplayId.asStateFlow()

    fun setContentDisplayId(displayId: Int) {
        _contentDisplayId.value = displayId
    }

    fun setMode(mode: RenderMode) {
        _mode.value = mode
    }

    fun setAspect(mode: AspectMode) {
        _geometry.value = _geometry.value.copy(aspectMode = mode)
    }

    fun setRotation(rotation: ContentRotation) {
        _geometry.value = _geometry.value.copy(rotation = rotation)
    }

    fun reset() {
        _mode.value = RenderMode.PASSTHROUGH_2D
        _geometry.value = GeometryConfig()
    }
}

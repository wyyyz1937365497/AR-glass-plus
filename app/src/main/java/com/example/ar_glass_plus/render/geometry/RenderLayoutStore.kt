package com.example.ar_glass_plus.render.geometry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared layout state: the GL renderer publishes the resolved layout each
 * frame; the input system reads the same snapshot for inverse mapping.
 * No GL/VD types here.
 */
object RenderLayoutStore {
    private val _snapshot = MutableStateFlow<RenderLayoutSnapshot?>(null)
    val snapshot: StateFlow<RenderLayoutSnapshot?> = _snapshot.asStateFlow()

    fun publish(snapshot: RenderLayoutSnapshot) {
        _snapshot.value = snapshot
    }

    fun clear() {
        _snapshot.value = null
    }
}

package com.example.ar_glass_plus.render.overlay

import com.example.ar_glass_plus.input.CursorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Render-side cursor state: input/ publishes, render/gl consumes. Pure data —
 * keeps the input layer GL-free and lets a future Vulkan backend read the
 * same CursorState.
 */
object CursorOverlayState {
    private val _cursor = MutableStateFlow<CursorState?>(null)
    val cursor: StateFlow<CursorState?> = _cursor.asStateFlow()

    private val _style = MutableStateFlow(CursorStyle())
    val style: StateFlow<CursorStyle> = _style.asStateFlow()

    fun setCursor(cursor: CursorState?) {
        _cursor.value = cursor
    }

    fun setStyle(style: CursorStyle) {
        _style.value = style
    }
}

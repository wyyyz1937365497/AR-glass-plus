package com.example.ar_glass_plus.render.overlay

import com.example.ar_glass_plus.input.CursorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Render-side mirror of WorkspaceState.cursor. RenderDisplayActivity is the
 * sole writer; render backends consume this process-global bridge.
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

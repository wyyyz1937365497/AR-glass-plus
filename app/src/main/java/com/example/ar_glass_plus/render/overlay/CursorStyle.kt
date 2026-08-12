package com.example.ar_glass_plus.render.overlay

/** Cursor visual style, sized in OUTPUT pixels (VD resolution changes must not resize it). */
data class CursorStyle(
    val sizePx: Float = 14f,
    val fillColor: Int = 0xFFFFFFFF.toInt(),
    val outlineColor: Int = 0xFF000000.toInt(),
)

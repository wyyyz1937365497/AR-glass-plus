package com.example.ar_glass_plus.input

/**
 * Cursor position stored in CONTENT coordinates (the hidden VirtualDisplay
 * space), never output/RayNeo space. This makes SBS, FIT/FILL and rotation
 * trivially consistent: rendering maps content→output per region, and input
 * injects directly at the content point.
 */
data class CursorState(
    val x: Float = 0f,
    val y: Float = 0f,
    val visible: Boolean = false,
    val pressed: Boolean = false,
)

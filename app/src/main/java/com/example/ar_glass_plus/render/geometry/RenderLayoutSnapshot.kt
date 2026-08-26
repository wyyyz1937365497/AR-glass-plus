package com.example.ar_glass_plus.render.geometry

/**
 * Immutable per-frame layout snapshot published by the renderer via
 * RenderLayoutStore. Window-keyed (one entry per SpatialWindow) so the
 * future P4.7D picking layer can map an output point back to a window and
 * content coordinate. [generation] increments whenever mode/geometryConfig
 * changes; a gesture must be cancelled if the generation changes mid-gesture.
 */
data class WindowLayoutSnapshot(
    val windowKey: Long,
    val slot: Int,
    /** Resolved placement per render region (2D = 1, SBS_DUPLICATE = 2). */
    val regions: List<ResolvedGeometry>,
)

data class RenderLayoutSnapshot(
    val generation: Long,
    val outputWidth: Int,
    val outputHeight: Int,
    val mode: RenderMode,
    val windows: List<WindowLayoutSnapshot>,
)

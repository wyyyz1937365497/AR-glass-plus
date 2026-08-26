package com.example.ar_glass_plus.render.geometry

/**
 * Immutable per-frame layout snapshot published by the spatial renderer via
 * RenderLayoutStore. Each entry carries a window's PROJECTED quad (pixels,
 * top-left origin; TL,TR,BR,BL) computed by the same CPU reference
 * (SpatialProjection) the renderer uses — this is the seam future picking
 * (output px → window → content px) consumes. Null quad = behind camera /
 * not rendered this frame. [generation] increments whenever mode/head-pose
 * changes; a gesture must be cancelled if it changes mid-gesture.
 */
data class WindowLayoutSnapshot(
    val windowKey: Long,
    /** TL,TR,BR,BL projected into the canonical (mono / left-eye) region. */
    val quad: List<PixelPoint>?,
)

data class RenderLayoutSnapshot(
    val generation: Long,
    val outputWidth: Int,
    val outputHeight: Int,
    val mode: RenderMode,
    val windows: List<WindowLayoutSnapshot>,
)

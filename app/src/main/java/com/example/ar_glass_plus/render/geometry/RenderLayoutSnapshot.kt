package com.example.ar_glass_plus.render.geometry

/**
 * Immutable snapshot of the current render layout, consumed by BOTH the GL
 * renderer (forward draw) and the input mapper (inverse mapping). [generation]
 * increments whenever mode/aspect/rotation changes; a gesture must be
 * cancelled if the generation changes mid-gesture.
 */
data class RenderLayoutSnapshot(
    val generation: Long,
    val outputWidth: Int,
    val outputHeight: Int,
    val mode: RenderMode,
    val regions: List<ResolvedGeometry>,
) {
    /** Canonical interaction region: 2D -> region[0]; SBS -> region[0] (left eye). */
    val canonicalRegion: ResolvedGeometry?
        get() = regions.firstOrNull()
}

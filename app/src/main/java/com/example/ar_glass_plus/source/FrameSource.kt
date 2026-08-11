package com.example.ar_glass_plus.source

import android.view.Surface

/** Input side of the render pipeline. The renderer must not know the source kind. */
data class SourceConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

/**
 * Producer of frames into an output Surface. Decoupled from RenderBackend so
 * any producer (virtual display app surface, test pattern, future camera/
 * video) plugs into the same pipeline.
 */
interface FrameSource {

    /** Begin producing frames into [output]. */
    suspend fun start(output: Surface, config: SourceConfig)

    suspend fun stop()
}

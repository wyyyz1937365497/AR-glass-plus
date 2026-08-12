package com.example.ar_glass_plus.input

import android.util.Log
import com.example.ar_glass_plus.render.geometry.GeometryMapper
import com.example.ar_glass_plus.render.geometry.PixelPoint
import com.example.ar_glass_plus.render.geometry.RenderLayoutSnapshot

/**
 * Maps a pad touch through the canonical render region into content
 * coordinates, using the SAME ResolvedGeometry the renderer draws with.
 *
 * Pad space -> (normalize) -> canonical region -> output space
 *           -> GeometryMapper.mapOutputToContent -> content space.
 */
class InputMapper {

    /**
     * @param padX/padY touch position in the pad's own pixel space.
     * @param padWidth/padHeight current pad size.
     */
    fun map(
        padX: Float,
        padY: Float,
        padWidth: Float,
        padHeight: Float,
        snapshot: RenderLayoutSnapshot,
    ): MappedInputEvent {
        if (padWidth <= 0f || padHeight <= 0f) {
            return MappedInputEvent.rejected(padX, padY, "NO_PAD_SIZE")
        }
        val nx = padX.coerceIn(0f, padWidth) / padWidth
        val ny = padY.coerceIn(0f, padHeight) / padHeight

        // Canonical interaction region: 2D -> region[0]; SBS -> region[0] (left eye).
        val geometry = snapshot.canonicalRegion
            ?: return MappedInputEvent.rejected(nx, ny, "NO_LAYOUT")

        val region = geometry.targetRegion
        val ox = region.left + nx * region.width
        val oy = region.top + ny * region.height
        val output = PixelPoint(ox, oy)

        val content = GeometryMapper.mapOutputToContent(output, geometry)
            ?: return MappedInputEvent.rejected(nx, ny, "LETTERBOX")

        return MappedInputEvent(
            padX = nx,
            padY = ny,
            outputPoint = output,
            contentPoint = content,
            regionIndex = 0,
            rejected = false,
        )
    }

    /** Structured one-line log for the input chain. */
    fun logChain(
        tag: String,
        mapped: MappedInputEvent,
        snapshot: RenderLayoutSnapshot,
        contentDisplayId: Int,
        gesture: String,
        resultCode: Int,
    ) {
        if (mapped.rejected) {
            Log.i(
                tag,
                "Input: pad=(${"%.3f".format(mapped.padX)},${"%.3f".format(mapped.padY)}) " +
                    "output=${mapped.outputPoint} content=null " +
                    "reason=${mapped.reason} gesture=$gesture",
            )
        } else {
            Log.i(
                tag,
                "Input: pad=(${"%.3f".format(mapped.padX)},${"%.3f".format(mapped.padY)}) " +
                    "mode=${snapshot.mode} region=LEFT " +
                    "output=(${"%.1f".format(mapped.outputPoint!!.x)},${"%.1f".format(mapped.outputPoint.y)}) " +
                    "content=(${"%.1f".format(mapped.contentPoint!!.x)},${"%.1f".format(mapped.contentPoint.y)}) " +
                    "contentDisplayId=$contentDisplayId gesture=$gesture result=$resultCode",
            )
        }
    }
}

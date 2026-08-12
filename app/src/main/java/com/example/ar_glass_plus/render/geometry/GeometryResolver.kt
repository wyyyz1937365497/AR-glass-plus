package com.example.ar_glass_plus.render.geometry

/**
 * Places a source into one target region under an AspectMode + rotation.
 * Pure math — no GL, no DisplayManager, no VirtualDisplay knowledge.
 * One instance per resolved region (2D = 1, SBS = 2, future PIP/multi = N).
 */
class GeometryResolver {

    fun resolve(
        sourceSize: PixelSize,
        targetRegion: PixelRect,
        config: GeometryConfig,
    ): ResolvedGeometry {
        val rotation = config.rotation
        // Effective aspect after rotation (90/270 swap width/height).
        val effW = if (rotation.isSwapped()) sourceSize.height else sourceSize.width
        val effH = if (rotation.isSwapped()) sourceSize.width else sourceSize.height
        val srcAspect = effW / effH
        val dstAspect = targetRegion.width / targetRegion.height

        val destination: PixelRect
        val sourceRect: PixelRect

        when (config.aspectMode) {
            AspectMode.FIT -> {
                val dw: Float
                val dh: Float
                if (srcAspect > dstAspect) {
                    dw = targetRegion.width
                    dh = targetRegion.width / srcAspect
                } else {
                    dw = targetRegion.height * srcAspect
                    dh = targetRegion.height
                }
                val x = targetRegion.left + (targetRegion.width - dw) / 2f
                val y = targetRegion.top + (targetRegion.height - dh) / 2f
                destination = PixelRect(x, y, x + dw, y + dh)
                sourceRect = FULL
            }

            AspectMode.FILL -> {
                // Aspect-preserving scale that fills the region; crop source.
                val scale = maxOf(
                    targetRegion.width / effW,
                    targetRegion.height / effH,
                )
                val sw = (targetRegion.width / scale) / effW
                val sh = (targetRegion.height / scale) / effH
                destination = targetRegion
                sourceRect = PixelRect(
                    (1f - sw) / 2f,
                    (1f - sh) / 2f,
                    (1f + sw) / 2f,
                    (1f + sh) / 2f,
                )
            }

            AspectMode.STRETCH -> {
                destination = targetRegion
                sourceRect = FULL
            }
        }

        return ResolvedGeometry(
            sourceSize = sourceSize,
            targetRegion = targetRegion,
            destinationRect = destination,
            sourceRect = sourceRect,
            rotation = rotation,
        )
    }

    private companion object {
        val FULL = PixelRect(0f, 0f, 1f, 1f)
    }
}

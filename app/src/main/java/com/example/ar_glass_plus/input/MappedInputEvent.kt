package com.example.ar_glass_plus.input

import com.example.ar_glass_plus.render.geometry.PixelPoint

/** Result of mapping one touch position through the render layout. */
data class MappedInputEvent(
    /** Normalized pad position (0..1). */
    val padX: Float,
    val padY: Float,
    /** Output pixel position (top-left origin), or null when rejected. */
    val outputPoint: PixelPoint?,
    /** Content pixel position, or null when rejected. */
    val contentPoint: PixelPoint?,
    /** Canonical region index used (0 = 2D / SBS left eye). */
    val regionIndex: Int,
    val rejected: Boolean,
    val reason: String? = null,
) {
    companion object {
        fun rejected(padX: Float, padY: Float, reason: String): MappedInputEvent =
            MappedInputEvent(padX, padY, null, null, 0, true, reason)
    }
}

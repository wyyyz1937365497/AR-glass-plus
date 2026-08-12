package com.example.ar_glass_plus.render.geometry

/** Content rotation, clockwise. [glFactor] is the shader rotation value. */
enum class ContentRotation(val glFactor: Float) {
    DEG_0(0f),
    DEG_90(1f),
    DEG_180(2f),
    DEG_270(3f);

    /** 90/270 swap effective width/height for aspect math. */
    fun isSwapped(): Boolean = this == DEG_90 || this == DEG_270
}

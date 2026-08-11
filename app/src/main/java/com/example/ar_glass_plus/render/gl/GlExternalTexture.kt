package com.example.ar_glass_plus.render.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.view.Surface

/**
 * OES external texture + its SurfaceTexture + producer Surface.
 * All methods must run on the GL thread (created/consumed by GlFrameInput).
 * No GL types leak past this package.
 */
class GlExternalTexture {

    var textureId: Int = 0
        private set

    var surfaceTexture: SurfaceTexture? = null
        private set

    var surface: Surface? = null
        private set

    private val transform = FloatArray(16)
    val transformMatrix: FloatArray get() = transform

    /** GL thread only. */
    fun create() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        surfaceTexture = SurfaceTexture(textureId)
        surface = Surface(surfaceTexture)
    }

    /** Explicit producer buffer size — never rely on an implicit default. */
    fun setDefaultBufferSize(width: Int, height: Int) {
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    /** Consume the newest frame and refresh the texture transform. */
    fun updateTexImage() {
        surfaceTexture?.updateTexImage()
        surfaceTexture?.getTransformMatrix(transform)
    }

    /** GL thread only. */
    fun release() {
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
    }
}

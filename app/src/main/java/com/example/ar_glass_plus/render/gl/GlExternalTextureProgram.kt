package com.example.ar_glass_plus.render.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Program + fullscreen quad that samples a GL_TEXTURE_EXTERNAL_OES texture
 * through the SurfaceTexture transform matrix (never assumes UV 0..1).
 * GLES 3.0 with the OES external image extension.
 */
class GlExternalTextureProgram : GlProgram(VERTEX_SRC, FRAGMENT_SRC) {

    private var vao = 0
    private var vbo = 0
    private val texMatrixLoc = uniformLocation("uTexMatrix")

    // TRIANGLE_STRIP quad: (pos.x, pos.y, uv.u, uv.v)
    private val vertices = floatArrayOf(
        -1f, -1f, 0f, 0f,
         1f, -1f, 1f, 0f,
        -1f,  1f, 0f, 1f,
         1f,  1f, 1f, 1f,
    )

    init {
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        vao = ids[0]
        vbo = ids[1]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertices.size * Float.SIZE_BYTES,
            ByteBuffer
                .allocateDirect(vertices.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertices)
                .position(0),
            GLES30.GL_STATIC_DRAW,
        )
        val posLoc = attribLocation("aPos")
        val uvLoc = attribLocation("aUv")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 4 * Float.SIZE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(uvLoc)
        GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, 4 * Float.SIZE_BYTES, 2 * Float.SIZE_BYTES)
        GLES30.glBindVertexArray(0)
    }

    /** GL thread only. */
    fun draw(textureId: Int, transform: FloatArray) {
        use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniformMatrix4fv(texMatrixLoc, 1, false, transform, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    override fun delete() {
        super.delete()
        val ids = intArrayOf(vbo, vao)
        GLES30.glDeleteBuffers(1, ids, 1)
        GLES30.glDeleteVertexArrays(1, ids, 0)
        vbo = 0
        vao = 0
    }

    private companion object {
        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aUv;
            uniform mat4 uTexMatrix;
            out vec2 vUv;
            void main() {
                vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            in vec2 vUv;
            uniform samplerExternalOES uTexture;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTexture, vUv);
            }
        """.trimIndent()
    }
}

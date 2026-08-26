package com.example.ar_glass_plus.render.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.example.ar_glass_plus.render.spatial.Mat4
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MVP-driven OES quad program for the spatial renderer: one window is one
 * world-space quad; the shader knows ONLY uMvp + the OES transform matrix —
 * no slots, tiles, or destination rects (those were the Gate 1 compositor).
 *
 * Quad vertices: unit corners TL,TR,BR,BL as (x,y) in [0,1]; the model
 * matrix maps them onto the window's world rectangle.
 */
class GlSpatialOesProgram : GlProgram(VERTEX_SRC, FRAGMENT_SRC) {

    private var vao = 0
    private var vbo = 0
    private val mvpLoc = uniformLocation("uMvp")
    private val texMatrixLoc = uniformLocation("uTexMatrix")

    // (pos.x, pos.y, uv.u, uv.v) — triangle strip TL,TR,BL,BR.
    private val vertices = floatArrayOf(
        0f, 1f, 0f, 0f,
        1f, 1f, 1f, 0f,
        0f, 0f, 0f, 1f,
        1f, 0f, 1f, 1f,
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

    /**
     * GL thread only. Draws the window quad under [mvp] sampling the OES
     * texture through [texMatrix] (producer→sampling transform; layout math
     * lives entirely in mvp).
     */
    fun draw(textureId: Int, texMatrix: FloatArray, mvp: Mat4) {
        use()
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp.m, 0)
        GLES30.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    override fun delete() {
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        vao = 0
        vbo = 0
        super.delete()
    }

    private companion object {
        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 aPos;   // unit quad corner [0,1]^2
            layout(location = 1) in vec2 aUv;
            uniform mat4 uMvp;                    // projection * view * model
            out vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            in vec2 vUv;
            uniform samplerExternalOES uTexture;
            uniform mat4 uTexMatrix;              // SurfaceTexture transform
            out vec4 fragColor;
            void main() {
                fragColor = vec4(texture(uTexture, (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy));
            }
        """.trimIndent()
    }
}

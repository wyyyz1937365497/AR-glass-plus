package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import com.example.ar_glass_plus.render.spatial.Mat4
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MVP-driven solid-color quad program: window chrome (title bar, border,
 * resize handle), calibration scene quads, pointer rings. Same unit-quad
 * convention as GlSpatialOesProgram.
 */
class GlSolidQuadProgram : GlProgram(VERTEX_SRC, FRAGMENT_SRC) {

    private var vao = 0
    private var vbo = 0
    private val mvpLoc = uniformLocation("uMvp")
    private val colorLoc = uniformLocation("uColor")

    private val vertices = floatArrayOf(
        // (x, y) unit quad TL TR BL BR as triangle strip in [0,1].
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f,
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
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
        GLES30.glBindVertexArray(0)
    }

    /** GL thread only. Draws one solid quad under [mvp] with [colorArgb]. */
    fun draw(mvp: Mat4, colorArgb: Int) {
        use()
        GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp.m, 0)
        GLES30.glUniform4f(
            colorLoc,
            ((colorArgb shr 16) and 0xFF) / 255f,
            ((colorArgb shr 8) and 0xFF) / 255f,
            (colorArgb and 0xFF) / 255f,
            ((colorArgb shr 24) and 0xFF) / 255f,
        )
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
            layout(location = 0) in vec2 aPos;
            uniform mat4 uMvp;
            void main() {
                gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            precision highp float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() { fragColor = uColor; }
        """.trimIndent()
    }
}

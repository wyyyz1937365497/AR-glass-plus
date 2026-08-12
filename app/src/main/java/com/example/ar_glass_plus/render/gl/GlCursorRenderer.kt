package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import com.example.ar_glass_plus.render.geometry.PixelPoint
import com.example.ar_glass_plus.render.overlay.CursorStyle
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the cursor overlay circle at an OUTPUT-space position (already
 * content→output mapped per region). Pure presentation — movement/input
 * logic lives in input/, never here.
 */
class GlCursorRenderer {

    private var program: GlProgram? = null
    private var vao = 0
    private var vbo = 0
    private var ready = false

    /** GL thread only. */
    fun onContextCreated() {
        program = GlProgram(VERTEX_SRC, FRAGMENT_SRC)
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0)
        GLES30.glGenBuffers(1, ids, 1)
        vao = ids[0]
        vbo = ids[1]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        // Quad in unit space; positions computed in shader via uniforms.
        val vertices = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
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
        val posLoc = program!!.attribLocation("aPos")
        GLES30.glEnableVertexAttribArray(posLoc)
        GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
        GLES30.glBindVertexArray(0)
        ready = true
    }

    /** GL thread only. [center] is in output pixels; [fbW]/[fbH] framebuffer size. */
    fun draw(center: PixelPoint, style: CursorStyle, fbWidth: Int, fbHeight: Int) {
        if (!ready) return
        val prog = program ?: return
        prog.use()

        val ndcX = (center.x / fbWidth) * 2f - 1f
        val ndcY = 1f - (center.y / fbHeight) * 2f
        val ndcW = (style.sizePx / fbWidth) * 2f
        val ndcH = (style.sizePx / fbHeight) * 2f

        GLES30.glUniform2f(prog.uniformLocation("uCenter"), ndcX, ndcY)
        GLES30.glUniform2f(prog.uniformLocation("uHalf"), ndcW / 2f, ndcH / 2f)
        GLES30.glUniform4f(prog.uniformLocation("uFill"), r(style.fillColor), g(style.fillColor), b(style.fillColor), 1f)
        GLES30.glUniform4f(prog.uniformLocation("uOutline"), r(style.outlineColor), g(style.outlineColor), b(style.outlineColor), 1f)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    fun delete() {
        val ids = intArrayOf(vbo, vao)
        GLES30.glDeleteBuffers(1, ids, 1)
        GLES30.glDeleteVertexArrays(1, ids, 0)
        program?.delete()
        program = null
        vbo = 0
        vao = 0
        ready = false
    }

    private fun r(color: Int) = ((color shr 16) and 0xFF) / 255f
    private fun g(color: Int) = ((color shr 8) and 0xFF) / 255f
    private fun b(color: Int) = (color and 0xFF) / 255f

    private companion object {
        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 aPos;
            uniform vec2 uCenter;
            uniform vec2 uHalf;
            out vec2 vLocal;
            void main() {
                vLocal = aPos * 2.0 - 1.0;
                gl_Position = vec4(uCenter + vLocal * uHalf, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            precision mediump float;
            in vec2 vLocal;
            uniform vec4 uFill;
            uniform vec4 uOutline;
            out vec4 fragColor;
            void main() {
                float dist = length(vLocal);
                // disc with outline ring
                float outline = 1.0 - smoothstep(0.78, 0.92, dist);
                float disc = 1.0 - smoothstep(0.90, 0.99, dist);
                vec3 col = mix(uOutline.rgb, uFill.rgb, outline);
                fragColor = vec4(col, disc);
            }
        """.trimIndent()
    }
}

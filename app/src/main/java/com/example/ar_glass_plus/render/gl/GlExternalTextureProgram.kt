package com.example.ar_glass_plus.render.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.example.ar_glass_plus.render.geometry.ResolvedGeometry
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
    private val srcRectLoc = uniformLocation("uSrcRect")
    private val rotLoc = uniformLocation("uRot")
    private val dstRectLoc = uniformLocation("uDstRect")

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

    /** GL thread only. Draw the OES texture into [geometry] on [fbWidth]x[fbHeight]. */
    fun draw(
        textureId: Int,
        transform: FloatArray,
        geometry: ResolvedGeometry,
        fbWidth: Int,
        fbHeight: Int,
    ) {
        use()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniformMatrix4fv(texMatrixLoc, 1, false, transform, 0)

        // destination (top-left origin) -> NDC
        val d = geometry.destinationRect
        val ndcX = (d.left / fbWidth) * 2f - 1f
        val ndcY = 1f - ((d.top + d.height) / fbHeight) * 2f
        val ndcW = (d.width / fbWidth) * 2f
        val ndcH = (d.height / fbHeight) * 2f
        GLES30.glUniform4f(dstRectLoc, ndcX, ndcY, ndcW, ndcH)

        val s = geometry.sourceRect
        GLES30.glUniform4f(srcRectLoc, s.left, s.top, s.width, s.height)
        GLES30.glUniform1f(rotLoc, geometry.rotation.glFactor)

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
            uniform vec4 uSrcRect;   // normalized crop (l, t, w, h)
            uniform float uRot;      // 0..3 quarter turns
            uniform vec4 uDstRect;   // NDC rect (x, y, w, h), top-left NDC
            out vec2 vUv;
            void main() {
                vec2 uv = aUv;
                if (uRot == 1.0)      uv = vec2(uv.y, 1.0 - uv.x);
                else if (uRot == 2.0) uv = vec2(1.0 - uv.x, 1.0 - uv.y);
                else if (uRot == 3.0) uv = vec2(1.0 - uv.y, uv.x);
                uv = vec2(uSrcRect.x + uv.x * uSrcRect.z, uSrcRect.y + uv.y * uSrcRect.w);
                vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
                vec2 pos = uDstRect.xy + (aPos * 0.5 + 0.5) * uDstRect.zw;
                gl_Position = vec4(pos, 0.0, 1.0);
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

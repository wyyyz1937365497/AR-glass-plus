package com.example.ar_glass_plus.render.gl

import android.opengl.GLES30
import android.util.Log

/** Thin GLES 3.0 shader program wrapper. All GL types stay inside render/gl. */
open class GlProgram(vertexSource: String, fragmentSource: String) {

    private var programId = 0

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        programId = GLES30.glCreateProgram()
        GLES30.glAttachShader(programId, vs)
        GLES30.glAttachShader(programId, fs)
        GLES30.glLinkProgram(programId)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(programId, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(programId)
            throw IllegalStateException("program link failed: $log")
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        Log.i(TAG, "program compiled, id=$programId")
    }

    fun use() {
        GLES30.glUseProgram(programId)
    }

    fun attribLocation(name: String): Int =
        GLES30.glGetAttribLocation(programId, name)

    fun uniformLocation(name: String): Int =
        GLES30.glGetUniformLocation(programId, name)

    open fun delete() {
        if (programId != 0) {
            GLES30.glDeleteProgram(programId)
            programId = 0
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    private companion object {
        const val TAG = "GlProgram"
    }
}

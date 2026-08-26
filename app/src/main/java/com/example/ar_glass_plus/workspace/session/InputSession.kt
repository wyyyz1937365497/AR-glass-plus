package com.example.ar_glass_plus.workspace

data class ContentSize(
    val width: Int,
    val height: Int,
)

interface InputSession {
    suspend fun onContentReady(contentDisplayId: Int, size: ContentSize)

    suspend fun onContentGone()

    suspend fun dispose()
}

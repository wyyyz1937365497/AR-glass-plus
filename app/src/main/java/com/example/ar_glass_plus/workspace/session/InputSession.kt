package com.example.ar_glass_plus.workspace

import com.example.ar_glass_plus.input.api.MouseButton
import com.example.ar_glass_plus.input.api.PointerAction

data class ContentSize(
    val width: Int,
    val height: Int,
)

interface InputSession {
    suspend fun onContentReady(contentDisplayId: Int, size: ContentSize)


    suspend fun onPointer(
        action: PointerAction,
        button: MouseButton,
        contentX: Float,
        contentY: Float,
    )

    suspend fun onScroll(dx: Float, dy: Float)
    suspend fun onContentGone()

    suspend fun dispose()
}

package com.example.ar_glass_plus.input

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorControllerTest {

    @Test
    fun centerPublishesCenteredVisibleCursorToObserver() {
        // Given
        val observed = mutableListOf<CursorState?>()
        val controller = CursorController(
            onContentSize = { 1280 to 720 },
            onCursorChanged = { cursor: CursorState? -> observed += cursor },
        )

        // When
        controller.center()

        // Then
        val centered = CursorState(640f, 360f, visible = true)
        assertEquals(listOf(centered), observed)
    }

    @Test
    fun virtualDisplayDestroyedPublishesNullToObserver() {
        // Given
        val observed = mutableListOf<CursorState?>()
        val controller = CursorController(
            onContentSize = { 1280 to 720 },
            onCursorChanged = { cursor: CursorState? -> observed += cursor },
        )

        controller.center()

        // When
        controller.onVirtualDisplayDestroyed()

        // Then
        assertEquals(listOf(CursorState(640f, 360f, visible = true), null), observed)
    }

    @Test
    fun moveReadsCursorFromProviderAndPublishesUpdatedCursor() {
        // Given
        var cursor = CursorState(640f, 360f, visible = true)
        val controller = CursorController(
            onContentSize = { 1280 to 720 },
            cursorProvider = { cursor },
            onCursorChanged = { cursor = it ?: cursor },
        )

        // When
        controller.move(dxPad = 10f, dyPad = 5f, padWidth = 100f, padHeight = 100f)

        // Then
        assertEquals(CursorState(768f, 396f, visible = true), cursor)
    }
}

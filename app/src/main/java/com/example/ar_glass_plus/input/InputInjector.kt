package com.example.ar_glass_plus.input

import com.example.ar_glass_plus.root.RootResult

/**
 * Display-aware input injection. Backend-replaceable: ShellInputInjector
 * (root `input -d`) now; Accessibility GestureDescription / binder injection
 * later. Never depends on GL/OES/VirtualDisplay implementation classes.
 */
interface InputInjector {

    suspend fun tap(displayId: Int, x: Float, y: Float): RootResult

    suspend fun swipe(
        displayId: Int,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): RootResult

    suspend fun key(displayId: Int, keyCode: Int): RootResult
}

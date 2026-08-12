package com.example.ar_glass_plus.input

import com.example.ar_glass_plus.root.RootResult
import com.example.ar_glass_plus.root.RootShell

/**
 * Root implementation: `input -d <displayId> ...` via su. One OS call per
 * completed gesture (tap/swipe/back) — never per move event.
 */
class ShellInputInjector(private val shell: RootShell) : InputInjector {

    override suspend fun tap(displayId: Int, x: Float, y: Float): RootResult =
        shell.exec("input -d $displayId tap ${x.toInt()} ${y.toInt()}")

    override suspend fun swipe(
        displayId: Int,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): RootResult =
        shell.exec(
            "input -d $displayId swipe ${fromX.toInt()} ${fromY.toInt()} " +
                "${toX.toInt()} ${toY.toInt()} $durationMs",
        )

    override suspend fun key(displayId: Int, keyCode: Int): RootResult =
        shell.exec("input -d $displayId keyevent $keyCode")
}

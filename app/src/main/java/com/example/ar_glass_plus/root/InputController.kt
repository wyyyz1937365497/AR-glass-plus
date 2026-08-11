package com.example.ar_glass_plus.root

import android.view.KeyEvent

/**
 * Display-aware input injection via `input -d <displayId>`. The display id is
 * never hardcoded — always the runtime value from ExternalDisplayController.
 */
class InputController(private val shell: RootShell) {

    suspend fun tap(displayId: Int, x: Int, y: Int): RootResult =
        shell.exec("input -d $displayId tap $x $y")

    suspend fun swipe(
        displayId: Int,
        x1: Int, y1: Int,
        x2: Int, y2: Int,
        durationMs: Int = 300,
    ): RootResult =
        shell.exec("input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs")

    suspend fun keyEvent(displayId: Int, keyCode: Int): RootResult =
        shell.exec("input -d $displayId keyevent $keyCode")

    suspend fun back(displayId: Int): RootResult =
        keyEvent(displayId, KeyEvent.KEYCODE_BACK)

    suspend fun home(displayId: Int): RootResult =
        keyEvent(displayId, KeyEvent.KEYCODE_HOME)
}

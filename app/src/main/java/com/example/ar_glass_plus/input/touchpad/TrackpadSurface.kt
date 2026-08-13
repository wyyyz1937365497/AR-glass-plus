package com.example.ar_glass_plus.input.touchpad

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The fixed, exclusive touchpad surface (P2.5.2B): owns its pointer stream
 * entirely (every change consumed — the page MUST never scroll), extracts raw
 * finger events, and feeds [TrackpadGestureEngine]. Gesture decisions live in
 * the engine, not here.
 *
 * Structural guard (AGENTS.md UI invariants): this surface is a sibling of the
 * scrollable sidebar, never inside a scroll container.
 */
@Composable
fun TrackpadSurface(
    engine: TrackpadGestureEngine,
    onGesture: (List<TrackpadGesture>) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .background(Color.Transparent)
            .pointerInput(engine) {
                var prevIds: Set<Long> = emptySet()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val time = event.changes.firstOrNull()?.uptimeMillis
                            ?: SystemClock.uptimeMillis()
                        val pressed = event.changes.filter { it.pressed }
                        val ids = pressed.mapTo(mutableSetOf()) { it.id.value }
                        val downIds = ids - prevIds
                        val upChanges = event.changes.filter { !it.pressed && it.id.value in prevIds }

                        for (change in pressed) {
                            if (change.id.value in downIds) {
                                Log.i(TAG, "DOWN id=${change.id.value} pos=${change.position}")
                                val g = engine.onPointerDown(
                                    change.id.value, change.position.x, change.position.y, time,
                                )
                                if (g.isNotEmpty()) onGesture(g)
                            } else if (
                                change.position.x != change.previousPosition.x ||
                                change.position.y != change.previousPosition.y
                            ) {
                                val g = engine.onPointerMove(
                                    change.id.value,
                                    change.position.x - change.previousPosition.x,
                                    change.position.y - change.previousPosition.y,
                                    change.position.x, change.position.y, time,
                                )
                                if (g.isNotEmpty()) onGesture(g)
                            }
                        }
                        for (change in upChanges) {
                            Log.i(TAG, "UP id=${change.id.value} pos=${change.position}")
                            val g = engine.onPointerUp(
                                change.id.value, change.position.x, change.position.y, time,
                            )
                            if (g.isNotEmpty()) onGesture(g)
                        }
                        prevIds = ids
                        event.changes.forEach { it.consume() }
                    }
                }
            },
    ) {
        content()
    }
}

private const val TAG = "TrackpadSurface"

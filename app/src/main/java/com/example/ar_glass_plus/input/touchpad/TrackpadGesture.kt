package com.example.ar_glass_plus.input.touchpad

/**
 * Semantic trackpad gestures — the ONLY output of [TrackpadGestureEngine].
 * Backend-independent: the engine knows nothing about displays, injection,
 * cursors or the root service.
 *
 * RD-style mapping (Microsoft Remote Desktop mouse-pointer baseline):
 *   one finger move        -> Move
 *   one finger tap         -> LeftClick
 *   double tap             -> LeftDoubleClick
 *   double tap + hold/move -> LeftDragStart / LeftDragMove / LeftDragEnd
 *   two finger tap         -> RightClick
 *   two finger move        -> Scroll (vertical + horizontal wheel)
 *   two finger dbl+hold    -> RightDragStart / RightDragMove / RightDragEnd
 */
sealed interface TrackpadGesture {

    /** Cursor-relative movement in touchpad (dp) deltas. */
    data class Move(val dx: Float, val dy: Float) : TrackpadGesture

    /** Single tap: one down+up. */
    data object LeftClick : TrackpadGesture

    /** Two taps within the double-tap timeout (two clicks). */
    data object LeftDoubleClick : TrackpadGesture

    /** Second tap held: button down (drag begins). */
    data object LeftDragStart : TrackpadGesture

    /** Movement while the left button is held. */
    data class LeftDragMove(val dx: Float, val dy: Float) : TrackpadGesture

    /** Drag ended: button up. */
    data object LeftDragEnd : TrackpadGesture

    /** Two-finger tap: right click. */
    data object RightClick : TrackpadGesture

    /** Two-finger move: wheel deltas (positive = down/right content). */
    data class Scroll(val horizontal: Float, val vertical: Float) : TrackpadGesture

    data object RightDragStart : TrackpadGesture

    data class RightDragMove(val dx: Float, val dy: Float) : TrackpadGesture

    data object RightDragEnd : TrackpadGesture
}

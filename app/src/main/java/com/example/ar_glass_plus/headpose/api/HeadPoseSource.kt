package com.example.ar_glass_plus.headpose.api

import com.example.ar_glass_plus.render.spatial.SpatialCamera
import kotlinx.coroutines.flow.StateFlow

/** Non-blocking, replaceable producer for the latest complete head pose. */
interface HeadPoseSource {
    val state: StateFlow<HeadPoseState>

    /** Starts the hardware stream. Safe to call repeatedly. */
    suspend fun start(): Boolean

    /** Stops hardware I/O without blocking the caller. */
    fun stop()

    /** Makes the current orientation the new forward direction. */
    fun recenter()

    /**
     * Returns the newest non-stale camera, or null before lock / after a stream
     * stall. This method runs on the GL thread and must never perform I/O.
     */
    fun latestCamera(nowNanos: Long): SpatialCamera?
}

sealed interface HeadPoseState {
    data object Inactive : HeadPoseState
    data object Starting : HeadPoseState
    data object Calibrating : HeadPoseState
    data class Tracking(val magneticYawActive: Boolean) : HeadPoseState
    data object Stalled : HeadPoseState
    data class Error(val message: String) : HeadPoseState
}

package com.example.ar_glass_plus.display.sbs

import com.example.ar_glass_plus.render.geometry.RenderMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinates physical output mode before publishing the matching GL mode. */
class SbsDisplayModeCoordinator(
    private val kernel: SbsKernelPort,
    private val currentMode: () -> RenderMode,
    private val commitMode: (RenderMode) -> Unit,
) {
    private val mutex = Mutex()

    val kernelState get() = kernel.state

    suspend fun select(mode: RenderMode): SbsOperationResult = mutex.withLock {
        if (mode.requiresSbsOutput) {
            val result = kernel.acquire()
            if (result is SbsOperationResult.Success) commitMode(mode)
            result
        } else {
            val result = kernel.release(SbsReleaseReason.USER_SELECTED_2D)
            // A release warning can mean the kernel module is already gone but
            // 2D could not be observed. Never leave GL advertising SBS after
            // the root side has relinquished its lease.
            commitMode(RenderMode.PASSTHROUGH_2D)
            result
        }
    }

    suspend fun releaseAndReset(reason: SbsReleaseReason): SbsOperationResult = mutex.withLock {
        val result = kernel.release(reason)
        if (currentMode().requiresSbsOutput) {
            commitMode(RenderMode.PASSTHROUGH_2D)
        }
        result
    }
}

val RenderMode.requiresSbsOutput: Boolean
    get() = this != RenderMode.PASSTHROUGH_2D

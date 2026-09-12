package com.example.ar_glass_plus.display.sbs

import com.example.ar_glass_plus.render.geometry.RenderMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coordinates physical output mode before publishing the matching GL mode. */
class SbsDisplayModeCoordinator(
    private val kernel: SbsKernelPort,
    private val currentMode: () -> RenderMode,
    private val commitMode: (RenderMode) -> Unit,
) {
    private val mutex = Mutex()

    val kernelState get() = kernel.state

    suspend fun select(mode: RenderMode): SbsOperationResult = mutex.withLock {
        // Once a root transaction starts, its matching render-mode commit must
        // finish even if the initiating Activity/Composable leaves the screen.
        // Cancellation before this mutex is acquired still prevents any work.
        withContext(NonCancellable) {
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
    }

    suspend fun releaseAndReset(reason: SbsReleaseReason): SbsOperationResult = mutex.withLock {
        withContext(NonCancellable) {
            val result = kernel.release(reason)
            if (currentMode().requiresSbsOutput) {
                commitMode(RenderMode.PASSTHROUGH_2D)
            }
            result
        }
    }
}

val RenderMode.requiresSbsOutput: Boolean
    get() = this != RenderMode.PASSTHROUGH_2D

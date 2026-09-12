package com.example.ar_glass_plus.display.sbs

import com.example.ar_glass_plus.render.geometry.RenderMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SbsDisplayModeCoordinatorTest {

    @Test
    fun sbsModeCommitsOnlyAfterKernelAcquireSucceeds() = runBlocking {
        val kernel = FakeKernelPort()
        var mode = RenderMode.PASSTHROUGH_2D
        val coordinator = coordinator(kernel, { mode }, { mode = it })

        val result = coordinator.select(RenderMode.SBS_DUPLICATE)

        assertTrue(result is SbsOperationResult.Success)
        assertEquals(1, kernel.acquireCount)
        assertEquals(RenderMode.SBS_DUPLICATE, mode)
    }

    @Test
    fun failedAcquireNeverPublishesSbsRenderMode() = runBlocking {
        val kernel = FakeKernelPort(
            acquireResult = SbsOperationResult.Failure("not-installed", 127),
        )
        var mode = RenderMode.PASSTHROUGH_2D
        val coordinator = coordinator(kernel, { mode }, { mode = it })

        val result = coordinator.select(RenderMode.SBS_STEREO)

        assertTrue(result is SbsOperationResult.Failure)
        assertEquals(RenderMode.PASSTHROUGH_2D, mode)
    }

    @Test
    fun calibrationAlsoRequiresThePhysicalSbsOutput() = runBlocking {
        val kernel = FakeKernelPort()
        var mode = RenderMode.PASSTHROUGH_2D
        val coordinator = coordinator(kernel, { mode }, { mode = it })

        coordinator.select(RenderMode.CALIBRATION)

        assertEquals(1, kernel.acquireCount)
        assertEquals(RenderMode.CALIBRATION, mode)
    }

    @Test
    fun selecting2dReleasesThenPublishes2dEvenWithCleanupWarning() = runBlocking {
        val kernel = FakeKernelPort(
            releaseResult = SbsOperationResult.Failure("2d-not-verified", 10),
        )
        var mode = RenderMode.SBS_DUPLICATE
        val commits = mutableListOf<RenderMode>()
        val coordinator = coordinator(kernel, { mode }) {
            mode = it
            commits += it
        }

        val result = coordinator.select(RenderMode.PASSTHROUGH_2D)

        assertTrue(result is SbsOperationResult.Failure)
        assertEquals(listOf(RenderMode.PASSTHROUGH_2D), commits)
        assertEquals(SbsReleaseReason.USER_SELECTED_2D, kernel.releaseReasons.single())
    }

    @Test
    fun cancellationAfterAcquireStartsStillCommitsMatchingRenderMode() = runBlocking {
        val acquireEntered = CompletableDeferred<Unit>()
        val allowAcquireToFinish = CompletableDeferred<Unit>()
        val kernel = FakeKernelPort(
            beforeAcquireResult = {
                acquireEntered.complete(Unit)
                allowAcquireToFinish.await()
            },
        )
        var mode = RenderMode.PASSTHROUGH_2D
        val coordinator = coordinator(kernel, { mode }, { mode = it })

        val selection = launch {
            coordinator.select(RenderMode.SBS_STEREO)
        }
        acquireEntered.await()
        selection.cancel()
        allowAcquireToFinish.complete(Unit)
        selection.join()

        assertTrue(selection.isCancelled)
        assertEquals(RenderMode.SBS_STEREO, mode)
    }

    @Test
    fun lifecycleReleaseResetsSbsButDoesNotDisturbExisting2d() = runBlocking {
        val kernel = FakeKernelPort()
        var mode = RenderMode.SBS_STEREO
        val commits = mutableListOf<RenderMode>()
        val coordinator = coordinator(kernel, { mode }) {
            mode = it
            commits += it
        }

        coordinator.releaseAndReset(SbsReleaseReason.OUTPUT_DISCONNECTED)
        coordinator.releaseAndReset(SbsReleaseReason.APP_BACKGROUNDED)

        assertEquals(listOf(RenderMode.PASSTHROUGH_2D), commits)
        assertEquals(
            listOf(SbsReleaseReason.OUTPUT_DISCONNECTED, SbsReleaseReason.APP_BACKGROUNDED),
            kernel.releaseReasons,
        )
    }

    private fun coordinator(
        kernel: SbsKernelPort,
        currentMode: () -> RenderMode,
        commitMode: (RenderMode) -> Unit,
    ) = SbsDisplayModeCoordinator(kernel, currentMode, commitMode)

    private class FakeKernelPort(
        private val acquireResult: SbsOperationResult = SbsOperationResult.Success("active"),
        private val releaseResult: SbsOperationResult = SbsOperationResult.Success("inactive"),
        private val beforeAcquireResult: suspend () -> Unit = {},
    ) : SbsKernelPort {
        private val mutableState = MutableStateFlow<SbsKernelState>(SbsKernelState.Inactive)
        override val state: StateFlow<SbsKernelState> = mutableState
        var acquireCount = 0
        val releaseReasons = mutableListOf<SbsReleaseReason>()

        override suspend fun acquire(): SbsOperationResult {
            acquireCount += 1
            beforeAcquireResult()
            return acquireResult
        }

        override suspend fun release(reason: SbsReleaseReason): SbsOperationResult {
            releaseReasons += reason
            return releaseResult
        }
    }
}

package com.example.ar_glass_plus.display.sbs

import com.example.ar_glass_plus.root.RootResult
import com.example.ar_glass_plus.root.RootShell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SbsKernelControllerTest {

    @Test
    fun acquireUsesPinnedModuleControllerAndMarksLeaseActive() = runBlocking {
        val shell = FakeRootShell(
            RootResult(0, "state=active token=test-token mode=3840x1080\n", ""),
        )
        val controller = SbsKernelController(shell, pidProvider = { 4242 }, leaseToken = "test-token")

        val result = controller.acquire()

        assertTrue(result is SbsOperationResult.Success)
        assertTrue(controller.state.value is SbsKernelState.Active)
        assertEquals(
            "/data/adb/modules/ar_glass_plus_dpfix/bin/ar-glass-dpctl " +
                "acquire 4242 test-token",
            shell.commands.single().first,
        )
        assertEquals(20_000L, shell.commands.single().second)
    }

    @Test
    fun acquireFailureRemainsInactiveAtRenderCoordinatorBoundary() = runBlocking {
        val shell = FakeRootShell(RootResult(3, "error=unsupported-target-or-missing-module\n", ""))
        val controller = SbsKernelController(shell, pidProvider = { 7 }, leaseToken = "token")

        val result = controller.acquire()

        assertTrue(result is SbsOperationResult.Failure)
        assertTrue(controller.state.value is SbsKernelState.Error)
    }

    @Test
    fun successfulReleaseUsesSameTokenAndBecomesInactive() = runBlocking {
        val shell = FakeRootShell(
            RootResult(0, "state=active token=token mode=3840x1080\n", ""),
            RootResult(0, "state=inactive reason=app-backgrounded\n", ""),
        )
        val controller = SbsKernelController(shell, pidProvider = { 88 }, leaseToken = "token")
        controller.acquire()

        val result = controller.release(SbsReleaseReason.APP_BACKGROUNDED)

        assertTrue(result is SbsOperationResult.Success)
        assertEquals(
            "/data/adb/modules/ar_glass_plus_dpfix/bin/ar-glass-dpctl " +
                "release token app-backgrounded",
            shell.commands.last().first,
        )
        assertEquals(SbsKernelState.Inactive, controller.state.value)
    }

    @Test
    fun releasedWithWarningDropsOwnershipAndDoesNotRepeatRmmod() = runBlocking {
        val shell = FakeRootShell(
            RootResult(0, "state=active token=token mode=3840x1080\n", ""),
            RootResult(10, "state=inactive warning=display-2d-not-verified\n", ""),
        )
        val controller = SbsKernelController(shell, pidProvider = { 88 }, leaseToken = "token")
        controller.acquire()

        val first = controller.release(SbsReleaseReason.USER_SELECTED_2D)
        val second = controller.release(SbsReleaseReason.OUTPUT_DISCONNECTED)

        assertTrue(first is SbsOperationResult.Failure)
        assertTrue(second is SbsOperationResult.Success)
        assertEquals(2, shell.commands.size)
    }

    private class FakeRootShell(vararg results: RootResult) : RootShell {
        private val queued = ArrayDeque(results.toList())
        val commands = mutableListOf<Pair<String, Long>>()

        override suspend fun isAvailable(): Boolean = true

        override suspend fun exec(command: String, timeoutMs: Long): RootResult {
            commands += command to timeoutMs
            return queued.removeFirst()
        }
    }
}

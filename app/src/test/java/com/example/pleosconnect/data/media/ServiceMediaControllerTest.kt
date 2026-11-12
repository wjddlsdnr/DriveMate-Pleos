package com.example.pleosconnect.data.media

import com.example.pleosconnect.contract.integration.MediaCommand
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.integration.*
import com.example.pleosconnect.pleos.vehicle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceMediaControllerTest {
    private class Gateway : MediaServiceGateway {
        var commands = 0
        var connections = 0
        var waitMillis = 0L
        var failure: FailureReason? = null
        override suspend fun apps() = listOf(MediaApp("app/service", "Test"))
        override suspend fun connect(app: MediaApp): MediaConnection { connections++; return MediaConnection("root") }
        override suspend fun children(parent: String) = emptyList<MediaEntry>()
        override suspend fun command(command: MediaCommand): OperationResult<Unit> {
            commands++; delay(waitMillis)
            return failure?.let { OperationResult.Failure(it) } ?: OperationResult.Success(Unit)
        }
        override suspend fun play(entry: MediaEntry) = command(MediaCommand.PlayMusic(entry.id))
        override fun disconnect() = Unit
    }
    @Test fun connectNeverDispatchesPlaybackAndUnconnectedPortFails() = runTest {
        val g = Gateway(); val c = ServiceMediaController(g)
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), c.execute(MediaCommand.Stop))
        c.connect(MediaApp("app/service", "Test")); assertEquals(0, g.commands)
    }
    @Test fun newsAndInvalidSearchNeverDispatch() = runTest {
        val g = Gateway(); val c = ServiceMediaController(g); c.connect(MediaApp("app/service", "Test"))
        assertEquals(OperationResult.Failure(FailureReason.UNSUPPORTED), c.execute(MediaCommand.PlayNewsAudio))
        assertEquals(OperationResult.Failure(FailureReason.INVALID_REQUEST), c.execute(MediaCommand.PlayMusic(" ")))
        assertEquals(OperationResult.Failure(FailureReason.INVALID_REQUEST), c.play(MediaEntry("x", "Folder", true, false)))
        assertEquals(0, g.commands)
    }
    @Test fun serviceFailureAndTimeoutAreNotSuccess() = runTest {
        val g = Gateway(); val c = ServiceMediaController(g); c.connect(MediaApp("app/service", "Test"))
        g.failure = FailureReason.UNSUPPORTED
        assertEquals(OperationResult.Failure(FailureReason.UNSUPPORTED), c.execute(MediaCommand.Next))
        g.waitMillis = 9_000
        assertEquals(OperationResult.Failure(FailureReason.TIMEOUT), c.execute(MediaCommand.PlayMusic("jazz")))
    }
    @Test fun disconnectRejectsLateResult() = runTest {
        val g = Gateway(); val c = ServiceMediaController(g); c.connect(MediaApp("app/service", "Test"))
        g.waitMillis = 1_000
        val result = async { c.execute(MediaCommand.PlayMusic("jazz")) }; runCurrent(); c.disconnect()
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), result.await())
    }
    @Test fun cancellationPropagatesWithoutRetry() = runTest {
        val g = Gateway(); val c = ServiceMediaController(g); c.connect(MediaApp("app/service", "Test")); g.waitMillis = 5_000
        val job = launch { c.execute(MediaCommand.Next) }; runCurrent(); job.cancelAndJoin(); assertEquals(1, g.commands)
    }
    @Test fun setupRequiresFreshFiniteParkAndZeroSpeedButNoSafeLocationClaim() {
        val base = VehicleReadUiState(enabled = true, nowMillis = 1_000, snapshot = VehicleReadSnapshot(
            gear = VehicleReading(ReadGear.PARK, 1_000), speedMetersPerSecond = VehicleReading(0f, 1_000)))
        assertTrue(mediaSetupAllowed(base))
        assertFalse(mediaSetupAllowed(base.copy(nowMillis = 6_001)))
        assertFalse(mediaSetupAllowed(base.copy(snapshot = base.snapshot.copy(speedMetersPerSecond = VehicleReading(Float.NaN, 1_000)))))
        assertFalse(mediaSetupAllowed(base.copy(snapshot = base.snapshot.copy(gear = VehicleReading(ReadGear.DRIVE, 1_000)))))
    }
}

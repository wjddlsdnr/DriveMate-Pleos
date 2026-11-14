package com.example.pleosconnect.voice

import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.mock.voice.MockVoiceBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SerializedVoiceServiceTest {
    private class Backend : VoiceBackend {
        val played = CompletableDeferred<OperationResult<Unit>>()
        val answer = CompletableDeferred<RecognitionResult>()
        val events = mutableListOf<String>()
        var cleanupFails = false
        override suspend fun speak(text: String): OperationResult<Unit> {
            events += "speak"
            return played.await()
        }
        override suspend fun listen(): RecognitionResult {
            events += "listen"
            return answer.await()
        }
        override suspend fun stop() { events += "stop"; if (cleanupFails) error("cleanup failed") }
        override suspend fun close() { events += "close" }
    }

    @Test fun questionWaitsForPlaybackAndRejectsCompetingRequests() = runTest {
        val backend = Backend()
        val service = SerializedVoiceService(backend)
        val question = async { service.ask("상태가 어떠세요?", 5.seconds) }
        runCurrent()
        assertEquals(VoicePhase.SPEAKING, service.phase.value)
        assertEquals(RecognitionResult.ServiceUnavailable, service.listen(5.seconds))
        assertTrue(service.speak("중복") is OperationResult.Failure)
        assertEquals(listOf("speak"), backend.events)
        backend.played.complete(OperationResult.Success(Unit))
        runCurrent()
        assertEquals(VoicePhase.LISTENING, service.phase.value)
        backend.answer.complete(RecognitionResult.Text("괜찮아요"))
        assertEquals(QuestionResult.Answer(RecognitionResult.Text("괜찮아요")), question.await())
        assertEquals(listOf("speak", "listen", "stop"), backend.events)
        assertEquals(VoicePhase.IDLE, service.phase.value)
    }

    @Test fun failedPlaybackNeverStartsListening() = runTest {
        val backend = Backend()
        backend.played.complete(OperationResult.Failure(FailureReason.PERMISSION_DENIED))
        val result = SerializedVoiceService(backend).ask("질문", 5.seconds)
        assertEquals(QuestionResult.PlaybackFailed(FailureReason.PERMISSION_DENIED), result)
        assertFalse(backend.events.contains("listen"))
    }

    @Test fun stalledPlaybackTimesOutWithoutStartingStt() = runTest {
        val backend = Backend()
        val result = SerializedVoiceService(backend, 2.seconds).ask("질문", 5.seconds)
        assertEquals(QuestionResult.PlaybackFailed(FailureReason.TIMEOUT), result)
        assertEquals(2000L, currentTime)
        assertFalse(backend.events.contains("listen"))
        assertEquals("stop", backend.events.last())
    }

    @Test fun localListenDeadlineIsTimeoutNeverNoResponse() = runTest {
        val backend = Backend()
        val service = SerializedVoiceService(backend)
        assertEquals(RecognitionResult.Timeout, service.listen(5.seconds))
        assertEquals(5000L, currentTime)
        assertEquals("stop", backend.events.last())
    }

    @Test fun distinctRecognitionOutcomesSurviveTransport() = runTest {
        val outcomes = listOf(
            RecognitionResult.NoResponse, RecognitionResult.Timeout,
            RecognitionResult.PermissionDenied, RecognitionResult.RecognitionFailure,
            RecognitionResult.ServiceUnavailable, RecognitionResult.Text("안 졸려"),
        )
        val service = SerializedVoiceService(MockVoiceBackend(outcomes))
        outcomes.forEach { assertEquals(it, service.listen(5.seconds)) }
    }

    @Test fun blankTextIsNotConfirmedSilence() = runTest {
        val service = SerializedVoiceService(MockVoiceBackend(listOf(RecognitionResult.Text("  "))))
        assertEquals(RecognitionResult.RecognitionFailure, service.listen(5.seconds))
    }

    @Test fun callerCancellationCleansUpAndPropagates() = runTest {
        val backend = Backend()
        val service = SerializedVoiceService(backend)
        val operation = launch { service.listen(5.seconds) }
        runCurrent()
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
        assertEquals("stop", backend.events.last())
        assertEquals(VoicePhase.IDLE, service.phase.value)
    }

    @Test fun stopCancelsPlaybackAndAllowsNewTransaction() = runTest {
        val backend = Backend()
        val service = SerializedVoiceService(backend)
        val operation = launch { service.ask("질문", 5.seconds) }
        runCurrent()
        service.stop()
        assertTrue(operation.isCancelled)
        assertFalse(backend.events.contains("listen"))
        backend.answer.complete(RecognitionResult.Text("응답"))
        assertEquals(RecognitionResult.Text("응답"), service.listen(5.seconds))
    }

    @Test fun closeCancelsListeningReleasesOnceAndRejectsReuse() = runTest {
        val backend = Backend()
        val service = SerializedVoiceService(backend)
        val operation = launch { service.listen(5.seconds) }
        runCurrent()
        service.close()
        service.close()
        assertTrue(operation.isCancelled)
        assertEquals(1, backend.events.count { it == "close" })
        assertEquals(VoicePhase.UNAVAILABLE, service.phase.value)
        assertEquals(RecognitionResult.ServiceUnavailable, service.listen(5.seconds))
        assertTrue(service.speak("재시작") is OperationResult.Failure)
    }

    @Test fun failedCleanupBlocksFurtherMicrophoneRequests() = runTest {
        val backend = Backend()
        backend.answer.complete(RecognitionResult.Text("답변"))
        backend.cleanupFails = true
        val service = SerializedVoiceService(backend)
        service.listen(5.seconds)
        assertEquals(VoicePhase.UNAVAILABLE, service.phase.value)
        assertEquals(RecognitionResult.ServiceUnavailable, service.listen(5.seconds))
        assertEquals(1, backend.events.count { it == "listen" })
    }

    @Test fun thrownPermissionAndServiceErrorsAreDistinct() = runTest {
        val backend = object : VoiceBackend {
            override suspend fun speak(text: String): OperationResult<Unit> = throw SecurityException()
            override suspend fun listen(): RecognitionResult = error("service disconnected")
            override suspend fun stop() = Unit
            override suspend fun close() = Unit
        }
        val service = SerializedVoiceService(backend)
        assertEquals(OperationResult.Failure(FailureReason.PERMISSION_DENIED), service.speak("질문"))
        assertEquals(RecognitionResult.ServiceUnavailable, service.listen(5.seconds))
    }

    @Test fun invalidTimeoutRejectedBeforeBackendUse() = runTest {
        val backend = Backend()
        val service = SerializedVoiceService(backend)
        listOf(Duration.ZERO, (-1).seconds, Duration.INFINITE).forEach { timeout ->
            try { service.listen(timeout); fail("invalid timeout accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(backend.events.isEmpty())
    }

    @Test fun cancellationDoesNotAllowReuseUntilCleanupFinishes() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var calls = 0
        val backend = object : VoiceBackend {
            override suspend fun speak(text: String): OperationResult<Unit> = OperationResult.Success(Unit)
            override suspend fun listen(): RecognitionResult { calls++; awaitCancellation() }
            override suspend fun stop() {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
            }
            override suspend fun close() = Unit
        }
        val service = SerializedVoiceService(backend)
        val operation = launch { service.listen(5.seconds) }
        runCurrent()
        operation.cancel()
        cleanupStarted.await()
        assertFalse(operation.isCompleted)
        assertEquals(RecognitionResult.ServiceUnavailable, service.listen(5.seconds))
        assertEquals(1, calls)
        releaseCleanup.complete(Unit)
        operation.join()
        assertEquals(VoicePhase.IDLE, service.phase.value)
    }

    @Test fun lateCompletionOfCancelledQuestionCannotAnswerNextQuestion() = runTest {
        val backend = Backend()
        val service = SerializedVoiceService(backend)
        val operation = launch { service.ask("이전 질문", 5.seconds) }
        runCurrent()
        service.stop()
        backend.played.complete(OperationResult.Success(Unit))
        backend.answer.complete(RecognitionResult.Text("현재 답변"))
        assertTrue(operation.isCancelled)
        assertEquals(RecognitionResult.Text("현재 답변"), service.listen(5.seconds))
        assertEquals(1, backend.events.count { it == "listen" })
    }
}

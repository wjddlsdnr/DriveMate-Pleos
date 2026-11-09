package com.example.pleosconnect.pleos.voice

import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import com.example.pleosconnect.contract.model.FailureReason
import com.example.pleosconnect.contract.voice.RecognitionResult
import org.junit.Assert.*
import org.junit.Test

class PleosVoiceResultTest {
    @Test fun partialTextIsNotAnAnswerOrConfirmedSilence() {
        val result = PleosRecognitionResult()
        result.update("졸려", false)
        assertEquals(RecognitionResult.RecognitionFailure, result.ended())
    }
    @Test fun finalTextPreservesNegation() {
        val result = PleosRecognitionResult()
        result.update("졸려", false)
        result.update("안 졸려", true)
        assertEquals(RecognitionResult.Text("안 졸려"), result.ended())
    }
    @Test fun emptyFinalIsUncertain() {
        val result = PleosRecognitionResult()
        result.update("", true)
        assertEquals(RecognitionResult.RecognitionFailure, result.ended())
    }
    @Test fun sttErrorMappingNeverInventsSilence() {
        assertEquals(RecognitionResult.PermissionDenied, mapSttError(SttError.PERMISSION_DENIED))
        assertEquals(RecognitionResult.Timeout, mapSttError(SttError.TIMEOUT))
        assertEquals(RecognitionResult.RecognitionFailure, mapSttError(SttError.MIC_ERROR))
        assertEquals(RecognitionResult.ServiceUnavailable, mapSttError(SttError.SERVER_INTERNAL_ERROR))
        SttError.entries.forEach { assertNotEquals(RecognitionResult.NoResponse, mapSttError(it)) }
    }
    @Test fun ttsErrorsDoNotBecomePlaybackSuccess() {
        assertEquals(FailureReason.PERMISSION_DENIED, mapTtsError(TtsError.PERMISSION_DENIED))
        assertEquals(FailureReason.TIMEOUT, mapTtsError(TtsError.TIMEOUT))
        assertEquals(FailureReason.UNSUPPORTED, mapTtsError(TtsError.NOT_SUPPORTED_API))
        assertEquals(FailureReason.SERVICE_UNAVAILABLE, mapTtsError(TtsError.UNKNOWN))
    }
}

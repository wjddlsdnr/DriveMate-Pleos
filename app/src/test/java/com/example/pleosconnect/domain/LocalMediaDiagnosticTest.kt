package com.example.pleosconnect.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

class LocalMediaDiagnosticTest {
    @Test fun distinguishesExactAliasFromParticleAndRecognitionVariationsWithoutAcceptingThem() {
        assertEquals(LocalMediaDiagnostic.EXACT_ALIAS, LocalMediaDiagnostic.classify("로컬 파일"))
        assertEquals(LocalMediaDiagnostic.OBJECT_PARTICLE, LocalMediaDiagnostic.classify("로컬 파일을"))
        assertEquals(LocalMediaDiagnostic.LATIN_TEST, LocalMediaDiagnostic.classify("TEST 음"))
        assertEquals(LocalMediaDiagnostic.MISSING_AUDIO_NOUN, LocalMediaDiagnostic.classify("테스트"))
        assertEquals(LocalMediaDiagnostic.NFC_ONLY, LocalMediaDiagnostic.classify(Normalizer.normalize("테스트 음", Normalizer.Form.NFD)))
    }
    @Test fun arbitraryMusicAndNegationsNeverBecomeLocalAliases() {
        listOf("발라드", "테스트 음 말고 재즈", "로컬 파일 말고", "테스트 음 같은 음악", "개인적인 임의 검색어").forEach {
            assertEquals(LocalMediaDiagnostic.OTHER, LocalMediaDiagnostic.classify(it))
        }
        assertEquals(NormalCommand.Select(com.example.pleosconnect.contract.model.DrivingMode.MUSIC, "로컬 파일을"),
            NormalCommandParser().parse("로컬 파일을 틀어줘"))
    }
}

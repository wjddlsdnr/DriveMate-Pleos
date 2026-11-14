package com.example.pleosconnect.voice

import com.example.pleosconnect.contract.model.ConditionReport
import com.example.pleosconnect.domain.ConditionClassifier
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer

class TestPhraseDiagnosticTest {
    @Test fun exactAndExistingNormalization() {
        assertEquals(TestPhraseMatch.EXACT, testPhraseMatch("괜찮아요"))
        assertEquals(TestPhraseMatch.NFC_WHITESPACE_PUNCTUATION, testPhraseMatch(" 괜찮아요! "))
        assertEquals(TestPhraseMatch.NFC_WHITESPACE_PUNCTUATION,
            testPhraseMatch(Normalizer.normalize("괜찮아요", Normalizer.Form.NFD)))
    }
    @Test fun invisibleDifferencesAreFixedCategoriesOnly() {
        assertEquals(TestPhraseMatch.UNICODE_SPACE, testPhraseMatch("괜찮아요\u00a0"))
        assertEquals(TestPhraseMatch.FORMAT_CHARACTER, testPhraseMatch("괜찮아요\u200b"))
    }
    @Test fun ambiguousAndDifferentAnswersNeverMatch() {
        for (text in listOf("", "괜찮아요?", "괜찮아요,", "괜찮아 하지만 졸려", "안 괜찮아요", "음악 틀어줘")) {
            assertEquals(TestPhraseMatch.DIFFERENT, testPhraseMatch(text))
        }
    }
    @Test fun diagnosticsNeverRelaxClassifier() {
        for (text in listOf("괜찮아요\u00a0", "괜찮아요\u200b", "괜찮아요?", "괜찮아 하지만 졸려")) {
            testPhraseMatch(text)
            assertEquals(ConditionReport.UNCONFIRMED, ConditionClassifier().classify(text))
        }
    }
}

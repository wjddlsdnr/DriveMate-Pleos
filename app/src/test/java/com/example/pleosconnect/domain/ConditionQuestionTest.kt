package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.model.ConditionReport
import com.example.pleosconnect.contract.voice.RecognitionResult
import org.junit.Assert.*
import org.junit.Test

class ConditionQuestionTest {
    private fun report(text: String, question: ConditionQuestion) = ConditionClassifier().classify(
        (question.interpret(RecognitionResult.Text(text)) as RecognitionResult.Text).value)

    @Test fun yesNoDependsOnSingleQuestionPolarity() {
        listOf("네", "예", "응").forEach {
            assertEquals(ConditionReport.MILD_FATIGUE, report(it, ConditionQuestion.FATIGUE))
            assertEquals(ConditionReport.NORMAL, report(it, ConditionQuestion.WELLBEING))
            assertEquals(ConditionReport.UNCONFIRMED, report(it, ConditionQuestion.UNSPECIFIED))
        }
        listOf("아니", "아니요", "아니오").forEach {
            assertEquals(ConditionReport.NORMAL, report(it, ConditionQuestion.FATIGUE))
            assertEquals(ConditionReport.UNCONFIRMED, report(it, ConditionQuestion.WELLBEING))
            assertEquals(ConditionReport.UNCONFIRMED, report(it, ConditionQuestion.UNSPECIFIED))
        }
    }
    @Test fun explicitNegationsAndContradictionsStayConservative() {
        listOf("안졸림", "안 졸림!", "졸리지 않음", "안 피곤함").forEach {
            assertEquals(ConditionReport.NORMAL, report(it, ConditionQuestion.FATIGUE))
        }
        listOf("네 아니요", "안 괜찮아", "안 졸린 건 아니야", "괜찮아 하지만 졸려").forEach {
            assertEquals(ConditionReport.UNCONFIRMED, report(it, ConditionQuestion.FATIGUE))
        }
    }
    @Test fun nonTextResultsArePreserved() {
        listOf(RecognitionResult.NoResponse, RecognitionResult.Timeout, RecognitionResult.PermissionDenied,
            RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable).forEach {
            assertSame(it, ConditionQuestion.FATIGUE.interpret(it))
        }
    }
}

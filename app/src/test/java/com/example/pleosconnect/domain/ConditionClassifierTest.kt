package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.model.ConditionReport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ConditionClassifierTest(private val input: String, private val expected: ConditionReport) {
    @Test fun classifiesWholeAnswerConservatively() {
        assertEquals(expected, ConditionClassifier().classify(input))
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}: {0}")
        fun examples(): List<Array<Any>> = buildList {
            fun cases(report: ConditionReport, vararg inputs: String) {
                inputs.forEach { add(arrayOf(it, report)) }
            }
            cases(ConditionReport.NORMAL, "괜찮아", "괜찮아요!", "이제 괜찮아", "안 졸려", "안졸려요",
                "졸리지 않아", "졸리지 않아요", "안 피곤해", "피곤하지 않아", "피곤하지 않습니다",
                "  안  졸려요. ", "안 졸려, 안 피곤해", "괜찮아요 그리고 안 졸려요")
            cases(ConditionReport.MILD_FATIGUE, "졸려", "졸려요", "좀 졸려", "조금 피곤해", "좀피곤해요",
                "피곤해요!", "좀 졸려 그리고 조금 피곤해")
            cases(ConditionReport.PERSISTENT_FATIGUE, "계속 졸려", "여전히 졸려요", "아직 졸려", "계속 피곤해요")
            cases(ConditionReport.SEVERE, "너무 졸려", "너무 피곤해요", "운전 못 하겠어", "운전못하겠어요",
                "운전할 수 없어요", "운전하기 어려워요")
            cases(ConditionReport.UNCONFIRMED, "", "  ", "네", "아니", "몰라", "아마 괜찮을걸",
                "안 졸려?", "안 졸려？", "안 졸린 건 아니야", "졸리지 않은 건 아니야",
                "운전 못 하는 건 아니야", "너무 안 졸려", "어제 너무 졸려", "졸리면 쉴게",
                "괜찮아 하지만 졸려", "안 졸려, 조금 피곤해", "너무 졸려 그런데 괜찮아",
                "괜찮지만 졸려", "졸려는 아니야", "졸려라는 노래", "졸리지 않아도 피곤해",
                "괜찮아 그리고 잘 모르겠어", "괜찮아,", "계속 졸려, 너무 졸려")
        }
    }
}

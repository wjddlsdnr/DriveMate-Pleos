package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.model.ConditionReport
import java.text.Normalizer

/** Conservative finite vocabulary for condition answers, not general language understanding. */
class ConditionClassifier {
    fun classify(text: String): ConditionReport {
        val normalized = normalize(text)
        if (normalized.isEmpty() || '?' in normalized || '？' in normalized) return ConditionReport.UNCONFIRMED
        val clauses = normalized.split(Regex("[,，]|하지만|그렇지만|그런데|그래도|근데|그리고"))
        val reports = clauses.map { vocabulary[it] ?: ConditionReport.UNCONFIRMED }
        return reports.distinct().singleOrNull() ?: ConditionReport.UNCONFIRMED
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace(Regex("\\s+"), "")
            .trimEnd('.', '!', '。', '！')

    private val vocabulary: Map<String, ConditionReport> = buildMap {
        fun phrases(report: ConditionReport, vararg words: String) {
            words.forEach { put(normalize(it), report) }
        }
        phrases(ConditionReport.NORMAL,
            "괜찮아", "괜찮아요", "괜찮습니다", "이제 괜찮아", "이제 괜찮아요",
            "안 졸려", "안 졸려요", "졸리지 않아", "졸리지 않아요", "졸리지 않습니다",
            "안 졸림", "졸리지 않음", "안 피곤함", "피곤하지 않음",
            "안 피곤해", "안 피곤해요", "피곤하지 않아", "피곤하지 않아요", "피곤하지 않습니다")
        phrases(ConditionReport.MILD_FATIGUE,
            "졸려", "졸려요", "좀 졸려", "좀 졸려요", "조금 졸려", "조금 졸려요",
            "피곤해", "피곤해요", "좀 피곤해", "좀 피곤해요", "조금 피곤해", "조금 피곤해요")
        phrases(ConditionReport.PERSISTENT_FATIGUE,
            "계속 졸려", "계속 졸려요", "여전히 졸려", "여전히 졸려요", "아직 졸려", "아직 졸려요",
            "계속 피곤해", "계속 피곤해요", "여전히 피곤해", "여전히 피곤해요")
        phrases(ConditionReport.SEVERE,
            "너무 졸려", "너무 졸려요", "너무 피곤해", "너무 피곤해요", "심하게 졸려", "심하게 졸려요",
            "운전 못 하겠어", "운전 못 하겠어요", "운전하기 어려워", "운전하기 어려워요",
            "운전할 수 없어", "운전할 수 없어요")
    }
}

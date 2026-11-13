package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.voice.RecognitionResult
import java.text.Normalizer

/** Interpret only a final answer to a known, single-polarity question. Never handles partial STT. */
enum class ConditionQuestion {
    FATIGUE, WELLBEING, UNSPECIFIED;

    fun interpret(answer: RecognitionResult): RecognitionResult {
        if (answer !is RecognitionResult.Text) return answer
        val key = Normalizer.normalize(answer.value, Normalizer.Form.NFC)
            .replace(Regex("\\s+"), "").trimEnd('.', '!', '。', '！')
        val yes = key in setOf("네", "예", "응")
        val no = key in setOf("아니", "아니요", "아니오")
        if (!yes && !no) return answer
        val meaning = when (this) {
            FATIGUE -> if (yes) "조금 피곤해" else "안 졸려"
            WELLBEING -> if (yes) "괜찮아" else return answer
            UNSPECIFIED -> return answer
        }
        return RecognitionResult.Text(meaning)
    }
}

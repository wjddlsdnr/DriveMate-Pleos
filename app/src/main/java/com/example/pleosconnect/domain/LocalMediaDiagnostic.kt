package com.example.pleosconnect.domain

import java.text.Normalizer

/** Fixed debug categories only; never returns a transcript or changes playback decisions. */
enum class LocalMediaDiagnostic {
    EXACT_ALIAS, NFC_ONLY, OBJECT_PARTICLE, LATIN_TEST, MISSING_AUDIO_NOUN, OTHER;

    companion object {
        fun classify(query: String): LocalMediaDiagnostic {
            val compact = query.replace(Regex("\\s+"), "")
            val aliases = setOf("로컬파일", "선택한파일", "테스트음")
            if (compact in aliases) return EXACT_ALIAS
            val normalized = Normalizer.normalize(compact, Normalizer.Form.NFC)
            if (normalized in aliases) return NFC_ONLY
            if (normalized in aliases.flatMap { listOf("${it}을", "${it}를") }) return OBJECT_PARTICLE
            if (normalized.lowercase() in setOf("test음", "test음악", "test")) return LATIN_TEST
            if (normalized in setOf("테스트", "로컬", "선택한")) return MISSING_AUDIO_NOUN
            return OTHER
        }
    }
}

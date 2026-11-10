package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.model.DrivingMode
import java.text.Normalizer

sealed interface NormalCommand {
    data class Select(val mode: DrivingMode, val query: String? = null) : NormalCommand
    data object Stop : NormalCommand
    data object Next : NormalCommand
    data object Unknown : NormalCommand
}
data class MusicPreference(val query: String, val genre: String? = null, val artist: String? = null, val playlist: String? = null)

/** Explicit finite grammar; unsupported or conflicting language is never executed. */
class NormalCommandParser {
    fun parse(text: String): NormalCommand {
        val raw = Normalizer.normalize(text, Normalizer.Form.NFC).trim().trimEnd('.', '!', '。', '！')
        var key = compact(raw)
        if ('?' in key || '？' in key) return NormalCommand.Unknown
        key = key.replace(Regex("^아니(?:요)?[,，]?"), "")
        when (key) {
            "조용히", "조용한운전", "조용한운전모드", "오늘은조용히", "조용히운전할래",
            "조용하게운전할래", "오늘은조용하게운전할래", "음악말고조용히" -> return NormalCommand.Select(DrivingMode.QUIET)
            "뉴스", "뉴스틀어줘", "뉴스들을래", "음악말고뉴스", "음악말고뉴스틀어줘",
            "오늘은음악말고뉴스들을래", "노래말고뉴스" -> return NormalCommand.Select(DrivingMode.NEWS_AUDIO)
            "음악", "음악틀어줘", "노래틀어줘", "음악들을래" -> return NormalCommand.Select(DrivingMode.MUSIC)
            "말동무", "말동무해줘", "대화하고싶어" -> return NormalCommand.Select(DrivingMode.COMPANION)
            "노래꺼줘", "음악꺼줘", "음악중단", "재생중단", "그만틀어", "노래그만", "음악멈춰" -> return NormalCommand.Stop
            "다음곡", "다음노래", "다음곡틀어줘" -> return NormalCommand.Next
        }
        val query = Regex("^(.{1,40}?)\\s*(?:틀어줘|재생해줘)$").matchEntire(raw)?.groupValues?.get(1)?.trim()
        return if (query != null && safeQuery(query)) NormalCommand.Select(DrivingMode.MUSIC, query) else NormalCommand.Unknown
    }

    fun preference(text: String): MusicPreference? {
        val key = compact(text).trimEnd('.', '!', '。', '！')
        val genre = genres.firstOrNull { key in setOf(it, "${it}좋아해", "${it}좋아해요", "${it}좋아합니다") }
        if (genre != null) return MusicPreference(genre, genre = genre)
        val named = Regex("^(가수|플레이리스트)(.{1,30}?)좋아해(?:요)?$").matchEntire(key) ?: return null
        val name = named.groupValues[2]
        if (!safeQuery(name)) return null
        return if (named.groupValues[1] == "가수") MusicPreference("$name 노래", artist = name)
            else MusicPreference("$name 플레이리스트", playlist = name)
    }

    fun affirmative(text: String) = compact(text).trimEnd('.', '!') in setOf("응", "네", "예", "좋아", "동의해", "그렇게해줘")
    private fun compact(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), "")
    private fun safeQuery(text: String): Boolean =
        Regex("[가-힣a-zA-Z0-9 ]{1,40}").matches(text) &&
            listOf("말고", "아니", "안", "않", "못", "싫", "말아", "꺼", "다음", "뉴스", "조용", "말동무", "오늘은", "틀지", "재생하지").none { it in compact(text) }
    private val genres = listOf("발라드", "댄스", "재즈", "클래식", "힙합", "록", "팝", "트로트")
}

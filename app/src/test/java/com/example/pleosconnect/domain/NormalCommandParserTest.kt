package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.model.DrivingMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class NormalCommandParserTest(private val text: String, private val expected: NormalCommand) {
    @Test fun interpretsWholeCommand() = assertEquals(expected, NormalCommandParser().parse(text))
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}: {0}")
        fun cases(): List<Array<Any>> = buildList {
            fun examples(command: NormalCommand, vararg inputs: String) { inputs.forEach { add(arrayOf(it, command)) } }
            examples(NormalCommand.Select(DrivingMode.QUIET), "오늘은 조용히", "조용히", "조용한 운전",
                "아니, 오늘은 조용하게 운전할래", "아니요 조용히", "음악 말고 조용히")
            examples(NormalCommand.Select(DrivingMode.NEWS_AUDIO), "음악 말고 뉴스", "오늘은 음악 말고 뉴스 들을래",
                "뉴스 틀어줘", "노래 말고 뉴스", "뉴스!")
            examples(NormalCommand.Select(DrivingMode.MUSIC), "음악 틀어줘", "노래틀어줘", "음악")
            examples(NormalCommand.Select(DrivingMode.COMPANION), "말동무 해줘", "말동무", "대화하고 싶어")
            examples(NormalCommand.Stop, "노래 꺼줘", "음악 중단", "음악꺼줘", "재생 중단")
            examples(NormalCommand.Next, "다음 곡", "다음곡틀어줘", "다음 노래")
            examples(NormalCommand.Select(DrivingMode.MUSIC, "발라드"), "발라드 틀어줘", "발라드 재생해줘")
            examples(NormalCommand.Select(DrivingMode.MUSIC, "신나는 음악"), "신나는 음악 틀어줘")
            examples(NormalCommand.Select(DrivingMode.MUSIC, "아이유 노래"), "아이유 노래 틀어줘")
            examples(NormalCommand.Unknown, "", "네", "아니", "모르겠어", "뉴스 틀어줘?", "음악 안 틀어줘",
                "음악 틀지 말고 뉴스 틀어줘", "음악 틀어주지 마", "음악하고 뉴스 틀어줘", "조용히 하지만 음악 틀어줘",
                "발라드 싫어 틀어줘", "다음 곡 말고", "음악 말고", "안 틀어줘", "뉴스 말고 음악", "발라드 좋아해")
        }
    }
}

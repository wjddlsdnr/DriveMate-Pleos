package com.example.pleosconnect.voice

import java.text.Normalizer

/** Fixed test metadata only. Never returns or retains the supplied transcript. */
internal enum class TestPhraseMatch {
    EXACT, NFC_WHITESPACE_PUNCTUATION, UNICODE_SPACE, FORMAT_CHARACTER, COMPATIBILITY, DIFFERENT;
}

/** Diagnostic transformations do not change the answer delivered to the classifier. */
internal fun testPhraseMatch(text: String): TestPhraseMatch {
    val expected = "괜찮아요"
    if (text == expected) return TestPhraseMatch.EXACT
    fun basic(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)
        .replace(Regex("\\s+"), "").trimEnd('.', '!', '。', '！')
    if (basic(text) == expected) return TestPhraseMatch.NFC_WHITESPACE_PUNCTUATION
    val spaces = text.filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
    if (basic(spaces) == expected) return TestPhraseMatch.UNICODE_SPACE
    val formats = spaces.filterNot { Character.getType(it) == Character.FORMAT.toInt() }
    if (basic(formats) == expected) return TestPhraseMatch.FORMAT_CHARACTER
    if (basic(Normalizer.normalize(formats, Normalizer.Form.NFKC)) == expected)
        return TestPhraseMatch.COMPATIBILITY
    return TestPhraseMatch.DIFFERENT
}

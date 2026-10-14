package io.anonero.ui.onboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreSeedWordsValidatorTest {
    private val polyseedWords = setOf("abandon", "ability", "able")
    private val moneroWords = setOf("abbey", "abducts", "ability")

    @Test
    fun validates16WordPhraseAgainstPolyseedVocabulary() {
        val phrase = List(16) { "abandon" }.joinToString(" ")
        val result = validateRestoreSeedWords(phrase, polyseedWords, moneroWords)

        assertFalse(result.invalid)
        assertEquals(16, result.words.size)
    }

    @Test
    fun normalizesUppercaseWordsBeforeValidation() {
        val phrase = List(16) { "ABANDON" }.joinToString(" ")
        val result = validateRestoreSeedWords(phrase, polyseedWords, moneroWords)

        assertFalse(result.invalid)
        assertEquals(List(16) { "abandon" }, result.words)
    }

    @Test
    fun validates25WordPhraseAgainstMoneroVocabulary() {
        val phrase = List(25) { "abbey" }.joinToString(" ")
        val result = validateRestoreSeedWords(phrase, polyseedWords, moneroWords)

        assertFalse(result.invalid)
        assertEquals(25, result.words.size)
    }

    @Test
    fun doesNotAcceptBip39OnlyWordsInMonero25WordPhrase() {
        val phrase = List(25) { "abandon" }.joinToString(" ")
        val result = validateRestoreSeedWords(phrase, polyseedWords, moneroWords)

        assertTrue(result.invalid)
        assertTrue(result.words.isEmpty())
    }

    @Test
    fun rejectsUnsupportedWordCount() {
        val phrase = List(17) { "abandon" }.joinToString(" ")
        val result = validateRestoreSeedWords(phrase, polyseedWords, moneroWords)

        assertTrue(result.invalid)
        assertTrue(result.words.isEmpty())
    }
}

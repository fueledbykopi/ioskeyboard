package com.ikeys.keyboard

import com.ikeys.keyboard.prediction.SuggestionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {

    private val engine = SuggestionEngine()

    @Test
    fun returnsEnglishAndIndonesianPrefixSuggestions() {
        val en = engine.suggest("hel")
        assertTrue(en.any { it.text.equals("hello", ignoreCase = true) })

        val id = engine.suggest("selam")
        assertTrue(id.any { it.text.equals("selamat", ignoreCase = true) })
    }

    @Test
    fun preservesTypedCapitalization() {
        val titleCase = engine.suggest("Selam")
        assertTrue(titleCase.any { it.text == "Selamat" })

        val upperCase = engine.suggest("SELAM")
        assertTrue(upperCase.any { it.text == "SELAMAT" })
    }

    @Test
    fun autocorrectsCommonEnglishContractionsAndIndonesianShorthands() {
        assertEquals("don't", engine.autoCorrectFor("dont"))
        assertEquals("I'm", engine.autoCorrectFor("im"))
        assertEquals("tomorrow", engine.autoCorrectFor("tmrw"))
        assertEquals("yang", engine.autoCorrectFor("yg"))
        assertEquals("sudah", engine.autoCorrectFor("sdh"))
        assertEquals("makan", engine.autoCorrectFor("mkn"))
    }

    @Test
    fun recoversFromTranspositionExtraLetterAndMissingLetterTypos() {
        val transposed = engine.autoCorrectFor("hte")
        assertNotNull(transposed)
        assertEquals("the", transposed)

        val extraChar = engine.autoCorrectFor("helllo")
        assertNotNull(extraChar)
        assertEquals("hello", extraChar)

        val missingChar = engine.autoCorrectFor("hllo")
        assertNotNull(missingChar)
        assertEquals("hello", missingChar)
    }

    @Test
    fun doesNotAggressivelyAutoCorrectValidPartialPrefixes() {
        assertNull(engine.autoCorrectFor("hel"))
    }

    @Test
    fun predictsContextualNextWordsUsingBigramsAndTrigrams() {
        val afterGood = engine.suggest(currentWord = "", previousWord = "Good")
        assertTrue(afterGood.any { it.text.equals("morning", ignoreCase = true) })

        val afterTerima = engine.suggest(currentWord = "", previousWord = "terima")
        assertTrue(afterTerima.any { it.text.equals("kasih", ignoreCase = true) })

        val afterHowAre = engine.suggest(currentWord = "", previousWord = "are", prevPreviousWord = "How")
        assertTrue(afterHowAre.any { it.text.equals("you", ignoreCase = true) })

        val afterApaKabar = engine.suggest(currentWord = "", previousWord = "kabar", prevPreviousWord = "apa")
        assertTrue(afterApaKabar.any { it.text.equals("hari", ignoreCase = true) })
    }

    @Test
    fun suggestsContextualEmojisForCompletedKeywords() {
        val loveSuggestions = engine.suggest("love")
        assertTrue(loveSuggestions.any { it.text == "❤️" })

        val coffeeSuggestions = engine.suggest("kopi")
        assertTrue(coffeeSuggestions.any { it.text == "☕" })
    }

    @Test
    fun autoDetectsLanguageFromPreviousWordContext() {
        val afterSaya = engine.suggest(currentWord = "m", previousWord = "saya")
        assertTrue(afterSaya.any { it.text.equals("mau", ignoreCase = true) || it.text.equals("makan", ignoreCase = true) })
    }

    @Test
    fun learnsCustomUserWordsTransitionsAndPersistsCsv() {
        engine.learnWord("Antigravity", boost = 500)
        engine.learnTransition("hello", "Antigravity", boost = 200)

        val results = engine.suggest("Antig")
        assertTrue(results.any { it.text.equals("Antigravity", ignoreCase = true) })

        val next = engine.suggest(currentWord = "", previousWord = "hello")
        assertTrue(next.any { it.text == "Antigravity" })

        val csv = engine.exportUserWordsCsv()
        assertTrue(csv.contains("Antigravity"))

        val fresh = SuggestionEngine()
        fresh.loadUserWordsCsv(csv)
        assertTrue(fresh.suggest("Antig").any { it.text.equals("Antigravity", ignoreCase = true) })
    }
}

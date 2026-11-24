package com.ikeys.keyboard

import com.ikeys.keyboard.prediction.SuggestionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals("yang", engine.autoCorrectFor("yg"))
        assertEquals("sudah", engine.autoCorrectFor("sdh"))
    }

    @Test
    fun recoversFromAdjacentTranspositionAndExtraLetterTypos() {
        val transposed = engine.autoCorrectFor("hte")
        assertNotNull(transposed)
        assertEquals("the", transposed)

        val extraChar = engine.autoCorrectFor("helllo")
        assertNotNull(extraChar)
        assertEquals("hello", extraChar)
    }

    @Test
    fun learnsCustomUserWordsDynamically() {
        engine.learnWord("Antigravity", boost = 500)
        val results = engine.suggest("Antig")
        assertTrue(results.any { it.text.equals("Antigravity", ignoreCase = true) })
    }
}

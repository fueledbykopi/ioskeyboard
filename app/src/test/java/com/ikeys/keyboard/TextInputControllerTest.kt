package com.ikeys.keyboard

import com.ikeys.keyboard.ime.TextInputController
import com.ikeys.keyboard.layout.KeyboardMode
import com.ikeys.keyboard.layout.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInputControllerTest {

    @Test
    fun shiftSingleTapDoubleTapCapsLockAndAutoUnshift() {
        val ctrl = TextInputController()
        ctrl.resetForNewInput("hello", autoCapEnabled = true, fieldSupportsAutoCap = true)
        assertEquals(ShiftState.OFF, ctrl.shiftState)

        // Single tap -> SHIFTED
        assertEquals(ShiftState.SHIFTED, ctrl.onShiftTap(1000L))
        // Typing a letter auto-unshifts
        ctrl.onCharacterCommitted("H")
        assertEquals(ShiftState.OFF, ctrl.shiftState)

        // Double tap within 320ms -> CAPS_LOCK
        assertEquals(ShiftState.SHIFTED, ctrl.onShiftTap(2000L))
        assertEquals(ShiftState.CAPS_LOCK, ctrl.onShiftTap(2180L))
        // Typing in CAPS_LOCK stays locked
        ctrl.onCharacterCommitted("A")
        assertEquals(ShiftState.CAPS_LOCK, ctrl.shiftState)

        // Tap again turns off CAPS_LOCK
        assertEquals(ShiftState.OFF, ctrl.onShiftTap(2600L))
    }

    @Test
    fun autoCapitalizationEvaluatesSentenceBoundariesAccurately() {
        val ctrl = TextInputController()
        assertTrue(ctrl.shouldAutoCapitalize(""))
        assertTrue(ctrl.shouldAutoCapitalize("Hello. "))
        assertTrue(ctrl.shouldAutoCapitalize("Really? "))
        assertTrue(ctrl.shouldAutoCapitalize("Wow! "))
        assertTrue(ctrl.shouldAutoCapitalize("Line one\n"))

        assertFalse(ctrl.shouldAutoCapitalize("Hello"))
        assertFalse(ctrl.shouldAutoCapitalize("Hello."))
        assertFalse(ctrl.shouldAutoCapitalize("Hello, "))
    }

    @Test
    fun doubleSpaceInsertsPeriodAndReturnsFromSymbolsToAlpha() {
        val ctrl = TextInputController()
        ctrl.resetForNewInput("hello", autoCapEnabled = true, fieldSupportsAutoCap = true)

        val firstSpace = ctrl.onSpaceTap(nowMs = 1000L, textBeforeCursor = "hello", doubleSpacePeriodEnabled = true)
        assertFalse(firstSpace.shouldInsertPeriodInstead)

        val secondSpace = ctrl.onSpaceTap(nowMs = 1200L, textBeforeCursor = "hello ", doubleSpacePeriodEnabled = true)
        assertTrue(secondSpace.shouldInsertPeriodInstead)

        // Test symbol mode auto-return on space
        ctrl.setMode(KeyboardMode.SYMBOLS)
        ctrl.onCharacterCommitted("!")
        val symSpace = ctrl.onSpaceTap(nowMs = 2000L, textBeforeCursor = "hello!", doubleSpacePeriodEnabled = true)
        assertTrue(symSpace.shouldReturnToAlpha)
        assertEquals(KeyboardMode.ALPHA, ctrl.keyboardMode)
    }

    @Test
    fun backspaceDeletesSurrogatePairEmojisAndWholeWordsCleanly() {
        // Basic ASCII
        assertEquals(1, TextInputController.computeSingleBackspaceCount("abc"))
        // Surrogate-pair emoji (😀 = 2 UTF-16 code units)
        assertEquals(2, TextInputController.computeSingleBackspaceCount("Hi 😀"))
        // Heart with variation selector (❤️ = U+2764 + U+FE0F = 2 code units)
        assertEquals(2, TextInputController.computeSingleBackspaceCount("Love ❤️"))
        // Word deletion including trailing spaces
        assertEquals(8, TextInputController.computeWordBackspaceCount("hello world   "))
        assertEquals(5, TextInputController.computeWordBackspaceCount("hello"))
    }

    @Test
    fun extractsCurrentAndPreviousWordsForNextWordPredictions() {
        val ctrl = TextInputController()
        assertEquals("morn", ctrl.extractCurrentWord("Good morn"))
        assertEquals("Good", ctrl.extractPreviousWord("Good morn"))
        assertEquals("", ctrl.extractCurrentWord("Good "))
        assertEquals("Good", ctrl.extractPreviousWord("Good "))
        assertEquals("", ctrl.extractPreviousWord("Good. "))
        assertEquals("terima", ctrl.extractPreviousWord("terima "))
    }
}

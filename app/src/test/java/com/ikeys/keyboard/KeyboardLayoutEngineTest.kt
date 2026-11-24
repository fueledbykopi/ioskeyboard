package com.ikeys.keyboard

import com.ikeys.keyboard.layout.IOSKeyboardLayout
import com.ikeys.keyboard.layout.InputVariant
import com.ikeys.keyboard.layout.KeyCode
import com.ikeys.keyboard.layout.KeyboardLayoutEngine
import com.ikeys.keyboard.layout.KeyboardMode
import com.ikeys.keyboard.layout.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutEngineTest {

    private val engine = KeyboardLayoutEngine()
    private val defaultMetrics = KeyboardLayoutEngine.Metrics(
        width = 1080f,
        topOffset = 120f,
        rowHeight = 132f,
        horizontalPadding = 9f,
        keyHorizontalGap = 16f,
        rowVerticalGap = 32f
    )

    @Test
    fun alphaLayoutMatchesAuthenticIOSRowStructure() {
        val rows = IOSKeyboardLayout.rowsFor(KeyboardMode.ALPHA, InputVariant.NORMAL)
        assertEquals(4, rows.size)
        assertEquals(10, rows[0].size) // QWERTYUIOP
        assertEquals(9, rows[1].size)  // ASDFGHJKL (centered)
        assertEquals(9, rows[2].size)  // Shift + ZXCVBNM + Backspace
        assertEquals(4, rows[3].size)  // 123 + Emoji + Space + Return
    }

    @Test
    fun emailAndUriVariantsExposeQuickKeysOnBottomRow() {
        val emailBottom = IOSKeyboardLayout.rowsFor(KeyboardMode.ALPHA, InputVariant.EMAIL)[3]
        val uriBottom = IOSKeyboardLayout.rowsFor(KeyboardMode.ALPHA, InputVariant.URI)[3]

        assertTrue(emailBottom.any { it.label == "@" })
        assertTrue(emailBottom.any { it.label == "." })
        assertTrue(uriBottom.any { it.label == "." })
        assertTrue(uriBottom.any { it.label == "/" })
    }

    @Test
    fun secondRowIsSymmetricallyCentered() {
        val keys = engine.computeKeys(IOSKeyboardLayout.rowsFor(KeyboardMode.ALPHA), defaultMetrics)
        val keyA = keys.first { it.spec.label == "a" }
        val keyL = keys.first { it.spec.label == "l" }

        val leftMargin = keyA.drawRect.left
        val rightMargin = defaultMetrics.width - keyL.drawRect.right
        assertEquals(leftMargin, rightMargin, 0.5f)
        assertTrue("Row 2 should be inset more than Row 1", leftMargin > 40f)
    }

    @Test
    fun hitTestingHasZeroDeadZonesAcrossGridAndGaps() {
        val keys = engine.computeKeys(IOSKeyboardLayout.rowsFor(KeyboardMode.ALPHA), defaultMetrics)
        for (x in 0..1080 step 27) {
            for (y in 120..740 step 25) {
                val hit = engine.findKeyAt(keys, x.toFloat(), y.toFloat())
                assertNotNull("Touch ($x, $y) should always resolve to a key", hit)
            }
        }
    }

    @Test
    fun symbolsAndExtendedSymbolsSwitchCodesCorrectly() {
        val symRows = IOSKeyboardLayout.rowsFor(KeyboardMode.SYMBOLS)
        val moreRows = IOSKeyboardLayout.rowsFor(KeyboardMode.MORE_SYMBOLS)

        assertEquals(KeyCode.MORE_SYMBOLS, symRows[2].first().code)
        assertEquals(KeyCode.ALPHA, symRows[3].first().code)
        assertEquals(KeyCode.SYMBOLS, moreRows[2].first().code)
        assertEquals(KeyCode.ALPHA, moreRows[3].first().code)
    }

    @Test
    fun vowelKeysProvideUpperAndLowercaseDiacritics() {
        val keys = engine.computeKeys(IOSKeyboardLayout.rowsFor(KeyboardMode.ALPHA), defaultMetrics)
        val keyA = keys.first { it.spec.label == "a" }

        val lowerAlts = keyA.alternatesFor(ShiftState.OFF)
        val upperAlts = keyA.alternatesFor(ShiftState.SHIFTED)

        assertTrue(lowerAlts.contains("á"))
        assertTrue(upperAlts.contains("Á"))
    }
}

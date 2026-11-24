package com.ikeys.keyboard.ime

import com.ikeys.keyboard.layout.KeyboardMode
import com.ikeys.keyboard.layout.ShiftState

/**
 * Pure Kotlin state machine for authentic iOS typing behaviors:
 * - Shift single-tap, double-tap Caps Lock, auto-unshift, and sentence auto-capitalization
 * - Double-space to period (". ")
 * - Auto-return from Symbols (123) to Alpha (ABC) after space following punctuation
 * - Safe UTF-16 surrogate pair / emoji-cluster & whole-word backspace length computation
 */
class TextInputController {

    var keyboardMode: KeyboardMode = KeyboardMode.ALPHA
        private set

    var shiftState: ShiftState = ShiftState.OFF
        private set

    private var lastShiftTapTimeMs: Long = 0L
    private var lastSpaceTapTimeMs: Long = 0L
    private var typedCharInSymbolMode: Boolean = false

    fun resetForNewInput(
        textBeforeCursor: CharSequence?,
        autoCapEnabled: Boolean,
        fieldSupportsAutoCap: Boolean
    ) {
        keyboardMode = KeyboardMode.ALPHA
        typedCharInSymbolMode = false
        lastShiftTapTimeMs = 0L
        lastSpaceTapTimeMs = 0L
        shiftState = if (autoCapEnabled && fieldSupportsAutoCap && shouldAutoCapitalize(textBeforeCursor)) {
            ShiftState.SHIFTED
        } else {
            ShiftState.OFF
        }
    }

    fun setMode(mode: KeyboardMode) {
        keyboardMode = mode
        if (mode == KeyboardMode.ALPHA) {
            typedCharInSymbolMode = false
        }
    }

    /**
     * Handles iOS Shift tap semantics:
     * - OFF -> SHIFTED
     * - SHIFTED within 320ms of previous tap -> CAPS_LOCK
     * - SHIFTED after 320ms -> OFF
     * - CAPS_LOCK -> OFF
     */
    fun onShiftTap(nowMs: Long): ShiftState {
        val elapsed = nowMs - lastShiftTapTimeMs
        shiftState = when (shiftState) {
            ShiftState.OFF -> ShiftState.SHIFTED
            ShiftState.SHIFTED -> if (elapsed in 1..DOUBLE_TAP_WINDOW_MS) {
                ShiftState.CAPS_LOCK
            } else {
                ShiftState.OFF
            }
            ShiftState.CAPS_LOCK -> ShiftState.OFF
        }
        lastShiftTapTimeMs = nowMs
        return shiftState
    }

    /**
     * Called after committing a character. Unshifts single-shot shift and tracks symbol usage.
     */
    fun onCharacterCommitted(committed: String) {
        lastSpaceTapTimeMs = 0L
        if (keyboardMode == KeyboardMode.SYMBOLS || keyboardMode == KeyboardMode.MORE_SYMBOLS) {
            typedCharInSymbolMode = true
        }
        if (shiftState == ShiftState.SHIFTED && committed.any { it.isLetter() }) {
            shiftState = ShiftState.OFF
        }
    }

    /**
     * Evaluates iOS double-space-to-period and auto-return from symbols on spacebar tap.
     */
    data class SpaceAction(
        val shouldInsertPeriodInstead: Boolean,
        val shouldReturnToAlpha: Boolean
    )

    fun onSpaceTap(
        nowMs: Long,
        textBeforeCursor: CharSequence?,
        doubleSpacePeriodEnabled: Boolean
    ): SpaceAction {
        val elapsed = nowMs - lastSpaceTapTimeMs
        val canPeriod = doubleSpacePeriodEnabled &&
            elapsed in 1..DOUBLE_SPACE_WINDOW_MS &&
            endsWithEligibleCharPlusSpace(textBeforeCursor)

        lastSpaceTapTimeMs = if (canPeriod) 0L else nowMs

        val returnToAlpha = (keyboardMode == KeyboardMode.SYMBOLS || keyboardMode == KeyboardMode.MORE_SYMBOLS) &&
            typedCharInSymbolMode
        if (returnToAlpha) {
            keyboardMode = KeyboardMode.ALPHA
            typedCharInSymbolMode = false
        }

        return SpaceAction(
            shouldInsertPeriodInstead = canPeriod,
            shouldReturnToAlpha = returnToAlpha
        )
    }

    fun updateAutoShift(
        textBeforeCursor: CharSequence?,
        autoCapEnabled: Boolean,
        fieldSupportsAutoCap: Boolean
    ) {
        if (shiftState == ShiftState.CAPS_LOCK) return
        if (keyboardMode != KeyboardMode.ALPHA) return

        shiftState = if (autoCapEnabled && fieldSupportsAutoCap && shouldAutoCapitalize(textBeforeCursor)) {
            ShiftState.SHIFTED
        } else {
            ShiftState.OFF
        }
    }

    /**
     * Extracts the active word currently being typed immediately before the cursor.
     */
    fun extractCurrentWord(textBeforeCursor: CharSequence?): String {
        if (textBeforeCursor.isNullOrEmpty()) return ""
        var i = textBeforeCursor.length - 1
        while (i >= 0) {
            val ch = textBeforeCursor[i]
            if (ch.isLetter() || ch == '\'') {
                i--
            } else {
                break
            }
        }
        return textBeforeCursor.substring(i + 1)
    }

    /**
     * Returns true if the cursor is at the start of text, after newline, or after sentence-ending punctuation + space.
     */
    fun shouldAutoCapitalize(textBeforeCursor: CharSequence?): Boolean {
        if (textBeforeCursor.isNullOrEmpty()) return true
        val str = textBeforeCursor.toString()
        if (str.endsWith("\n")) return true

        // Walk backward over trailing spaces (must have at least one space after . ? !)
        var idx = str.length - 1
        var sawSpace = false
        while (idx >= 0 && str[idx] == ' ') {
            sawSpace = true
            idx--
        }
        if (idx < 0) return true
        if (!sawSpace) return false

        val lastNonSpace = str[idx]
        return lastNonSpace == '.' || lastNonSpace == '?' || lastNonSpace == '!'
    }

    private fun endsWithEligibleCharPlusSpace(textBeforeCursor: CharSequence?): Boolean {
        if (textBeforeCursor == null || textBeforeCursor.length < 2) return false
        val last = textBeforeCursor[textBeforeCursor.length - 1]
        val prev = textBeforeCursor[textBeforeCursor.length - 2]
        return last == ' ' && (prev.isLetterOrDigit() || prev == ')' || prev == '"')
    }

    companion object {
        const val DOUBLE_TAP_WINDOW_MS = 320L
        const val DOUBLE_SPACE_WINDOW_MS = 340L

        /**
         * Computes how many UTF-16 code units to delete so emojis / surrogate pairs / ZWJ sequences
         * are deleted cleanly in a single backspace tap without leaving broken orphan surrogates.
         */
        fun computeSingleBackspaceCount(textBeforeCursor: CharSequence?): Int {
            if (textBeforeCursor.isNullOrEmpty()) return 1
            val len = textBeforeCursor.length
            var idx = len - 1
            var count = 0

            // Handle variation selector (FE0F)
            if (idx >= 0 && textBeforeCursor[idx] == '\uFE0F') {
                count++
                idx--
            }

            // Delete primary code point (surrogate pair or BMP char)
            if (idx >= 1 && Character.isLowSurrogate(textBeforeCursor[idx]) &&
                Character.isHighSurrogate(textBeforeCursor[idx - 1])
            ) {
                count += 2
                idx -= 2
            } else if (idx >= 0) {
                count += 1
                idx -= 1
            }

            // If joined by ZWJ (200D) to a preceding emoji, delete the entire ZWJ cluster
            while (idx >= 1 && textBeforeCursor[idx] == '\u200D') {
                count += 1 // ZWJ
                idx -= 1
                if (idx >= 0 && textBeforeCursor[idx] == '\uFE0F') {
                    count++
                    idx--
                }
                if (idx >= 1 && Character.isLowSurrogate(textBeforeCursor[idx]) &&
                    Character.isHighSurrogate(textBeforeCursor[idx - 1])
                ) {
                    count += 2
                    idx -= 2
                } else if (idx >= 0) {
                    count += 1
                    idx -= 1
                }
            }

            return count.coerceAtLeast(1)
        }

        /**
         * Computes how many characters to delete when accelerated backspace deletes a whole word.
         */
        fun computeWordBackspaceCount(textBeforeCursor: CharSequence?): Int {
            if (textBeforeCursor.isNullOrEmpty()) return 1
            var idx = textBeforeCursor.length - 1
            // Skip trailing spaces first
            while (idx >= 0 && textBeforeCursor[idx] == ' ') {
                idx--
            }
            if (idx < 0) return textBeforeCursor.length
            // Stop at newline if immediately encountered
            if (textBeforeCursor[idx] == '\n') {
                return textBeforeCursor.length - idx
            }
            // Walk back across word characters
            while (idx >= 0 && !textBeforeCursor[idx].isWhitespace()) {
                idx--
            }
            return (textBeforeCursor.length - (idx + 1)).coerceAtLeast(1)
        }
    }
}

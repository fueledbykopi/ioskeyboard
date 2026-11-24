package com.ikeys.keyboard.ui

import android.content.Context
import android.content.res.Configuration
import com.ikeys.keyboard.prefs.ThemeMode

/**
 * Exact iOS 18+ Light & Dark keyboard color palettes.
 * Colors are pre-packed ARGB integers for zero-allocation Canvas drawing.
 */
data class IOSKeyboardTheme(
    val isDark: Boolean,
    val keyboardBg: Int,
    val topBorderColor: Int,
    val suggestionBarBg: Int,
    val suggestionDivider: Int,
    val suggestionText: Int,
    val suggestionHighlightBg: Int,
    val keyCharBg: Int,
    val keyCharBgPressed: Int,
    val keyCharShadow: Int,
    val keyCharText: Int,
    val keySpecialBg: Int,
    val keySpecialBgPressed: Int,
    val keySpecialShadow: Int,
    val keySpecialText: Int,
    val keyShiftActiveBg: Int,
    val keyShiftActiveIcon: Int,
    val keyActionBlueBg: Int,
    val keyActionBlueBgPressed: Int,
    val keyActionBlueShadow: Int,
    val keyActionBlueText: Int,
    val popupBg: Int,
    val popupBorder: Int,
    val popupShadow: Int,
    val popupText: Int,
    val popupPillSelectedBg: Int,
    val popupPillSelectedText: Int,
    val trackpadKeyAlpha: Int,
    val chinIconColor: Int,
    val emojiTabActiveBg: Int,
    val emojiTabIconColor: Int,
    val emojiTabActiveIconColor: Int
) {
    companion object {
        val Light = IOSKeyboardTheme(
            isDark = false,
            keyboardBg = 0xFFD1D5DC.toInt(),
            topBorderColor = 0xFFBCC1CA.toInt(),
            suggestionBarBg = 0xFFD1D5DC.toInt(),
            suggestionDivider = 0xFFB8BDC7.toInt(),
            suggestionText = 0xFF000000.toInt(),
            suggestionHighlightBg = 0xFFE5E8EE.toInt(),
            keyCharBg = 0xFFFFFFFF.toInt(),
            keyCharBgPressed = 0xFFE3E6EC.toInt(),
            keyCharShadow = 0xFF898B90.toInt(),
            keyCharText = 0xFF000000.toInt(),
            keySpecialBg = 0xFFACB3BF.toInt(),
            keySpecialBgPressed = 0xFFFFFFFF.toInt(),
            keySpecialShadow = 0xFF83878E.toInt(),
            keySpecialText = 0xFF000000.toInt(),
            keyShiftActiveBg = 0xFFFFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueBg = 0xFF007AFF.toInt(),
            keyActionBlueBgPressed = 0xFF0062CC.toInt(),
            keyActionBlueShadow = 0xFF0051A8.toInt(),
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBg = 0xFFFFFFFF.toInt(),
            popupBorder = 0x26000000,
            popupShadow = 0x3D000000,
            popupText = 0xFF000000.toInt(),
            popupPillSelectedBg = 0xFF007AFF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 72,
            chinIconColor = 0xFF4F5560.toInt(),
            emojiTabActiveBg = 0xFFB8BFC9.toInt(),
            emojiTabIconColor = 0xFF6B7280.toInt(),
            emojiTabActiveIconColor = 0xFF111827.toInt()
        )

        val Dark = IOSKeyboardTheme(
            isDark = true,
            keyboardBg = 0xFF1C1C1E.toInt(),
            topBorderColor = 0xFF2C2C2E.toInt(),
            suggestionBarBg = 0xFF1C1C1E.toInt(),
            suggestionDivider = 0xFF3A3A3C.toInt(),
            suggestionText = 0xFFFFFFFF.toInt(),
            suggestionHighlightBg = 0xFF333336.toInt(),
            keyCharBg = 0xFF535356.toInt(),
            keyCharBgPressed = 0xFF6E6E72.toInt(),
            keyCharShadow = 0xFF0D0D0E.toInt(),
            keyCharText = 0xFFFFFFFF.toInt(),
            keySpecialBg = 0xFF363638.toInt(),
            keySpecialBgPressed = 0xFF5A5A5E.toInt(),
            keySpecialShadow = 0xFF0D0D0E.toInt(),
            keySpecialText = 0xFFFFFFFF.toInt(),
            keyShiftActiveBg = 0xFFE5E5EA.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueBg = 0xFF0A84FF.toInt(),
            keyActionBlueBgPressed = 0xFF0066D6.toInt(),
            keyActionBlueShadow = 0xFF004C9E.toInt(),
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBg = 0xFF636366.toInt(),
            popupBorder = 0x33FFFFFF,
            popupShadow = 0x66000000,
            popupText = 0xFFFFFFFF.toInt(),
            popupPillSelectedBg = 0xFF0A84FF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 60,
            chinIconColor = 0xFFAEAEB2.toInt(),
            emojiTabActiveBg = 0xFF3A3A3C.toInt(),
            emojiTabIconColor = 0xFF8E8E93.toInt(),
            emojiTabActiveIconColor = 0xFFFFFFFF.toInt()
        )

        fun resolve(context: Context, mode: ThemeMode): IOSKeyboardTheme {
            return when (mode) {
                ThemeMode.LIGHT -> Light
                ThemeMode.DARK -> Dark
                ThemeMode.AUTO -> {
                    val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    if (nightMask == Configuration.UI_MODE_NIGHT_YES) Dark else Light
                }
            }
        }
    }
}

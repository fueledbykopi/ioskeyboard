package com.ikeys.keyboard.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.ikeys.keyboard.prefs.BlurIntensity
import com.ikeys.keyboard.prefs.KeyboardStyle
import com.ikeys.keyboard.prefs.ThemeMode

/**
 * Latest iOS 26 keyboard material & color system supporting:
 * - Liquid Glass (iOS 26 optical refractive glass, rounded floating tray, specular rims, unified key tones)
 * - Frosted Blur (iOS frosted translucent material blur)
 * - Classic Solid (high-contrast opaque iOS Light & Dark)
 *
 * All colors are pre-packed ARGB ints for zero-allocation Canvas drawing.
 */
data class IOSKeyboardTheme(
    val isDark: Boolean,
    val style: KeyboardStyle,
    val blurIntensity: BlurIntensity,
    val useWindowBlur: Boolean,
    val topCornerRadiusDp: Float,
    val keyCornerRadiusDp: Float,
    val actionCornerRadiusDp: Float,
    // Backdrop layers
    val keyboardBg: Int,             // Primary backdrop (translucent when blur is active)
    val keyboardBgFallback: Int,     // Higher-opacity fallback when system cross-window blur is unavailable
    val backdropGradientTop: Int,    // Subtle optical top sheen for Liquid Glass / Frosted
    val backdropGradientBottom: Int, // Subtle depth gradient at bottom
    val topBorderColor: Int,         // Specular glass rim or hairline
    val topSpecularHighlight: Int,   // Secondary inner top light reflection
    // Suggestion bar
    val suggestionBarBg: Int,
    val suggestionDivider: Int,
    val suggestionText: Int,
    val suggestionHighlightBg: Int,
    val suggestionHighlightBorder: Int,
    // Character keys
    val keyCharBg: Int,
    val keyCharBgTop: Int,           // Liquid Glass subtle top light tint
    val keyCharBgPressed: Int,
    val keyCharBorder: Int,          // Specular rim stroke around glass keys
    val keyCharTopRim: Int,          // Bright top edge refraction highlight
    val keyCharShadow: Int,
    val keyCharText: Int,
    // Special / modifier keys
    val keySpecialBg: Int,
    val keySpecialBgTop: Int,
    val keySpecialBgPressed: Int,
    val keySpecialBorder: Int,
    val keySpecialShadow: Int,
    val keySpecialText: Int,
    // Active shift key
    val keyShiftActiveBg: Int,
    val keyShiftActiveIcon: Int,
    // Primary blue action key (Search / Send / Go / Done)
    val keyActionBlueBg: Int,
    val keyActionBlueBgTop: Int,
    val keyActionBlueBgPressed: Int,
    val keyActionBlueBorder: Int,
    val keyActionBlueShadow: Int,
    val keyActionBlueText: Int,
    // Key preview teardrop & accent strip
    val popupBg: Int,
    val popupBgTop: Int,
    val popupBorder: Int,
    val popupShadow: Int,
    val popupText: Int,
    val popupPillSelectedBg: Int,
    val popupPillSelectedText: Int,
    // Trackpad, chin & emoji panel
    val trackpadKeyAlpha: Int,
    val chinIconColor: Int,
    val chinPressPillBg: Int,
    val emojiTabActiveBg: Int,
    val emojiTabIconColor: Int,
    val emojiTabActiveIconColor: Int
) {
    val isGlassStyle: Boolean
        get() = style == KeyboardStyle.LIQUID_GLASS

    val isTranslucentStyle: Boolean
        get() = style == KeyboardStyle.LIQUID_GLASS || style == KeyboardStyle.FROSTED_BLUR

    fun effectiveBackdropColor(hardwareBlurActive: Boolean): Int {
        if (!isTranslucentStyle) return keyboardBg
        val base = if (hardwareBlurActive) keyboardBg else keyboardBgFallback
        if (!hardwareBlurActive) return base
        val alpha = (Color.alpha(base) * blurIntensity.surfaceAlphaScale).toInt().coerceIn(72, 232)
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }

    companion object {

        // --- iOS 26 LIQUID GLASS ---

        val LiquidGlassLight = IOSKeyboardTheme(
            isDark = false,
            style = KeyboardStyle.LIQUID_GLASS,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 26f,
            keyCornerRadiusDp = 8.5f,
            actionCornerRadiusDp = 12f,
            keyboardBg = 0x9CE8EDF5.toInt(),
            keyboardBgFallback = 0xE6DDE3EE.toInt(),
            backdropGradientTop = 0x52FFFFFF,
            backdropGradientBottom = 0x18B6C2D6,
            topBorderColor = 0xC8FFFFFF.toInt(),
            topSpecularHighlight = 0x55FFFFFF,
            suggestionBarBg = 0x00000000,
            suggestionDivider = 0x336C7484,
            suggestionText = 0xFF0A0A0D.toInt(),
            suggestionHighlightBg = 0xB8FFFFFF.toInt(),
            suggestionHighlightBorder = 0x80FFFFFF.toInt(),
            keyCharBg = 0xD9FFFFFF.toInt(),
            keyCharBgTop = 0xF2FFFFFF.toInt(),
            keyCharBgPressed = 0xF5E5ECF8.toInt(),
            keyCharBorder = 0x99FFFFFF.toInt(),
            keyCharTopRim = 0xE6FFFFFF.toInt(),
            keyCharShadow = 0x302A3342,
            keyCharText = 0xFF08090C.toInt(),
            // Unified iOS 26 glass harmony: modifiers are subtly cooler translucent glass, not harsh flat gray
            keySpecialBg = 0xA8CDD5E4.toInt(),
            keySpecialBgTop = 0xC4DCE3F0.toInt(),
            keySpecialBgPressed = 0xE8FFFFFF.toInt(),
            keySpecialBorder = 0x85FFFFFF.toInt(),
            keySpecialShadow = 0x2B253042,
            keySpecialText = 0xFF0A0B0E.toInt(),
            keyShiftActiveBg = 0xF5FFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueBg = 0xE6007AFF.toInt(),
            keyActionBlueBgTop = 0xF53399FF.toInt(),
            keyActionBlueBgPressed = 0xF00062CC.toInt(),
            keyActionBlueBorder = 0x88B3DCFF.toInt(),
            keyActionBlueShadow = 0x450051A8,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBg = 0xF5FFFFFF.toInt(),
            popupBgTop = 0xFFFFFFFF.toInt(),
            popupBorder = 0x66FFFFFF,
            popupShadow = 0x381B2538,
            popupText = 0xFF08090C.toInt(),
            popupPillSelectedBg = 0xFF007AFF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 64,
            chinIconColor = 0xFF2E3440.toInt(),
            chinPressPillBg = 0x80FFFFFF.toInt(),
            emojiTabActiveBg = 0xB8FFFFFF.toInt(),
            emojiTabIconColor = 0xFF525A68.toInt(),
            emojiTabActiveIconColor = 0xFF0A0B0E.toInt()
        )

        val LiquidGlassDark = IOSKeyboardTheme(
            isDark = true,
            style = KeyboardStyle.LIQUID_GLASS,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 26f,
            keyCornerRadiusDp = 8.5f,
            actionCornerRadiusDp = 12f,
            keyboardBg = 0xA614161B.toInt(),
            keyboardBgFallback = 0xEB181A20.toInt(),
            backdropGradientTop = 0x22FFFFFF,
            backdropGradientBottom = 0x26050608,
            topBorderColor = 0x4DFFFFFF,
            topSpecularHighlight = 0x1FFFFFFF,
            suggestionBarBg = 0x00000000,
            suggestionDivider = 0x38FFFFFF,
            suggestionText = 0xFFF7F8FA.toInt(),
            suggestionHighlightBg = 0x3DFFFFFF,
            suggestionHighlightBorder = 0x40FFFFFF,
            keyCharBg = 0x47FFFFFF,
            keyCharBgTop = 0x5CFFFFFF,
            keyCharBgPressed = 0x7AFFFFFF,
            keyCharBorder = 0x38FFFFFF,
            keyCharTopRim = 0x62FFFFFF,
            keyCharShadow = 0x52000000,
            keyCharText = 0xFFFFFFFF.toInt(),
            // Unified iOS 26 dark glass tone: smoky cohesive glass for modifiers
            keySpecialBg = 0x2EFFFFFF,
            keySpecialBgTop = 0x3DFFFFFF,
            keySpecialBgPressed = 0x61FFFFFF,
            keySpecialBorder = 0x2EFFFFFF,
            keySpecialShadow = 0x52000000,
            keySpecialText = 0xFFF5F6FA.toInt(),
            keyShiftActiveBg = 0xEBFFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF0A0B0E.toInt(),
            keyActionBlueBg = 0xE60A84FF.toInt(),
            keyActionBlueBgTop = 0xF5409CFF.toInt(),
            keyActionBlueBgPressed = 0xF00066D6.toInt(),
            keyActionBlueBorder = 0x78A8D4FF,
            keyActionBlueShadow = 0x5C003D82,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBg = 0xEB363940.toInt(),
            popupBgTop = 0xF2474B54.toInt(),
            popupBorder = 0x52FFFFFF,
            popupShadow = 0x75000000,
            popupText = 0xFFFFFFFF.toInt(),
            popupPillSelectedBg = 0xFF0A84FF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 52,
            chinIconColor = 0xFFD8DAE0.toInt(),
            chinPressPillBg = 0x38FFFFFF,
            emojiTabActiveBg = 0x40FFFFFF,
            emojiTabIconColor = 0xFF989CA6.toInt(),
            emojiTabActiveIconColor = 0xFFFFFFFF.toInt()
        )

        // --- iOS FROSTED BLUR ---

        val FrostedBlurLight = IOSKeyboardTheme(
            isDark = false,
            style = KeyboardStyle.FROSTED_BLUR,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 22f,
            keyCornerRadiusDp = 6.5f,
            actionCornerRadiusDp = 7.5f,
            keyboardBg = 0xBFD3D8E0.toInt(),
            keyboardBgFallback = 0xF0D2D6DE.toInt(),
            backdropGradientTop = 0x28FFFFFF,
            backdropGradientBottom = 0x109BA3B0,
            topBorderColor = 0x66FFFFFF,
            topSpecularHighlight = 0x20FFFFFF,
            suggestionBarBg = 0x00000000,
            suggestionDivider = 0x458A909E,
            suggestionText = 0xFF000000.toInt(),
            suggestionHighlightBg = 0x9CE8ECF2.toInt(),
            suggestionHighlightBorder = 0x30FFFFFF,
            keyCharBg = 0xEBFFFFFF.toInt(),
            keyCharBgTop = 0xF2FFFFFF.toInt(),
            keyCharBgPressed = 0xE6DFE3EB.toInt(),
            keyCharBorder = 0x24FFFFFF,
            keyCharTopRim = 0x40FFFFFF,
            keyCharShadow = 0x557C8089,
            keyCharText = 0xFF000000.toInt(),
            keySpecialBg = 0xBDADB4C2.toInt(),
            keySpecialBgTop = 0xC4B4BBC8.toInt(),
            keySpecialBgPressed = 0xF2FFFFFF.toInt(),
            keySpecialBorder = 0x1FFFFFFF,
            keySpecialShadow = 0x52757982,
            keySpecialText = 0xFF000000.toInt(),
            keyShiftActiveBg = 0xF5FFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueBg = 0xF2007AFF.toInt(),
            keyActionBlueBgTop = 0xF51A88FF.toInt(),
            keyActionBlueBgPressed = 0xFF0062CC.toInt(),
            keyActionBlueBorder = 0x33FFFFFF,
            keyActionBlueShadow = 0x550051A8,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBg = 0xFAFFFFFF.toInt(),
            popupBgTop = 0xFFFFFFFF.toInt(),
            popupBorder = 0x24000000,
            popupShadow = 0x38000000,
            popupText = 0xFF000000.toInt(),
            popupPillSelectedBg = 0xFF007AFF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 70,
            chinIconColor = 0xFF454B55.toInt(),
            chinPressPillBg = 0x55FFFFFF,
            emojiTabActiveBg = 0xA8B5BCC9.toInt(),
            emojiTabIconColor = 0xFF636A76.toInt(),
            emojiTabActiveIconColor = 0xFF111827.toInt()
        )

        val FrostedBlurDark = IOSKeyboardTheme(
            isDark = true,
            style = KeyboardStyle.FROSTED_BLUR,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 22f,
            keyCornerRadiusDp = 6.5f,
            actionCornerRadiusDp = 7.5f,
            keyboardBg = 0xC21B1C1F.toInt(),
            keyboardBgFallback = 0xF21C1C1E.toInt(),
            backdropGradientTop = 0x14FFFFFF,
            backdropGradientBottom = 0x1A000000,
            topBorderColor = 0x30FFFFFF,
            topSpecularHighlight = 0x12FFFFFF,
            suggestionBarBg = 0x00000000,
            suggestionDivider = 0x388E8E93,
            suggestionText = 0xFFFFFFFF.toInt(),
            suggestionHighlightBg = 0x454A4A4F.toInt(),
            suggestionHighlightBorder = 0x22FFFFFF,
            keyCharBg = 0x8C5E5E63.toInt(),
            keyCharBgTop = 0x9465656A.toInt(),
            keyCharBgPressed = 0xB87A7A80.toInt(),
            keyCharBorder = 0x1AFFFFFF,
            keyCharTopRim = 0x2BFFFFFF,
            keyCharShadow = 0x6E000000,
            keyCharText = 0xFFFFFFFF.toInt(),
            keySpecialBg = 0x7A3D3D42.toInt(),
            keySpecialBgTop = 0x82444449.toInt(),
            keySpecialBgPressed = 0xA8636368.toInt(),
            keySpecialBorder = 0x16FFFFFF,
            keySpecialShadow = 0x6E000000,
            keySpecialText = 0xFFFFFFFF.toInt(),
            keyShiftActiveBg = 0xEBE5E5EA.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueBg = 0xF20A84FF.toInt(),
            keyActionBlueBgTop = 0xF52692FF.toInt(),
            keyActionBlueBgPressed = 0xFF0066D6.toInt(),
            keyActionBlueBorder = 0x30FFFFFF,
            keyActionBlueShadow = 0x66004C9E,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBg = 0xF5525256.toInt(),
            popupBgTop = 0xF85C5C60.toInt(),
            popupBorder = 0x33FFFFFF,
            popupShadow = 0x66000000,
            popupText = 0xFFFFFFFF.toInt(),
            popupPillSelectedBg = 0xFF0A84FF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 58,
            chinIconColor = 0xFFC5C5CA.toInt(),
            chinPressPillBg = 0x30FFFFFF,
            emojiTabActiveBg = 0x6648484D.toInt(),
            emojiTabIconColor = 0xFF8E8E93.toInt(),
            emojiTabActiveIconColor = 0xFFFFFFFF.toInt()
        )

        // --- CLASSIC SOLID iOS ---

        val ClassicLight = IOSKeyboardTheme(
            isDark = false,
            style = KeyboardStyle.CLASSIC,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = false,
            topCornerRadiusDp = 0f,
            keyCornerRadiusDp = 5.5f,
            actionCornerRadiusDp = 5.5f,
            keyboardBg = 0xFFD1D5DC.toInt(),
            keyboardBgFallback = 0xFFD1D5DC.toInt(),
            backdropGradientTop = 0x00000000,
            backdropGradientBottom = 0x00000000,
            topBorderColor = 0xFFBCC1CA.toInt(),
            topSpecularHighlight = 0x00000000,
            suggestionBarBg = 0xFFD1D5DC.toInt(),
            suggestionDivider = 0xFFB8BDC7.toInt(),
            suggestionText = 0xFF000000.toInt(),
            suggestionHighlightBg = 0xFFE5E8EE.toInt(),
            suggestionHighlightBorder = 0x00000000,
            keyCharBg = 0xFFFFFFFF.toInt(),
            keyCharBgTop = 0xFFFFFFFF.toInt(),
            keyCharBgPressed = 0xFFE3E6EC.toInt(),
            keyCharBorder = 0x00000000,
            keyCharTopRim = 0x00000000,
            keyCharShadow = 0xFF898B90.toInt(),
            keyCharText = 0xFF000000.toInt(),
            keySpecialBg = 0xFFACB3BF.toInt(),
            keySpecialBgTop = 0xFFACB3BF.toInt(),
            keySpecialBgPressed = 0xFFFFFFFF.toInt(),
            keySpecialBorder = 0x00000000,
            keySpecialShadow = 0xFF83878E.toInt(),
            keySpecialText = 0xFF000000.toInt(),
            keyShiftActiveBg = 0xFFFFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueBg = 0xFF007AFF.toInt(),
            keyActionBlueBgTop = 0xFF007AFF.toInt(),
            keyActionBlueBgPressed = 0xFF0062CC.toInt(),
            keyActionBlueBorder = 0x00000000,
            keyActionBlueShadow = 0xFF0051A8.toInt(),
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBg = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xFFFFFFFF.toInt(),
            popupBorder = 0x26000000,
            popupShadow = 0x3D000000,
            popupText = 0xFF000000.toInt(),
            popupPillSelectedBg = 0xFF007AFF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 72,
            chinIconColor = 0xFF4F5560.toInt(),
            chinPressPillBg = 0x44FFFFFF,
            emojiTabActiveBg = 0xFFB8BFC9.toInt(),
            emojiTabIconColor = 0xFF6B7280.toInt(),
            emojiTabActiveIconColor = 0xFF111827.toInt()
        )

        val ClassicDark = IOSKeyboardTheme(
            isDark = true,
            style = KeyboardStyle.CLASSIC,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = false,
            topCornerRadiusDp = 0f,
            keyCornerRadiusDp = 5.5f,
            actionCornerRadiusDp = 5.5f,
            keyboardBg = 0xFF1C1C1E.toInt(),
            keyboardBgFallback = 0xFF1C1C1E.toInt(),
            backdropGradientTop = 0x00000000,
            backdropGradientBottom = 0x00000000,
            topBorderColor = 0xFF2C2C2E.toInt(),
            topSpecularHighlight = 0x00000000,
            suggestionBarBg = 0xFF1C1C1E.toInt(),
            suggestionDivider = 0xFF3A3A3C.toInt(),
            suggestionText = 0xFFFFFFFF.toInt(),
            suggestionHighlightBg = 0xFF333336.toInt(),
            suggestionHighlightBorder = 0x00000000,
            keyCharBg = 0xFF535356.toInt(),
            keyCharBgTop = 0xFF535356.toInt(),
            keyCharBgPressed = 0xFF6E6E72.toInt(),
            keyCharBorder = 0x00000000,
            keyCharTopRim = 0x00000000,
            keyCharShadow = 0xFF0D0D0E.toInt(),
            keyCharText = 0xFFFFFFFF.toInt(),
            keySpecialBg = 0xFF363638.toInt(),
            keySpecialBgTop = 0xFF363638.toInt(),
            keySpecialBgPressed = 0xFF5A5A5E.toInt(),
            keySpecialBorder = 0x00000000,
            keySpecialShadow = 0xFF0D0D0E.toInt(),
            keySpecialText = 0xFFFFFFFF.toInt(),
            keyShiftActiveBg = 0xFFE5E5EA.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueBg = 0xFF0A84FF.toInt(),
            keyActionBlueBgTop = 0xFF0A84FF.toInt(),
            keyActionBlueBgPressed = 0xFF0066D6.toInt(),
            keyActionBlueBorder = 0x00000000,
            keyActionBlueShadow = 0xFF004C9E.toInt(),
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBg = 0xFF636366.toInt(),
            popupBgTop = 0xFF636366.toInt(),
            popupBorder = 0x33FFFFFF,
            popupShadow = 0x66000000,
            popupText = 0xFFFFFFFF.toInt(),
            popupPillSelectedBg = 0xFF0A84FF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 60,
            chinIconColor = 0xFFAEAEB2.toInt(),
            chinPressPillBg = 0x30FFFFFF,
            emojiTabActiveBg = 0xFF3A3A3C.toInt(),
            emojiTabIconColor = 0xFF8E8E93.toInt(),
            emojiTabActiveIconColor = 0xFFFFFFFF.toInt()
        )

        // Backwards-compatible aliases
        val Light: IOSKeyboardTheme get() = LiquidGlassLight
        val Dark: IOSKeyboardTheme get() = LiquidGlassDark

        fun resolve(
            context: Context,
            mode: ThemeMode,
            style: KeyboardStyle = KeyboardStyle.LIQUID_GLASS,
            blurIntensity: BlurIntensity = BlurIntensity.BALANCED
        ): IOSKeyboardTheme {
            val isDark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.AUTO -> {
                    val nightMask = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    nightMask == Configuration.UI_MODE_NIGHT_YES
                }
            }
            val base = when (style) {
                KeyboardStyle.LIQUID_GLASS -> if (isDark) LiquidGlassDark else LiquidGlassLight
                KeyboardStyle.FROSTED_BLUR -> if (isDark) FrostedBlurDark else FrostedBlurLight
                KeyboardStyle.CLASSIC -> if (isDark) ClassicDark else ClassicLight
            }
            return base.copy(blurIntensity = blurIntensity)
        }
    }
}

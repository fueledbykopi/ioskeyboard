package com.ikeys.keyboard.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Shader
import com.ikeys.keyboard.prefs.BlurIntensity
import com.ikeys.keyboard.prefs.KeyboardStyle
import com.ikeys.keyboard.prefs.ThemeMode

/**
 * Latest iOS 26 keyboard material & color system with authentic optical depth:
 * - Liquid Glass: iOS 26 curved optical glass keys with visible top specular light,
 *   translucent body, bottom caustic reflection, Fresnel glass rims, glowing blue capsule Return,
 *   and a floating rounded refractive glass deck.
 * - Frosted Blur: Silky matte iOS frosted acrylic material (`systemChromeMaterial`) with live
 *   backdrop blur + subtle micro-frosted noise grain & clean frosted matte keys.
 * - Classic Solid: Traditional crisp opaque iOS Light & Dark palette.
 */
data class IOSKeyboardTheme(
    val isDark: Boolean,
    val style: KeyboardStyle,
    val blurIntensity: BlurIntensity,
    val useWindowBlur: Boolean,
    val topCornerRadiusDp: Float,
    val keyCornerRadiusDp: Float,
    val actionCornerRadiusDp: Float,
    // Deck / backdrop
    val keyboardBg: Int,               // Translucent tint when live cross-window blur is active
    val keyboardBgFallback: Int,       // Rich glass/frosted base when hardware blur is off or behind solid apps
    val deckGradTop: Int,              // Ambient glass gradient top
    val deckGradMid: Int,              // Ambient glass gradient mid
    val deckGradBottom: Int,           // Ambient glass gradient bottom
    val deckAmbientGlowA: Int,         // Subtle iridescent refraction bloom (left)
    val deckAmbientGlowB: Int,         // Subtle iridescent refraction bloom (right)
    val topRimOuter: Int,              // Crisp luminous top edge specular stroke
    val topRimInner: Int,              // Soft secondary inner glass reflection
    val frostNoiseAlpha: Int,          // Subtle micro-frosted acrylic grain (0..255)
    // Suggestion bar
    val suggestionBarBg: Int,
    val suggestionBarBorder: Int,
    val suggestionDivider: Int,
    val suggestionText: Int,
    val suggestionHighlightBg: Int,
    val suggestionHighlightBorder: Int,
    // Character keys (3-layer optical glass: top specular -> body -> bottom caustic bounce)
    val keyCharTop: Int,
    val keyCharMid: Int,
    val keyCharBottom: Int,
    val keyCharBgPressed: Int,
    val keyCharRimTop: Int,            // Bright top Fresnel glass highlight
    val keyCharRimBottom: Int,         // Subtle cool lower refractive edge (gives real 3D thickness!)
    val keyCharShadow: Int,
    val keyCharText: Int,
    // Special / modifier keys
    val keySpecialTop: Int,
    val keySpecialMid: Int,
    val keySpecialBottom: Int,
    val keySpecialBgPressed: Int,
    val keySpecialRimTop: Int,
    val keySpecialRimBottom: Int,
    val keySpecialShadow: Int,
    val keySpecialText: Int,
    // Active Shift key
    val keyShiftActiveBg: Int,
    val keyShiftActiveIcon: Int,
    // Primary blue action capsule (Search / Send / Go / Done)
    val keyActionBlueTop: Int,
    val keyActionBlueBottom: Int,
    val keyActionBluePressed: Int,
    val keyActionBlueRimTop: Int,
    val keyActionBlueShadow: Int,
    val keyActionBlueText: Int,
    // Key preview teardrop & accent strip
    val popupBgTop: Int,
    val popupBgBottom: Int,
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

    val isFrostedStyle: Boolean
        get() = style == KeyboardStyle.FROSTED_BLUR

    val isTranslucentStyle: Boolean
        get() = style == KeyboardStyle.LIQUID_GLASS || style == KeyboardStyle.FROSTED_BLUR

    fun effectiveBackdropColor(hardwareBlurActive: Boolean): Int {
        if (!isTranslucentStyle) return keyboardBg
        val base = if (hardwareBlurActive) keyboardBg else keyboardBgFallback
        if (!hardwareBlurActive) return base
        val alpha = (Color.alpha(base) * blurIntensity.surfaceAlphaScale).toInt().coerceIn(72, 224)
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }

    companion object {

        @Volatile
        private var cachedFrostShaderLight: BitmapShader? = null

        @Volatile
        private var cachedFrostShaderDark: BitmapShader? = null

        /**
         * Generates a tiny 96x96 tileable fine frosted-acrylic noise texture once per process (~36KB RAM).
         * Makes Frosted Blur & Liquid Glass look unmistakably tactile even over solid app backgrounds.
         */
        fun getFrostNoiseShader(isDark: Boolean): BitmapShader {
            val existing = if (isDark) cachedFrostShaderDark else cachedFrostShaderLight
            if (existing != null) return existing

            synchronized(this) {
                val again = if (isDark) cachedFrostShaderDark else cachedFrostShaderLight
                if (again != null) return again

                val size = 96
                val pixels = IntArray(size * size)
                var seed = if (isDark) 0x5F3759DF else 0x1A2B3C4D
                for (i in pixels.indices) {
                    // Fast deterministic xorshift32 micro-grain
                    seed = seed xor (seed shl 13)
                    seed = seed xor (seed ushr 17)
                    seed = seed xor (seed shl 5)
                    val n = (seed and 0xFF)
                    // Soft subtle frost speckle
                    pixels[i] = if (isDark) {
                        val v = if (n > 128) 255 else 210
                        val a = (n and 0x1F) // 0..31 alpha
                        Color.argb(a, v, v, v)
                    } else {
                        val v = if (n > 140) 255 else 155
                        val a = (n and 0x17) // 0..23 alpha
                        Color.argb(a, v, v, v)
                    }
                }
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                bmp.setPixels(pixels, 0, size, 0, 0, size, size)
                val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                if (isDark) {
                    cachedFrostShaderDark = shader
                } else {
                    cachedFrostShaderLight = shader
                }
                return shader
            }
        }

        // --- iOS 26 LIQUID GLASS (Crystal refractive glass, floating sculpted lens keys) ---

        val LiquidGlassLight = IOSKeyboardTheme(
            isDark = false,
            style = KeyboardStyle.LIQUID_GLASS,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 28f,
            keyCornerRadiusDp = 9.5f,
            actionCornerRadiusDp = 13.5f,
            // Cool refractive glass deck with enough depth so crystal glass keys pop with 3D contrast
            keyboardBg = 0x8CC5D0E2.toInt(),
            keyboardBgFallback = 0xE2C4CFE0.toInt(),
            deckGradTop = 0x66F5F9FF,
            deckGradMid = 0x26D0DCF0,
            deckGradBottom = 0x45AAB8CF,
            deckAmbientGlowA = 0x40FFFFFF,
            deckAmbientGlowB = 0x33A6D4FF,
            topRimOuter = 0xF2FFFFFF.toInt(),
            topRimInner = 0x80E3EEFF.toInt(),
            frostNoiseAlpha = 110,
            // Floating glass pill suggestion strip
            suggestionBarBg = 0x52FFFFFF,
            suggestionBarBorder = 0xA6FFFFFF.toInt(),
            suggestionDivider = 0x3D4C5A70,
            suggestionText = 0xFF0A0D14.toInt(),
            suggestionHighlightBg = 0xD9FFFFFF.toInt(),
            suggestionHighlightBorder = 0xE6FFFFFF.toInt(),
            // 3D Crystal Glass letter keys: bright specular top -> airy glass body -> luminous bottom caustic
            keyCharTop = 0xFAFFFFFF.toInt(),
            keyCharMid = 0xC4F4F8FF.toInt(),
            keyCharBottom = 0xE8FFFFFF.toInt(),
            keyCharBgPressed = 0xF8D9E8FF.toInt(),
            keyCharRimTop = 0xFFFFFFFF.toInt(),
            keyCharRimBottom = 0x545A6E8C,
            keyCharShadow = 0x381D2B42,
            keyCharText = 0xFF090C12.toInt(),
            // Harmonized cool-crystal modifier keys (true iOS 26 unified glass look)
            keySpecialTop = 0xC4DCE5F4.toInt(),
            keySpecialMid = 0x99B9C6DC.toInt(),
            keySpecialBottom = 0xB8CFDCEF.toInt(),
            keySpecialBgPressed = 0xEBFFFFFF.toInt(),
            keySpecialRimTop = 0xCCFFFFFF.toInt(),
            keySpecialRimBottom = 0x4F4A5C78,
            keySpecialShadow = 0x361B283D,
            keySpecialText = 0xFF0D111A.toInt(),
            keyShiftActiveBg = 0xFAFFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            // Glowing iOS 26 Liquid Blue Glass capsule
            keyActionBlueTop = 0xF54AA8FF.toInt(),
            keyActionBlueBottom = 0xF20072F5.toInt(),
            keyActionBluePressed = 0xF5005CD1.toInt(),
            keyActionBlueRimTop = 0xD9C7E6FF.toInt(),
            keyActionBlueShadow = 0x590051B3,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xFCFFFFFF.toInt(),
            popupBgBottom = 0xEBF0F6FF.toInt(),
            popupBorder = 0x595C7090,
            popupShadow = 0x4219273D,
            popupText = 0xFF090C12.toInt(),
            popupPillSelectedBg = 0xFF007AFF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 58,
            chinIconColor = 0xFF283242.toInt(),
            chinPressPillBg = 0x99FFFFFF.toInt(),
            emojiTabActiveBg = 0xCCFFFFFF.toInt(),
            emojiTabIconColor = 0xFF4A5568.toInt(),
            emojiTabActiveIconColor = 0xFF090C12.toInt()
        )

        val LiquidGlassDark = IOSKeyboardTheme(
            isDark = true,
            style = KeyboardStyle.LIQUID_GLASS,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 28f,
            keyCornerRadiusDp = 9.5f,
            actionCornerRadiusDp = 13.5f,
            // Deep obsidian-sapphire refractive glass deck
            keyboardBg = 0x9E0E1118.toInt(),
            keyboardBgFallback = 0xEB10141D.toInt(),
            deckGradTop = 0x30A6C8FF,
            deckGradMid = 0x121B2436,
            deckGradBottom = 0x4005070B,
            deckAmbientGlowA = 0x266BA6FF,
            deckAmbientGlowB = 0x2299C2FF,
            topRimOuter = 0x78FFFFFF,
            topRimInner = 0x2EA6D0FF,
            frostNoiseAlpha = 125,
            // Floating smoky glass pill suggestion strip
            suggestionBarBg = 0x1EFFFFFF,
            suggestionBarBorder = 0x3DFFFFFF,
            suggestionDivider = 0x38FFFFFF,
            suggestionText = 0xFFF7F9FC.toInt(),
            suggestionHighlightBg = 0x42FFFFFF,
            suggestionHighlightBorder = 0x61FFFFFF,
            // Smoky 3D refractive glass letter keys (clear bright top specular + bottom caustic glow)
            keyCharTop = 0x52FFFFFF,
            keyCharMid = 0x2BFFFFFF,
            keyCharBottom = 0x38C7DEFF,
            keyCharBgPressed = 0x7DFFFFFF,
            keyCharRimTop = 0x7AFFFFFF,
            keyCharRimBottom = 0x33A6C8FF,
            keyCharShadow = 0x78000000,
            keyCharText = 0xFFFFFFFF.toInt(),
            // Unified iOS 26 dark glass modifier keys
            keySpecialTop = 0x30FFFFFF,
            keySpecialMid = 0x1AFFFFFF,
            keySpecialBottom = 0x24B0C8EC,
            keySpecialBgPressed = 0x5EFFFFFF,
            keySpecialRimTop = 0x54FFFFFF,
            keySpecialRimBottom = 0x24FFFFFF,
            keySpecialShadow = 0x78000000,
            keySpecialText = 0xFFF2F5FA.toInt(),
            keyShiftActiveBg = 0xEBFFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF0A0D12.toInt(),
            // Luminous iOS 26 Dark Liquid Blue Glass capsule
            keyActionBlueTop = 0xF548A6FF.toInt(),
            keyActionBlueBottom = 0xF00A78F5.toInt(),
            keyActionBluePressed = 0xF5005FCC.toInt(),
            keyActionBlueRimTop = 0xB8C2E3FF.toInt(),
            keyActionBlueShadow = 0x73003D85,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xF2444B59.toInt(),
            popupBgBottom = 0xEB272C36.toInt(),
            popupBorder = 0x6BFFFFFF,
            popupShadow = 0x8C000000.toInt(),
            popupText = 0xFFFFFFFF.toInt(),
            popupPillSelectedBg = 0xFF0A84FF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 48,
            chinIconColor = 0xFFD9DFEB.toInt(),
            chinPressPillBg = 0x42FFFFFF,
            emojiTabActiveBg = 0x45FFFFFF,
            emojiTabIconColor = 0xFF98A0B0.toInt(),
            emojiTabActiveIconColor = 0xFFFFFFFF.toInt()
        )

        // --- iOS FROSTED BLUR (Silky matte frosted acrylic — UIBlurEffect systemChromeMaterial) ---

        val FrostedBlurLight = IOSKeyboardTheme(
            isDark = false,
            style = KeyboardStyle.FROSTED_BLUR,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 20f,
            keyCornerRadiusDp = 6f,
            actionCornerRadiusDp = 6.5f,
            keyboardBg = 0xABD2D6DE.toInt(),
            keyboardBgFallback = 0xEBD2D6DF.toInt(),
            deckGradTop = 0x45FFFFFF,
            deckGradMid = 0x18E8ECF2,
            deckGradBottom = 0x249FA6B3,
            deckAmbientGlowA = 0x26FFFFFF,
            deckAmbientGlowB = 0x1CDFE6F2,
            topRimOuter = 0xB3FFFFFF.toInt(),
            topRimInner = 0x33FFFFFF,
            frostNoiseAlpha = 185, // Clearly visible silky matte acrylic frost grain!
            suggestionBarBg = 0x00000000,
            suggestionBarBorder = 0x00000000,
            suggestionDivider = 0x4A7C8391,
            suggestionText = 0xFF000000.toInt(),
            suggestionHighlightBg = 0xB3FFFFFF.toInt(),
            suggestionHighlightBorder = 0x45FFFFFF,
            // Clean frosted translucent iOS keys (matte satin, no glossy liquid caustic)
            keyCharTop = 0xE6FFFFFF.toInt(),
            keyCharMid = 0xDBFFFFFF.toInt(),
            keyCharBottom = 0xD4FFFFFF.toInt(),
            keyCharBgPressed = 0xE8DDE1E8.toInt(),
            keyCharRimTop = 0x73FFFFFF,
            keyCharRimBottom = 0x266B7280,
            keyCharShadow = 0x5C787D87,
            keyCharText = 0xFF000000.toInt(),
            keySpecialTop = 0xB8AEB5C2.toInt(),
            keySpecialMid = 0xB0A6AEBC.toInt(),
            keySpecialBottom = 0xA89FA7B5.toInt(),
            keySpecialBgPressed = 0xEBFFFFFF.toInt(),
            keySpecialRimTop = 0x4DFFFFFF,
            keySpecialRimBottom = 0x265F6570,
            keySpecialShadow = 0x576D727C,
            keySpecialText = 0xFF000000.toInt(),
            keyShiftActiveBg = 0xF2FFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueTop = 0xF20A82FF.toInt(),
            keyActionBlueBottom = 0xF2007AFF.toInt(),
            keyActionBluePressed = 0xFF0062CC.toInt(),
            keyActionBlueRimTop = 0x59FFFFFF,
            keyActionBlueShadow = 0x5E0051A8,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xF7FFFFFF.toInt(),
            popupBgBottom = 0xF0F5F7FA.toInt(),
            popupBorder = 0x2B000000,
            popupShadow = 0x3D000000,
            popupText = 0xFF000000.toInt(),
            popupPillSelectedBg = 0xFF007AFF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 68,
            chinIconColor = 0xFF424852.toInt(),
            chinPressPillBg = 0x73FFFFFF,
            emojiTabActiveBg = 0xA8FFFFFF.toInt(),
            emojiTabIconColor = 0xFF606773.toInt(),
            emojiTabActiveIconColor = 0xFF111827.toInt()
        )

        val FrostedBlurDark = IOSKeyboardTheme(
            isDark = true,
            style = KeyboardStyle.FROSTED_BLUR,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 20f,
            keyCornerRadiusDp = 6f,
            actionCornerRadiusDp = 6.5f,
            keyboardBg = 0xB31C1D21.toInt(),
            keyboardBgFallback = 0xEE1D1E22.toInt(),
            deckGradTop = 0x26FFFFFF,
            deckGradMid = 0x0CFFFFFF,
            deckGradBottom = 0x28000000,
            deckAmbientGlowA = 0x18FFFFFF,
            deckAmbientGlowB = 0x129EB8DB,
            topRimOuter = 0x45FFFFFF,
            topRimInner = 0x1AFFFFFF,
            frostNoiseAlpha = 195, // Silky matte dark frosted acrylic texture
            suggestionBarBg = 0x00000000,
            suggestionBarBorder = 0x00000000,
            suggestionDivider = 0x408E8E93,
            suggestionText = 0xFFFFFFFF.toInt(),
            suggestionHighlightBg = 0x5254545A.toInt(),
            suggestionHighlightBorder = 0x2BFFFFFF,
            keyCharTop = 0x8F64646A.toInt(),
            keyCharMid = 0x855A5A60.toInt(),
            keyCharBottom = 0x8054545A.toInt(),
            keyCharBgPressed = 0xBF7C7C82.toInt(),
            keyCharRimTop = 0x38FFFFFF,
            keyCharRimBottom = 0x14000000,
            keyCharShadow = 0x7A000000,
            keyCharText = 0xFFFFFFFF.toInt(),
            keySpecialTop = 0x78424248.toInt(),
            keySpecialMid = 0x703A3A40.toInt(),
            keySpecialBottom = 0x6B35353B.toInt(),
            keySpecialBgPressed = 0xAD66666C.toInt(),
            keySpecialRimTop = 0x29FFFFFF,
            keySpecialRimBottom = 0x14000000,
            keySpecialShadow = 0x7A000000,
            keySpecialText = 0xFFFFFFFF.toInt(),
            keyShiftActiveBg = 0xEBE5E5EA.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueTop = 0xF21A8FFF.toInt(),
            keyActionBlueBottom = 0xF00A84FF.toInt(),
            keyActionBluePressed = 0xFF0066D6.toInt(),
            keyActionBlueRimTop = 0x4DFFFFFF,
            keyActionBlueShadow = 0x6B004C9E,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xF558585E.toInt(),
            popupBgBottom = 0xF24A4A50.toInt(),
            popupBorder = 0x3DFFFFFF,
            popupShadow = 0x70000000,
            popupText = 0xFFFFFFFF.toInt(),
            popupPillSelectedBg = 0xFF0A84FF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 56,
            chinIconColor = 0xFFC7C7CC.toInt(),
            chinPressPillBg = 0x38FFFFFF,
            emojiTabActiveBg = 0x6E505056.toInt(),
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
            keyCornerRadiusDp = 5f,
            actionCornerRadiusDp = 5f,
            keyboardBg = 0xFFD1D5DC.toInt(),
            keyboardBgFallback = 0xFFD1D5DC.toInt(),
            deckGradTop = 0x00000000,
            deckGradMid = 0x00000000,
            deckGradBottom = 0x00000000,
            deckAmbientGlowA = 0x00000000,
            deckAmbientGlowB = 0x00000000,
            topRimOuter = 0xFFBCC1CA.toInt(),
            topRimInner = 0x00000000,
            frostNoiseAlpha = 0,
            suggestionBarBg = 0xFFD1D5DC.toInt(),
            suggestionBarBorder = 0x00000000,
            suggestionDivider = 0xFFB8BDC7.toInt(),
            suggestionText = 0xFF000000.toInt(),
            suggestionHighlightBg = 0xFFE5E8EE.toInt(),
            suggestionHighlightBorder = 0x00000000,
            keyCharTop = 0xFFFFFFFF.toInt(),
            keyCharMid = 0xFFFFFFFF.toInt(),
            keyCharBottom = 0xFFFFFFFF.toInt(),
            keyCharBgPressed = 0xFFE3E6EC.toInt(),
            keyCharRimTop = 0x00000000,
            keyCharRimBottom = 0x00000000,
            keyCharShadow = 0xFF898B90.toInt(),
            keyCharText = 0xFF000000.toInt(),
            keySpecialTop = 0xFFACB3BF.toInt(),
            keySpecialMid = 0xFFACB3BF.toInt(),
            keySpecialBottom = 0xFFACB3BF.toInt(),
            keySpecialBgPressed = 0xFFFFFFFF.toInt(),
            keySpecialRimTop = 0x00000000,
            keySpecialRimBottom = 0x00000000,
            keySpecialShadow = 0xFF83878E.toInt(),
            keySpecialText = 0xFF000000.toInt(),
            keyShiftActiveBg = 0xFFFFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueTop = 0xFF007AFF.toInt(),
            keyActionBlueBottom = 0xFF007AFF.toInt(),
            keyActionBluePressed = 0xFF0062CC.toInt(),
            keyActionBlueRimTop = 0x00000000,
            keyActionBlueShadow = 0xFF0051A8.toInt(),
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xFFFFFFFF.toInt(),
            popupBgBottom = 0xFFFFFFFF.toInt(),
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
            keyCornerRadiusDp = 5f,
            actionCornerRadiusDp = 5f,
            keyboardBg = 0xFF1C1C1E.toInt(),
            keyboardBgFallback = 0xFF1C1C1E.toInt(),
            deckGradTop = 0x00000000,
            deckGradMid = 0x00000000,
            deckGradBottom = 0x00000000,
            deckAmbientGlowA = 0x00000000,
            deckAmbientGlowB = 0x00000000,
            topRimOuter = 0xFF2C2C2E.toInt(),
            topRimInner = 0x00000000,
            frostNoiseAlpha = 0,
            suggestionBarBg = 0xFF1C1C1E.toInt(),
            suggestionBarBorder = 0x00000000,
            suggestionDivider = 0xFF3A3A3C.toInt(),
            suggestionText = 0xFFFFFFFF.toInt(),
            suggestionHighlightBg = 0xFF333336.toInt(),
            suggestionHighlightBorder = 0x00000000,
            keyCharTop = 0xFF535356.toInt(),
            keyCharMid = 0xFF535356.toInt(),
            keyCharBottom = 0xFF535356.toInt(),
            keyCharBgPressed = 0xFF6E6E72.toInt(),
            keyCharRimTop = 0x00000000,
            keyCharRimBottom = 0x00000000,
            keyCharShadow = 0xFF0D0D0E.toInt(),
            keyCharText = 0xFFFFFFFF.toInt(),
            keySpecialTop = 0xFF363638.toInt(),
            keySpecialMid = 0xFF363638.toInt(),
            keySpecialBottom = 0xFF363638.toInt(),
            keySpecialBgPressed = 0xFF5A5A5E.toInt(),
            keySpecialRimTop = 0x00000000,
            keySpecialRimBottom = 0x00000000,
            keySpecialShadow = 0xFF0D0D0E.toInt(),
            keySpecialText = 0xFFFFFFFF.toInt(),
            keyShiftActiveBg = 0xFFE5E5EA.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueTop = 0xFF0A84FF.toInt(),
            keyActionBlueBottom = 0xFF0A84FF.toInt(),
            keyActionBluePressed = 0xFF0066D6.toInt(),
            keyActionBlueRimTop = 0x00000000,
            keyActionBlueShadow = 0xFF004C9E.toInt(),
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xFF636366.toInt(),
            popupBgBottom = 0xFF636366.toInt(),
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

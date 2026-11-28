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
 * Latest iOS keyboard material & color system tuned for pixel-faithful Apple visual fidelity:
 * - Liquid Glass (iOS 26): Neutral luminous silver-pearl (Light) and deep obsidian-graphite (Dark)
 *   optical glass with subtle top specular highlights, crisp Fresnel rims, floating suggestion capsule,
 *   and glowing iOS Blue capsule Return.
 * - Frosted Blur: Silky matte iOS frosted acrylic (`systemChromeMaterial`) with live backdrop blur.
 * - Classic Solid: Exact Apple iOS Light (`#D1D3D9` / `#FFFFFF` / `#ABB0BA`) and Dark (`#1E1E1F` / `#5E5E62` / `#3A3A3C`).
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
    val keyboardBg: Int,
    val keyboardBgFallback: Int,
    val deckGradTop: Int,
    val deckGradMid: Int,
    val deckGradBottom: Int,
    val deckAmbientGlowA: Int,
    val deckAmbientGlowB: Int,
    val topRimOuter: Int,
    val topRimInner: Int,
    val frostNoiseAlpha: Int,
    // Suggestion bar
    val suggestionBarBg: Int,
    val suggestionBarBorder: Int,
    val suggestionDivider: Int,
    val suggestionText: Int,
    val suggestionHighlightBg: Int,
    val suggestionHighlightBorder: Int,
    // Character keys
    val keyCharTop: Int,
    val keyCharMid: Int,
    val keyCharBottom: Int,
    val keyCharBgPressed: Int,
    val keyCharRimTop: Int,
    val keyCharRimBottom: Int,
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
        val alpha = (Color.alpha(base) * blurIntensity.surfaceAlphaScale).toInt().coerceIn(78, 226)
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }

    companion object {

        @Volatile
        private var cachedFrostShaderLight: BitmapShader? = null

        @Volatile
        private var cachedFrostShaderDark: BitmapShader? = null

        /**
         * Generates a tiny 96x96 tileable fine frosted-acrylic noise texture once per process (~36KB RAM).
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
                    seed = seed xor (seed shl 13)
                    seed = seed xor (seed ushr 17)
                    seed = seed xor (seed shl 5)
                    val n = (seed and 0xFF)
                    pixels[i] = if (isDark) {
                        val v = if (n > 128) 255 else 210
                        val a = (n and 0x1B)
                        Color.argb(a, v, v, v)
                    } else {
                        val v = if (n > 140) 255 else 160
                        val a = (n and 0x14)
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

        // --- iOS 26 LIQUID GLASS (Authentic neutral silver-pearl & obsidian optical glass) ---

        val LiquidGlassLight = IOSKeyboardTheme(
            isDark = false,
            style = KeyboardStyle.LIQUID_GLASS,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 26f,
            keyCornerRadiusDp = 8.5f,
            actionCornerRadiusDp = 11f,
            // Authentic neutral silver-pearl iOS 26 glass deck (clean, never overly blue)
            keyboardBg = 0x99D2D5DD.toInt(),
            keyboardBgFallback = 0xEAD4D7DF.toInt(),
            deckGradTop = 0x59FFFFFF,
            deckGradMid = 0x22E5E8EF,
            deckGradBottom = 0x38B9BFC9,
            deckAmbientGlowA = 0x33FFFFFF,
            deckAmbientGlowB = 0x1EDCE8FA,
            topRimOuter = 0xEBFFFFFF.toInt(),
            topRimInner = 0x66FFFFFF,
            frostNoiseAlpha = 95,
            // Floating glass pill suggestion bar
            suggestionBarBg = 0x5EFFFFFF,
            suggestionBarBorder = 0xB3FFFFFF.toInt(),
            suggestionDivider = 0x38646C7A,
            suggestionText = 0xFF000000.toInt(),
            suggestionHighlightBg = 0xE6FFFFFF.toInt(),
            suggestionHighlightBorder = 0xF0FFFFFF.toInt(),
            // Crisp luminous white sculpted iOS 26 glass letter keys
            keyCharTop = 0xFCFFFFFF.toInt(),
            keyCharMid = 0xEBFFFFFF.toInt(),
            keyCharBottom = 0xF5FAFAFC.toInt(),
            keyCharBgPressed = 0xFFE3E7EE.toInt(),
            keyCharRimTop = 0xFFFFFFFF.toInt(),
            keyCharRimBottom = 0x456D7482,
            keyCharShadow = 0x42333945,
            keyCharText = 0xFF000000.toInt(),
            // Harmonized neutral-silver glass modifier keys
            keySpecialTop = 0xC4BCC2CE.toInt(),
            keySpecialMid = 0xADB0B7C4.toInt(),
            keySpecialBottom = 0xBAB6BDC9.toInt(),
            keySpecialBgPressed = 0xF2FFFFFF.toInt(),
            keySpecialRimTop = 0xB8FFFFFF.toInt(),
            keySpecialRimBottom = 0x40545B69,
            keySpecialShadow = 0x3D2E3440,
            keySpecialText = 0xFF000000.toInt(),
            keyShiftActiveBg = 0xFCFFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            // Authentic Apple Blue Liquid Glass capsule
            keyActionBlueTop = 0xF72E96FF.toInt(),
            keyActionBlueBottom = 0xF5007AFF.toInt(),
            keyActionBluePressed = 0xF70062CC.toInt(),
            keyActionBlueRimTop = 0xC4C2E2FF.toInt(),
            keyActionBlueShadow = 0x52004EA8,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xFDFFFFFF.toInt(),
            popupBgBottom = 0xF7F7F8FA.toInt(),
            popupBorder = 0x38555C69,
            popupShadow = 0x401C222D,
            popupText = 0xFF000000.toInt(),
            popupPillSelectedBg = 0xFF007AFF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 58,
            chinIconColor = 0xFF3A3F4A.toInt(),
            chinPressPillBg = 0x99FFFFFF.toInt(),
            emojiTabActiveBg = 0xD6FFFFFF.toInt(),
            emojiTabIconColor = 0xFF5C6270.toInt(),
            emojiTabActiveIconColor = 0xFF000000.toInt()
        )

        val LiquidGlassDark = IOSKeyboardTheme(
            isDark = true,
            style = KeyboardStyle.LIQUID_GLASS,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 26f,
            keyCornerRadiusDp = 8.5f,
            actionCornerRadiusDp = 11f,
            // Deep authentic Apple charcoal-obsidian glass deck
            keyboardBg = 0xA818191D.toInt(),
            keyboardBgFallback = 0xF01B1C20.toInt(),
            deckGradTop = 0x26FFFFFF,
            deckGradMid = 0x0E1F2127,
            deckGradBottom = 0x3808090C,
            deckAmbientGlowA = 0x1C99B8E8,
            deckAmbientGlowB = 0x16B8C8E0,
            topRimOuter = 0x6BFFFFFF,
            topRimInner = 0x24FFFFFF,
            frostNoiseAlpha = 110,
            // Floating smoky glass suggestion pill
            suggestionBarBg = 0x22FFFFFF,
            suggestionBarBorder = 0x38FFFFFF,
            suggestionDivider = 0x38FFFFFF,
            suggestionText = 0xFFFFFFFF.toInt(),
            suggestionHighlightBg = 0x45FFFFFF,
            suggestionHighlightBorder = 0x5CFFFFFF,
            // High-clarity smoky glass letter keys (crisp contrast like real iOS Dark)
            keyCharTop = 0x5CFFFFFF,
            keyCharMid = 0x3DFFFFFF,
            keyCharBottom = 0x47E6EEFA,
            keyCharBgPressed = 0x80FFFFFF.toInt(),
            keyCharRimTop = 0x6EFFFFFF,
            keyCharRimBottom = 0x26FFFFFF,
            keyCharShadow = 0x80000000.toInt(),
            keyCharText = 0xFFFFFFFF.toInt(),
            // Unified dark glass modifier keys
            keySpecialTop = 0x36FFFFFF,
            keySpecialMid = 0x21FFFFFF,
            keySpecialBottom = 0x29FFFFFF,
            keySpecialBgPressed = 0x5EFFFFFF,
            keySpecialRimTop = 0x4DFFFFFF,
            keySpecialRimBottom = 0x1EFFFFFF,
            keySpecialShadow = 0x80000000.toInt(),
            keySpecialText = 0xFFF5F6FA.toInt(),
            keyShiftActiveBg = 0xF0FFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            // Luminous iOS Dark Blue capsule
            keyActionBlueTop = 0xF53A9DFF.toInt(),
            keyActionBlueBottom = 0xF20A84FF.toInt(),
            keyActionBluePressed = 0xF50066D6.toInt(),
            keyActionBlueRimTop = 0xADBAE0FF.toInt(),
            keyActionBlueShadow = 0x6E003F8A,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xF552545B.toInt(),
            popupBgBottom = 0xF0383A40.toInt(),
            popupBorder = 0x59FFFFFF,
            popupShadow = 0x8C000000.toInt(),
            popupText = 0xFFFFFFFF.toInt(),
            popupPillSelectedBg = 0xFF0A84FF.toInt(),
            popupPillSelectedText = 0xFFFFFFFF.toInt(),
            trackpadKeyAlpha = 48,
            chinIconColor = 0xFFD1D4DC.toInt(),
            chinPressPillBg = 0x3DFFFFFF,
            emojiTabActiveBg = 0x47FFFFFF,
            emojiTabIconColor = 0xFF989DA8.toInt(),
            emojiTabActiveIconColor = 0xFFFFFFFF.toInt()
        )

        // --- iOS FROSTED BLUR (Silky matte frosted acrylic — UIBlurEffect systemChromeMaterial) ---

        val FrostedBlurLight = IOSKeyboardTheme(
            isDark = false,
            style = KeyboardStyle.FROSTED_BLUR,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = true,
            topCornerRadiusDp = 18f,
            keyCornerRadiusDp = 5.5f,
            actionCornerRadiusDp = 5.5f,
            keyboardBg = 0xB0D1D4DC.toInt(),
            keyboardBgFallback = 0xEDD1D4DC.toInt(),
            deckGradTop = 0x40FFFFFF,
            deckGradMid = 0x16E8EBF0,
            deckGradBottom = 0x209EA4B0,
            deckAmbientGlowA = 0x22FFFFFF,
            deckAmbientGlowB = 0x18E0E4EC,
            topRimOuter = 0xADFFFFFF.toInt(),
            topRimInner = 0x2EFFFFFF,
            frostNoiseAlpha = 165,
            suggestionBarBg = 0x00000000,
            suggestionBarBorder = 0x00000000,
            suggestionDivider = 0x4A7C8391,
            suggestionText = 0xFF000000.toInt(),
            suggestionHighlightBg = 0xB8FFFFFF.toInt(),
            suggestionHighlightBorder = 0x45FFFFFF,
            keyCharTop = 0xEDFFFFFF.toInt(),
            keyCharMid = 0xE6FFFFFF.toInt(),
            keyCharBottom = 0xDEFFFFFF.toInt(),
            keyCharBgPressed = 0xE8DDE0E7.toInt(),
            keyCharRimTop = 0x70FFFFFF,
            keyCharRimBottom = 0x246B7280,
            keyCharShadow = 0x667C8088,
            keyCharText = 0xFF000000.toInt(),
            keySpecialTop = 0xBDB0B5C0.toInt(),
            keySpecialMid = 0xB5A9AFB9.toInt(),
            keySpecialBottom = 0xADA2A8B3.toInt(),
            keySpecialBgPressed = 0xEBFFFFFF.toInt(),
            keySpecialRimTop = 0x4AFFFFFF,
            keySpecialRimBottom = 0x245F6570,
            keySpecialShadow = 0x5E72767F,
            keySpecialText = 0xFF000000.toInt(),
            keyShiftActiveBg = 0xF5FFFFFF.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueTop = 0xF50A82FF.toInt(),
            keyActionBlueBottom = 0xF5007AFF.toInt(),
            keyActionBluePressed = 0xFF0062CC.toInt(),
            keyActionBlueRimTop = 0x59FFFFFF,
            keyActionBlueShadow = 0x5E0051A8,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xFAFFFFFF.toInt(),
            popupBgBottom = 0xF5F6F7FA.toInt(),
            popupBorder = 0x29000000,
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
            topCornerRadiusDp = 18f,
            keyCornerRadiusDp = 5.5f,
            actionCornerRadiusDp = 5.5f,
            keyboardBg = 0xB81E1E21.toInt(),
            keyboardBgFallback = 0xF01E1E21.toInt(),
            deckGradTop = 0x22FFFFFF,
            deckGradMid = 0x0AFFFFFF,
            deckGradBottom = 0x26000000,
            deckAmbientGlowA = 0x14FFFFFF,
            deckAmbientGlowB = 0x10B0C0D8,
            topRimOuter = 0x40FFFFFF,
            topRimInner = 0x16FFFFFF,
            frostNoiseAlpha = 175,
            suggestionBarBg = 0x00000000,
            suggestionBarBorder = 0x00000000,
            suggestionDivider = 0x408E8E93,
            suggestionText = 0xFFFFFFFF.toInt(),
            suggestionHighlightBg = 0x5758585E.toInt(),
            suggestionHighlightBorder = 0x2BFFFFFF,
            keyCharTop = 0x9E68686E.toInt(),
            keyCharMid = 0x965E5E64.toInt(),
            keyCharBottom = 0x8F58585E.toInt(),
            keyCharBgPressed = 0xC47E7E84.toInt(),
            keyCharRimTop = 0x36FFFFFF,
            keyCharRimBottom = 0x14000000,
            keyCharShadow = 0x80000000.toInt(),
            keyCharText = 0xFFFFFFFF.toInt(),
            keySpecialTop = 0x8244444A.toInt(),
            keySpecialMid = 0x7A3C3C42.toInt(),
            keySpecialBottom = 0x7536363C.toInt(),
            keySpecialBgPressed = 0xAD66666C.toInt(),
            keySpecialRimTop = 0x26FFFFFF,
            keySpecialRimBottom = 0x14000000,
            keySpecialShadow = 0x80000000.toInt(),
            keySpecialText = 0xFFFFFFFF.toInt(),
            keyShiftActiveBg = 0xEFE5E5EA.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueTop = 0xF51A8FFF.toInt(),
            keyActionBlueBottom = 0xF20A84FF.toInt(),
            keyActionBluePressed = 0xFF0066D6.toInt(),
            keyActionBlueRimTop = 0x4DFFFFFF,
            keyActionBlueShadow = 0x6B004C9E,
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xF75E5E64.toInt(),
            popupBgBottom = 0xF5505056.toInt(),
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

        // --- CLASSIC SOLID iOS (Pixel-exact Apple Light & Dark colors) ---

        val ClassicLight = IOSKeyboardTheme(
            isDark = false,
            style = KeyboardStyle.CLASSIC,
            blurIntensity = BlurIntensity.BALANCED,
            useWindowBlur = false,
            topCornerRadiusDp = 0f,
            keyCornerRadiusDp = 5f,
            actionCornerRadiusDp = 5f,
            keyboardBg = 0xFFD1D3D9.toInt(),
            keyboardBgFallback = 0xFFD1D3D9.toInt(),
            deckGradTop = 0x00000000,
            deckGradMid = 0x00000000,
            deckGradBottom = 0x00000000,
            deckAmbientGlowA = 0x00000000,
            deckAmbientGlowB = 0x00000000,
            topRimOuter = 0xFFBDC0C7.toInt(),
            topRimInner = 0x00000000,
            frostNoiseAlpha = 0,
            suggestionBarBg = 0xFFD1D3D9.toInt(),
            suggestionBarBorder = 0x00000000,
            suggestionDivider = 0xFFB6BAC2.toInt(),
            suggestionText = 0xFF000000.toInt(),
            suggestionHighlightBg = 0xFFE5E7EC.toInt(),
            suggestionHighlightBorder = 0x00000000,
            keyCharTop = 0xFFFFFFFF.toInt(),
            keyCharMid = 0xFFFFFFFF.toInt(),
            keyCharBottom = 0xFFFFFFFF.toInt(),
            keyCharBgPressed = 0xFFE3E5EB.toInt(),
            keyCharRimTop = 0x00000000,
            keyCharRimBottom = 0x00000000,
            keyCharShadow = 0xFF898A8D.toInt(),
            keyCharText = 0xFF000000.toInt(),
            keySpecialTop = 0xFFABB0BA.toInt(),
            keySpecialMid = 0xFFABB0BA.toInt(),
            keySpecialBottom = 0xFFABB0BA.toInt(),
            keySpecialBgPressed = 0xFFFFFFFF.toInt(),
            keySpecialRimTop = 0x00000000,
            keySpecialRimBottom = 0x00000000,
            keySpecialShadow = 0xFF84878D.toInt(),
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
            keyboardBg = 0xFF1E1E1F.toInt(),
            keyboardBgFallback = 0xFF1E1E1F.toInt(),
            deckGradTop = 0x00000000,
            deckGradMid = 0x00000000,
            deckGradBottom = 0x00000000,
            deckAmbientGlowA = 0x00000000,
            deckAmbientGlowB = 0x00000000,
            topRimOuter = 0xFF2E2E30.toInt(),
            topRimInner = 0x00000000,
            frostNoiseAlpha = 0,
            suggestionBarBg = 0xFF1E1E1F.toInt(),
            suggestionBarBorder = 0x00000000,
            suggestionDivider = 0xFF3A3A3C.toInt(),
            suggestionText = 0xFFFFFFFF.toInt(),
            suggestionHighlightBg = 0xFF3A3A3D.toInt(),
            suggestionHighlightBorder = 0x00000000,
            keyCharTop = 0xFF5E5E62.toInt(),
            keyCharMid = 0xFF5E5E62.toInt(),
            keyCharBottom = 0xFF5E5E62.toInt(),
            keyCharBgPressed = 0xFF78787D.toInt(),
            keyCharRimTop = 0x00000000,
            keyCharRimBottom = 0x00000000,
            keyCharShadow = 0xFF0A0A0B.toInt(),
            keyCharText = 0xFFFFFFFF.toInt(),
            keySpecialTop = 0xFF3A3A3C.toInt(),
            keySpecialMid = 0xFF3A3A3C.toInt(),
            keySpecialBottom = 0xFF3A3A3C.toInt(),
            keySpecialBgPressed = 0xFF5E5E62.toInt(),
            keySpecialRimTop = 0x00000000,
            keySpecialRimBottom = 0x00000000,
            keySpecialShadow = 0xFF0A0A0B.toInt(),
            keySpecialText = 0xFFFFFFFF.toInt(),
            keyShiftActiveBg = 0xFFE5E5EA.toInt(),
            keyShiftActiveIcon = 0xFF000000.toInt(),
            keyActionBlueTop = 0xFF0A84FF.toInt(),
            keyActionBlueBottom = 0xFF0A84FF.toInt(),
            keyActionBluePressed = 0xFF0066D6.toInt(),
            keyActionBlueRimTop = 0x00000000,
            keyActionBlueShadow = 0xFF004C9E.toInt(),
            keyActionBlueText = 0xFFFFFFFF.toInt(),
            popupBgTop = 0xFF6B6B70.toInt(),
            popupBgBottom = 0xFF6B6B70.toInt(),
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

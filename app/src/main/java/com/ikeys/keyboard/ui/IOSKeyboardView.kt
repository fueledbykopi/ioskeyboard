package com.ikeys.keyboard.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.ikeys.keyboard.layout.ComputedKey
import com.ikeys.keyboard.layout.EdgeBias
import com.ikeys.keyboard.layout.IOSKeyboardLayout
import com.ikeys.keyboard.layout.InputVariant
import com.ikeys.keyboard.layout.KeyCode
import com.ikeys.keyboard.layout.KeyType
import com.ikeys.keyboard.layout.KeyboardLayoutEngine
import com.ikeys.keyboard.layout.KeyboardMode
import com.ikeys.keyboard.layout.ReturnAction
import com.ikeys.keyboard.layout.ShiftState
import com.ikeys.keyboard.prediction.SuggestionItem
import com.ikeys.keyboard.prefs.HeightPreset
import kotlin.math.abs

/**
 * Ultra-low-latency hardware-accelerated Canvas keyboard view engineered for the latest iOS look & feel:
 * - Zero-allocation onDraw() & onTouchEvent() via cached static backdrop Bitmap + GPU shaders + Matrix translation
 * - Exact latest iOS edge-to-edge proportions, orientation-aware Portrait/Landscape fit, and SF-Pro optical typography
 * - Authentic iOS 26 Liquid Glass, Frosted Blur, & Classic Solid palettes
 * - Effortless typing ergonomics: full multi-touch rollover across ALL keys, finger-pad vertical compensation,
 *   slide hysteresis on character keys, Bézier teardrop popups (never crushed on top row), long-press diacritics,
 *   and spacebar trackpad cursor control
 */
class IOSKeyboardView(context: Context) : View(context) {

    interface Callback {
        fun onCharacter(text: String)
        fun onSpecialKey(code: Int)
        fun onGlobeLongPress()
        fun onBackspace(deleteWord: Boolean)
        fun onSuggestionPicked(item: SuggestionItem)
        fun onTrackpadStart()
        fun onTrackpadMove(steps: Int)
        fun onTrackpadEnd()
        fun onPopupSlideTick()
        fun onKeyPressFeedback(code: Int, type: KeyType)
    }

    var callback: Callback? = null

    private val density: Float get() = resources.displayMetrics.density
    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private val layoutEngine = KeyboardLayoutEngine()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Dynamic state
    private var theme: IOSKeyboardTheme = IOSKeyboardTheme.LiquidGlassLight
    private var hardwareBlurActive: Boolean = false
    private var keyboardMode: KeyboardMode = KeyboardMode.ALPHA
    private var inputVariant: InputVariant = InputVariant.NORMAL
    private var shiftState: ShiftState = ShiftState.OFF
    private var returnAction: ReturnAction = ReturnAction.RETURN
    private var suggestions: List<SuggestionItem> = emptyList()

    private var popupEnabled: Boolean = true
    private var suggestionBarEnabled: Boolean = true
    private var bottomChinEnabled: Boolean = true
    private var heightPreset: HeightPreset = HeightPreset.STANDARD

    // Temporary iOS-style language indicator on the spacebar (e.g. "English" / "Indonesia")
    private var spacebarBannerText: String? = null
    private var spacebarBannerUntilMs: Long = 0L

    private var computedKeys: List<ComputedKey> = emptyList()
    private var suggestionBarTopPx: Float = 0f
    private var suggestionBarBottomPx: Float = 0f
    private var keyAreaTopPx: Float = 0f
    private var chinTopPx: Float = 0f

    // Typefaces cached once (Apple iOS uses Regular SF Pro for letter keys, Medium for modifiers/actions)
    private val regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // Cached static backdrop bitmap (eliminates 4x fullscreen shader overdraw on every keystroke!)
    private var cachedBackdropBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Reusable Paints, Shaders, Matrix, Paths, and RectFs (ZERO allocation during onDraw)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val deckGradPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ambientGlowPaintA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ambientGlowPaintB = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frostNoisePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Cached GPU shaders translated per key via shaderMatrix (0 allocations per draw call!)
    private val shaderMatrix = Matrix()
    private var charKeyShader: LinearGradient? = null
    private var specialKeyShader: LinearGradient? = null
    private var blueActionShader: LinearGradient? = null
    private var charRimShader: LinearGradient? = null
    private var specialRimShader: LinearGradient? = null
    private var popupShader: LinearGradient? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = regularTypeface
    }
    private val specialTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = mediumTypeface
    }
    private val suggestionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = regularTypeface
    }
    private val popupFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = regularTypeface
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val reusableRect = RectF()
    private val reusableRect2 = RectF()
    private val reusablePath = Path()
    private val backdropPath = Path()
    private val topRimPath = Path()
    private val backdropRadii = FloatArray(8)

    // Multi-touch pointer tracking
    private class PointerSession(
        val pointerId: Int,
        var key: ComputedKey,
        val downX: Float,
        val downY: Float,
        val downTimeMs: Long,
        var isLongPressAlternates: Boolean = false,
        var alternates: List<String> = emptyList(),
        var selectedAlternateIndex: Int = 0,
        var didSwipeDeleteWord: Boolean = false
    )

    private val activePointers = ArrayList<PointerSession>(4)

    // Fading popup tail animation after finger release (authentic iOS feel)
    private var fadingPopupKey: ComputedKey? = null
    private var fadingPopupLabel: String = ""
    private var fadingPopupStartMs: Long = 0L

    // Pointer-isolated suggestion bar & bottom chin tracking
    private var suggestionPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var pressedSuggestionIndex: Int = -1
    private var chinPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var pressedChinAction: Int = 0 // 0=none, KeyCode.GLOBE, KeyCode.DISMISS
    private var globeLongPressTriggered: Boolean = false

    // Spacebar trackpad state
    private var isTrackpadActive: Boolean = false
    private var trackpadPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var trackpadLastStepX: Float = 0f
    private var trackpadAccumX: Float = 0f

    // Backspace repeat / word acceleration state
    private var backspacePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var backspaceDownMs: Long = 0L

    private val longPressAlternatesRunnable = Runnable {
        val session = activePointers.lastOrNull() ?: return@Runnable
        if (!session.key.spec.isPopupEligible || isTrackpadActive) return@Runnable
        val alts = session.key.alternatesFor(shiftState)
        if (alts.size > 1) {
            session.isLongPressAlternates = true
            session.alternates = alts
            session.selectedAlternateIndex = 0
            callback?.onPopupSlideTick()
            invalidate()
        }
    }

    private val globeLongPressRunnable = Runnable {
        if (pressedChinAction == KeyCode.GLOBE && chinPointerId != MotionEvent.INVALID_POINTER_ID) {
            globeLongPressTriggered = true
            pressedChinAction = 0
            chinPointerId = MotionEvent.INVALID_POINTER_ID
            callback?.onPopupSlideTick()
            callback?.onGlobeLongPress()
            invalidate()
        }
    }

    private val spaceTrackpadRunnable = Runnable {
        val session = activePointers.firstOrNull { it.key.spec.code == KeyCode.SPACE } ?: return@Runnable
        isTrackpadActive = true
        trackpadPointerId = session.pointerId
        trackpadLastStepX = session.downX
        trackpadAccumX = 0f
        callback?.onTrackpadStart()
        invalidate()
    }

    private val spacebarBannerClearRunnable = Runnable {
        spacebarBannerText = null
        invalidate()
    }

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (backspacePointerId == MotionEvent.INVALID_POINTER_ID) return
            val heldMs = SystemClock.uptimeMillis() - backspaceDownMs
            val deleteWord = heldMs >= BACKSPACE_WORD_ACCEL_MS
            callback?.onBackspace(deleteWord)
            val nextDelay = if (deleteWord) BACKSPACE_WORD_INTERVAL_MS else BACKSPACE_CHAR_INTERVAL_MS
            mainHandler.postDelayed(this, nextDelay)
        }
    }

    init {
        isClickable = true
        isFocusable = false
        setWillNotDraw(false)
    }

    fun onOrientationOrMetricsChanged() {
        requestLayout()
        recomputeGeometry(width)
        rebuildBackdropAndShaders(width.toFloat(), height.toFloat())
        invalidate()
    }

    fun applyConfig(
        theme: IOSKeyboardTheme,
        hardwareBlurActive: Boolean,
        popupEnabled: Boolean,
        suggestionBarEnabled: Boolean,
        bottomChinEnabled: Boolean,
        heightPreset: HeightPreset
    ) {
        val configUnchanged = this.theme == theme &&
            this.hardwareBlurActive == hardwareBlurActive &&
            this.popupEnabled == popupEnabled &&
            this.suggestionBarEnabled == suggestionBarEnabled &&
            this.bottomChinEnabled == bottomChinEnabled &&
            this.heightPreset == heightPreset
        if (configUnchanged) return

        val needsRelayout = this.suggestionBarEnabled != suggestionBarEnabled ||
            this.bottomChinEnabled != bottomChinEnabled ||
            this.heightPreset != heightPreset ||
            this.theme.topCornerRadiusDp != theme.topCornerRadiusDp

        this.theme = theme
        this.hardwareBlurActive = hardwareBlurActive
        this.popupEnabled = popupEnabled
        this.suggestionBarEnabled = suggestionBarEnabled
        this.bottomChinEnabled = bottomChinEnabled
        this.heightPreset = heightPreset

        // Authentic iOS typography: crisp Regular for letter keys & popups across all styles
        textPaint.typeface = regularTypeface
        popupTextPaint.typeface = regularTypeface
        suggestionPaint.typeface = regularTypeface

        if (needsRelayout) {
            requestLayout()
        }
        recomputeGeometry(width)
        rebuildBackdropAndShaders(width.toFloat(), height.toFloat())
        invalidate()
    }

    fun setKeyboardState(
        mode: KeyboardMode,
        variant: InputVariant,
        shift: ShiftState,
        returnAction: ReturnAction
    ) {
        if (this.keyboardMode == mode &&
            this.inputVariant == variant &&
            this.shiftState == shift &&
            this.returnAction == returnAction
        ) {
            return
        }
        val layoutChanged = this.keyboardMode != mode || this.inputVariant != variant
        this.keyboardMode = mode
        this.inputVariant = variant
        this.shiftState = shift
        this.returnAction = returnAction
        if (layoutChanged) {
            cancelAllActiveSessions()
            recomputeGeometry(width)
        }
        invalidate()
    }

    fun setSuggestions(items: List<SuggestionItem>) {
        if (this.suggestions == items) return
        this.suggestions = items
        invalidate()
    }

    fun flashSpacebarLanguage(label: String) {
        spacebarBannerText = label
        spacebarBannerUntilMs = SystemClock.uptimeMillis() + SPACEBAR_BANNER_DURATION_MS
        mainHandler.removeCallbacks(spacebarBannerClearRunnable)
        mainHandler.postDelayed(spacebarBannerClearRunnable, SPACEBAR_BANNER_DURATION_MS)
        invalidate()
    }

    private fun effectiveRowHeightDp(): Float {
        return if (isLandscape) {
            (heightPreset.rowHeightDp * 0.76f).coerceIn(31f, 38f)
        } else {
            heightPreset.rowHeightDp
        }
    }

    private fun effectiveRowGapDp(): Float = if (isLandscape) 7.5f else ROW_GAP_DP
    private fun effectiveSuggestionBarDp(): Float = if (isLandscape) 36f else SUGGESTION_BAR_DP
    private fun effectiveBottomChinDp(): Float = if (isLandscape) 30f else BOTTOM_CHIN_DP

    fun calculateDesiredHeightPx(): Int {
        val topInset = if (theme.topCornerRadiusDp > 0f) dp(GLASS_TOP_INSET_DP) else 0f
        val suggestionH = if (suggestionBarEnabled) dp(effectiveSuggestionBarDp()) else dp(TOP_NO_BAR_PAD_DP)
        val rowH = dp(effectiveRowHeightDp())
        val rowGap = dp(effectiveRowGapDp())
        val keysTopPad = dp(if (isLandscape) 3.5f else KEYS_TOP_PAD_DP)
        val keysBottomPad = dp(if (isLandscape) 4f else KEYS_BOTTOM_PAD_DP)
        val chinH = if (bottomChinEnabled) dp(effectiveBottomChinDp()) else dp(6f)
        return (topInset + suggestionH + keysTopPad + rowH * 4f + rowGap * 3f + keysBottomPad + chinH).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = calculateDesiredHeightPx()
        setMeasuredDimension(desiredWidth, desiredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGeometry(w)
        rebuildBackdropAndShaders(w.toFloat(), h.toFloat())
    }

    private fun recomputeGeometry(viewWidth: Int) {
        if (viewWidth <= 0) return
        val topInset = if (theme.topCornerRadiusDp > 0f) dp(GLASS_TOP_INSET_DP) else 0f
        suggestionBarTopPx = topInset
        val suggestionH = if (suggestionBarEnabled) dp(effectiveSuggestionBarDp()) else dp(TOP_NO_BAR_PAD_DP)
        suggestionBarBottomPx = suggestionBarTopPx + suggestionH
        keyAreaTopPx = suggestionBarBottomPx + dp(if (isLandscape) 3.5f else KEYS_TOP_PAD_DP)

        val rowH = dp(effectiveRowHeightDp())
        val rowGap = dp(effectiveRowGapDp())
        val keysBottomPad = dp(if (isLandscape) 4f else KEYS_BOTTOM_PAD_DP)
        chinTopPx = keyAreaTopPx + rowH * 4f + rowGap * 3f + keysBottomPad

        // Exact iPhone edge-to-edge fit: ~3.25dp outer edge margin (0.5dp pad + 2.75dp halfGap)
        // Recovers ~11.5dp across all 10 keys so every letter key is wider and easier to tap!
        val horizPad = when {
            isLandscape -> dp(24f)
            theme.isGlassStyle -> dp(1.2f)
            else -> dp(0.5f)
        }
        val keyGapX = if (isLandscape) dp(6.5f) else dp(5.5f)

        val rows = IOSKeyboardLayout.rowsFor(keyboardMode, inputVariant)
        computedKeys = layoutEngine.computeKeys(
            rows = rows,
            metrics = KeyboardLayoutEngine.Metrics(
                width = viewWidth.toFloat(),
                topOffset = keyAreaTopPx,
                rowHeight = rowH,
                horizontalPadding = horizPad,
                keyHorizontalGap = keyGapX,
                rowVerticalGap = rowGap
            )
        )
    }

    private fun rebuildBackdropAndShaders(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val topR = dp(theme.topCornerRadiusDp).coerceAtMost(w * 0.16f)
        backdropRadii[0] = topR
        backdropRadii[1] = topR
        backdropRadii[2] = topR
        backdropRadii[3] = topR
        backdropRadii[4] = 0f
        backdropRadii[5] = 0f
        backdropRadii[6] = 0f
        backdropRadii[7] = 0f

        backdropPath.reset()
        reusableRect.set(0f, 0f, w, h)
        backdropPath.addRoundRect(reusableRect, backdropRadii, Path.Direction.CW)

        topRimPath.reset()
        val inset = dp(0.7f)
        if (topR > 1f) {
            topRimPath.moveTo(inset, topR)
            reusableRect.set(inset, inset, inset + topR * 2f, inset + topR * 2f)
            topRimPath.arcTo(reusableRect, 180f, 90f, false)
            topRimPath.lineTo(w - inset - topR, inset)
            reusableRect.set(w - inset - topR * 2f, inset, w - inset, inset + topR * 2f)
            topRimPath.arcTo(reusableRect, 270f, 90f, false)
        } else {
            topRimPath.moveTo(0f, inset)
            topRimPath.lineTo(w, inset)
        }

        val rowH = dp(effectiveRowHeightDp()).coerceAtLeast(1f)

        if (theme.isTranslucentStyle) {
            deckGradPaint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(theme.deckGradTop, theme.deckGradMid, theme.deckGradBottom),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )

            ambientGlowPaintA.shader = if ((theme.deckAmbientGlowA ushr 24) > 0) {
                RadialGradient(
                    w * 0.18f, h * 0.08f, w * 0.62f,
                    theme.deckAmbientGlowA, 0x00000000,
                    Shader.TileMode.CLAMP
                )
            } else {
                null
            }
            ambientGlowPaintB.shader = if ((theme.deckAmbientGlowB ushr 24) > 0) {
                RadialGradient(
                    w * 0.84f, h * 0.25f, w * 0.58f,
                    theme.deckAmbientGlowB, 0x00000000,
                    Shader.TileMode.CLAMP
                )
            } else {
                null
            }

            if (theme.frostNoiseAlpha > 0) {
                frostNoisePaint.shader = IOSKeyboardTheme.getFrostNoiseShader(theme.isDark)
                frostNoisePaint.alpha = theme.frostNoiseAlpha
            } else {
                frostNoisePaint.shader = null
            }

            charKeyShader = LinearGradient(
                0f, 0f, 0f, rowH,
                intArrayOf(theme.keyCharTop, theme.keyCharMid, theme.keyCharBottom),
                floatArrayOf(0f, 0.54f, 1f),
                Shader.TileMode.CLAMP
            )
            specialKeyShader = LinearGradient(
                0f, 0f, 0f, rowH,
                intArrayOf(theme.keySpecialTop, theme.keySpecialMid, theme.keySpecialBottom),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            blueActionShader = LinearGradient(
                0f, 0f, 0f, rowH,
                intArrayOf(theme.keyActionBlueTop, theme.keyActionBlueBottom),
                null,
                Shader.TileMode.CLAMP
            )
            charRimShader = LinearGradient(
                0f, 0f, 0f, rowH,
                intArrayOf(theme.keyCharRimTop, 0x18FFFFFF, theme.keyCharRimBottom),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            specialRimShader = LinearGradient(
                0f, 0f, 0f, rowH,
                intArrayOf(theme.keySpecialRimTop, 0x12FFFFFF, theme.keySpecialRimBottom),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )
            popupShader = LinearGradient(
                0f, 0f, 0f, dp(110f),
                intArrayOf(theme.popupBgTop, theme.popupBgBottom),
                null,
                Shader.TileMode.CLAMP
            )
        } else {
            deckGradPaint.shader = null
            ambientGlowPaintA.shader = null
            ambientGlowPaintB.shader = null
            frostNoisePaint.shader = null
            charKeyShader = null
            specialKeyShader = null
            blueActionShader = null
            charRimShader = null
            specialRimShader = null
            popupShader = null
        }

        rebuildBackdropCacheBitmap(w.toInt(), h.toInt())
    }

    /**
     * Pre-renders the static glass/frosted/classic backdrop into an offscreen Bitmap once per resize/theme change,
     * turning 4+ fullscreen shader passes per keystroke into a single hardware-accelerated drawBitmap()!
     */
    private fun rebuildBackdropCacheBitmap(w: Int, h: Int) {
        cachedBackdropBitmap?.recycle()
        cachedBackdropBitmap = null
        if (w <= 0 || h <= 0) return
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val offCanvas = Canvas(bmp)
            offCanvas.drawColor(Color.TRANSPARENT)
            renderBackdropDirect(offCanvas, w.toFloat(), h.toFloat())
            cachedBackdropBitmap = bmp
        } catch (_: OutOfMemoryError) {
            cachedBackdropBitmap = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Keyboard Backdrop (single cached bitmap draw for locked 120fps typing!)
        val bmp = cachedBackdropBitmap
        if (bmp != null && !bmp.isRecycled && bmp.width == width && bmp.height == height) {
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        } else {
            renderBackdropDirect(canvas, w, h)
        }

        // 2. QuickType Suggestion Bar
        if (suggestionBarEnabled) {
            drawSuggestionBar(canvas, w)
        }

        // 3. Keys
        val charCornerR = dp(theme.keyCornerRadiusDp)
        val actionCornerR = dp(theme.actionCornerRadiusDp)
        val shadowOffsetY = if (theme.isGlassStyle) dp(1.35f) else dp(1.05f)
        for (i in computedKeys.indices) {
            val key = computedKeys[i]
            val isPressed = isKeyPressed(key)
            val cornerR = when {
                theme.isGlassStyle && key.spec.code == KeyCode.RETURN -> key.drawRect.height * 0.48f
                theme.isGlassStyle && key.spec.code == KeyCode.SPACE -> dp(10.5f)
                key.spec.type == KeyType.CHARACTER -> charCornerR
                else -> actionCornerR
            }
            drawKey(canvas, key, isPressed, cornerR, shadowOffsetY)
        }

        // 4. Optional iPhone-style Bottom Chin (Globe left, Keyboard Dismiss right)
        if (bottomChinEnabled) {
            drawBottomChin(canvas, w, h)
        }

        // 5. Floating Key Preview Popup or Long-Press Accent Strip (drawn on top, zero clipping!)
        drawActivePopups(canvas, w)
    }

    private fun renderBackdropDirect(canvas: Canvas, w: Float, h: Float) {
        if (!hardwareBlurActive || !theme.useWindowBlur) {
            bgPaint.color = theme.effectiveBackdropColor(hardwareBlurActive = false)
            if (theme.topCornerRadiusDp > 0f) {
                canvas.drawPath(backdropPath, bgPaint)
            } else {
                canvas.drawRect(0f, 0f, w, h, bgPaint)
            }
        }

        if (theme.isTranslucentStyle) {
            if (deckGradPaint.shader != null) {
                canvas.drawPath(backdropPath, deckGradPaint)
            }
            if (ambientGlowPaintA.shader != null) {
                canvas.drawPath(backdropPath, ambientGlowPaintA)
            }
            if (ambientGlowPaintB.shader != null) {
                canvas.drawPath(backdropPath, ambientGlowPaintB)
            }
            if (frostNoisePaint.shader != null) {
                canvas.drawPath(backdropPath, frostNoisePaint)
            }
        }

        if (theme.topCornerRadiusDp > 0f) {
            topRimPaint.color = theme.topRimOuter
            topRimPaint.strokeWidth = if (theme.isGlassStyle) dp(1.25f) else dp(0.9f)
            canvas.drawPath(topRimPath, topRimPaint)

            if (theme.isGlassStyle && (theme.topRimInner ushr 24) > 0) {
                topRimPaint.color = theme.topRimInner
                topRimPaint.strokeWidth = dp(0.9f)
                canvas.save()
                canvas.translate(0f, dp(1.4f))
                canvas.drawPath(topRimPath, topRimPaint)
                canvas.restore()
            }
        } else {
            bgPaint.color = theme.topRimOuter
            canvas.drawRect(0f, 0f, w, dp(0.75f), bgPaint)
        }
    }

    private fun drawSuggestionBar(canvas: Canvas, viewWidth: Float) {
        val barTop = suggestionBarTopPx
        val barBottom = suggestionBarBottomPx
        val barH = barBottom - barTop

        if (theme.isGlassStyle) {
            val capInsetX = dp(8f)
            val capTop = barTop + dp(3.5f)
            val capBottom = barBottom - dp(3f)
            val capR = (capBottom - capTop) * 0.5f
            reusableRect.set(capInsetX, capTop, viewWidth - capInsetX, capBottom)

            keyPaint.color = theme.suggestionBarBg
            canvas.drawRoundRect(reusableRect, capR, capR, keyPaint)

            keyBorderPaint.shader = null
            keyBorderPaint.color = theme.suggestionBarBorder
            keyBorderPaint.strokeWidth = dp(0.8f)
            canvas.drawRoundRect(reusableRect, capR, capR, keyBorderPaint)
        } else if ((theme.suggestionBarBg ushr 24) > 0 && !theme.isTranslucentStyle) {
            bgPaint.color = theme.suggestionBarBg
            canvas.drawRect(0f, barTop + dp(0.75f), viewWidth, barBottom, bgPaint)
        }

        val sideInset = if (theme.isGlassStyle) dp(10f) else dp(4f)
        val usableW = (viewWidth - sideInset * 2f).coerceAtLeast(1f)
        val slotCount = 3
        val slotW = usableW / slotCount
        val dividerTop = barTop + dp(10.5f)
        val dividerBottom = barBottom - dp(10f)
        val dividerW = dp(0.8f)

        // Vertical hairline dividers
        bgPaint.color = theme.suggestionDivider
        val div1X = sideInset + slotW
        val div2X = sideInset + slotW * 2f
        canvas.drawRect(div1X - dividerW * 0.5f, dividerTop, div1X + dividerW * 0.5f, dividerBottom, bgPaint)
        canvas.drawRect(div2X - dividerW * 0.5f, dividerTop, div2X + dividerW * 0.5f, dividerBottom, bgPaint)

        suggestionPaint.textSize = sp(if (isLandscape) 14.2f else 15.5f)
        val textCenterY = barTop + barH * 0.5f - (suggestionPaint.descent() + suggestionPaint.ascent()) * 0.5f + dp(0.3f)
        val pillPadV = if (theme.isGlassStyle) dp(6f) else dp(5f)
        val pillR = if (theme.isGlassStyle) (barH - pillPadV * 2f) * 0.5f else dp(7f)
        val maxTextWidth = (slotW - dp(14f)).coerceAtLeast(dp(24f))

        for (i in 0 until slotCount) {
            val item = suggestions.getOrNull(i) ?: continue
            val left = sideInset + i * slotW
            val right = left + slotW

            if (pressedSuggestionIndex == i || item.isAutoCorrectCandidate) {
                keyPaint.color = theme.suggestionHighlightBg
                reusableRect.set(left + dp(4f), barTop + pillPadV, right - dp(4f), barBottom - pillPadV + dp(0.5f))
                canvas.drawRoundRect(reusableRect, pillR, pillR, keyPaint)

                if (theme.isGlassStyle && (theme.suggestionHighlightBorder ushr 24) > 0) {
                    keyBorderPaint.shader = null
                    keyBorderPaint.color = theme.suggestionHighlightBorder
                    keyBorderPaint.strokeWidth = dp(0.75f)
                    canvas.drawRoundRect(reusableRect, pillR, pillR, keyBorderPaint)
                }
            }

            suggestionPaint.color = theme.suggestionText
            val rawLabel = if (item.isLiteral) "\u201C${item.text}\u201D" else item.text
            val fittedLabel = ellipsizeToWidth(rawLabel, suggestionPaint, maxTextWidth)
            canvas.drawText(fittedLabel, (left + right) * 0.5f, textCenterY, suggestionPaint)
        }
    }

    private fun ellipsizeToWidth(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val avail = (maxWidth - paint.measureText(ellipsis)).coerceAtLeast(1f)
        val count = paint.breakText(text, true, avail, null)
        return if (count > 0) text.substring(0, count) + ellipsis else ellipsis
    }

    private fun drawKey(
        canvas: Canvas,
        key: ComputedKey,
        isPressed: Boolean,
        cornerR: Float,
        shadowOffsetY: Float
    ) {
        val r = key.drawRect
        val spec = key.spec
        val isShiftActive = spec.code == KeyCode.SHIFT && shiftState != ShiftState.OFF
        val isBlueReturn = spec.code == KeyCode.RETURN && returnAction.isBlueAccent
        val isCharOrSpace = spec.type == KeyType.CHARACTER || spec.type == KeyType.SPACE

        val shadowColor: Int = when {
            isBlueReturn -> theme.keyActionBlueShadow
            isShiftActive || isCharOrSpace -> theme.keyCharShadow
            else -> theme.keySpecialShadow
        }

        val alphaScale = if (isTrackpadActive) theme.trackpadKeyAlpha / 255f else 1f

        // 1. Crisp 1dp iOS bottom drop shadow underneath each keycap
        shadowPaint.color = shadowColor
        if (isTrackpadActive) {
            shadowPaint.alpha = ((shadowColor ushr 24) * alphaScale).toInt()
        }
        val shadowExpandX = if (theme.isGlassStyle) dp(0.25f) else 0f
        reusableRect.set(
            r.left - shadowExpandX,
            r.top + shadowOffsetY,
            r.right + shadowExpandX,
            r.bottom + shadowOffsetY
        )
        canvas.drawRoundRect(reusableRect, cornerR, cornerR, shadowPaint)

        // 2. Main Keycap Body
        reusableRect.set(r.left, r.top, r.right, r.bottom)
        val faceSolidFallback: Int
        if (theme.isTranslucentStyle && !isPressed && !isShiftActive) {
            val activeShader = when {
                isBlueReturn -> blueActionShader
                isCharOrSpace -> charKeyShader
                else -> specialKeyShader
            }
            faceSolidFallback = when {
                isBlueReturn -> theme.keyActionBlueBottom
                isCharOrSpace -> theme.keyCharMid
                else -> theme.keySpecialMid
            }
            if (activeShader != null) {
                shaderMatrix.setTranslate(0f, r.top)
                activeShader.setLocalMatrix(shaderMatrix)
                keyGradientPaint.shader = activeShader
                keyGradientPaint.alpha = if (isTrackpadActive) theme.trackpadKeyAlpha else 255
                canvas.drawRoundRect(reusableRect, cornerR, cornerR, keyGradientPaint)
            } else {
                keyPaint.color = faceSolidFallback
                keyPaint.alpha = if (isTrackpadActive) theme.trackpadKeyAlpha else 255
                canvas.drawRoundRect(reusableRect, cornerR, cornerR, keyPaint)
            }
        } else {
            faceSolidFallback = when {
                isBlueReturn -> if (isPressed) theme.keyActionBluePressed else theme.keyActionBlueBottom
                isShiftActive -> theme.keyShiftActiveBg
                isCharOrSpace -> if (isPressed) theme.keyCharBgPressed else theme.keyCharMid
                else -> if (isPressed) theme.keySpecialBgPressed else theme.keySpecialMid
            }
            keyPaint.color = faceSolidFallback
            if (isTrackpadActive) {
                keyPaint.alpha = ((faceSolidFallback ushr 24) * alphaScale).toInt()
            }
            canvas.drawRoundRect(reusableRect, cornerR, cornerR, keyPaint)
        }

        // 3. Liquid Glass Fresnel Rim + Specular Top Edge Highlight
        if (theme.isGlassStyle && !isTrackpadActive) {
            val strokeW = dp(0.9f)
            val halfS = strokeW * 0.5f
            reusableRect2.set(r.left + halfS, r.top + halfS, r.right - halfS, r.bottom - halfS)
            keyBorderPaint.strokeWidth = strokeW

            when {
                isBlueReturn -> {
                    keyBorderPaint.shader = null
                    keyBorderPaint.color = theme.keyActionBlueRimTop
                    canvas.drawRoundRect(reusableRect2, cornerR, cornerR, keyBorderPaint)
                }
                isCharOrSpace || isShiftActive -> {
                    val rimShader = charRimShader
                    if (rimShader != null && !isPressed) {
                        shaderMatrix.setTranslate(0f, r.top)
                        rimShader.setLocalMatrix(shaderMatrix)
                        keyBorderPaint.shader = rimShader
                    } else {
                        keyBorderPaint.shader = null
                        keyBorderPaint.color = theme.keyCharRimTop
                    }
                    canvas.drawRoundRect(reusableRect2, cornerR, cornerR, keyBorderPaint)
                }
                else -> {
                    val rimShader = specialRimShader
                    if (rimShader != null && !isPressed) {
                        shaderMatrix.setTranslate(0f, r.top)
                        rimShader.setLocalMatrix(shaderMatrix)
                        keyBorderPaint.shader = rimShader
                    } else {
                        keyBorderPaint.shader = null
                        keyBorderPaint.color = theme.keySpecialRimTop
                    }
                    canvas.drawRoundRect(reusableRect2, cornerR, cornerR, keyBorderPaint)
                }
            }
            keyBorderPaint.shader = null

            val topCatchColor = when {
                isBlueReturn -> theme.keyActionBlueRimTop
                isCharOrSpace || isShiftActive -> theme.keyCharRimTop
                else -> theme.keySpecialRimTop
            }
            if ((topCatchColor ushr 24) > 0) {
                keyBorderPaint.color = topCatchColor
                keyBorderPaint.strokeWidth = dp(0.85f)
                val bevelInset = (cornerR * 0.65f).coerceAtMost(r.width * 0.35f)
                val lineY = r.top + dp(0.75f)
                canvas.drawLine(r.left + bevelInset, lineY, r.right - bevelInset, lineY, keyBorderPaint)
            }
        } else if (theme.isFrostedStyle && !isTrackpadActive) {
            keyBorderPaint.shader = null
            keyBorderPaint.color = if (isCharOrSpace) theme.keyCharRimTop else theme.keySpecialRimTop
            keyBorderPaint.strokeWidth = dp(0.55f)
            reusableRect2.set(r.left + dp(0.3f), r.top + dp(0.3f), r.right - dp(0.3f), r.bottom - dp(0.3f))
            canvas.drawRoundRect(reusableRect2, cornerR, cornerR, keyBorderPaint)
        }

        // Authentic iOS Trackpad Effect: all labels & glyphs vanish while sliding cursor!
        if (isTrackpadActive) return

        val cx = r.centerX
        val cy = r.centerY

        when (spec.code) {
            KeyCode.SHIFT -> {
                val iconColor = if (isShiftActive) theme.keyShiftActiveIcon else theme.keySpecialText
                IOSVectorIcons.drawShiftIcon(
                    canvas = canvas,
                    cx = cx,
                    cy = cy,
                    size = dp(if (isLandscape) 18f else 20.5f),
                    state = shiftState,
                    color = iconColor,
                    paint = iconPaint,
                    path = reusablePath
                )
            }
            KeyCode.BACKSPACE -> {
                IOSVectorIcons.drawBackspaceIcon(
                    canvas = canvas,
                    cx = cx,
                    cy = cy,
                    size = dp(if (isLandscape) 19f else 21.5f),
                    isPressed = isPressed,
                    fgColor = theme.keySpecialText,
                    bgColor = faceSolidFallback,
                    paint = iconPaint,
                    path = reusablePath
                )
            }
            KeyCode.EMOJI -> {
                IOSVectorIcons.drawEmojiSmileyIcon(
                    canvas = canvas,
                    cx = cx,
                    cy = cy,
                    size = dp(if (isLandscape) 19f else 21.5f),
                    color = theme.keySpecialText,
                    paint = iconPaint,
                    rect = reusableRect2
                )
            }
            KeyCode.RETURN -> {
                if (returnAction == ReturnAction.RETURN) {
                    IOSVectorIcons.drawReturnArrowIcon(
                        canvas = canvas,
                        cx = cx,
                        cy = cy,
                        size = dp(if (isLandscape) 19f else 21f),
                        color = theme.keySpecialText,
                        paint = iconPaint,
                        path = reusablePath
                    )
                } else {
                    specialTextPaint.textSize = sp(if (isLandscape) 14.5f else 15.8f)
                    specialTextPaint.color = if (isBlueReturn) theme.keyActionBlueText else theme.keySpecialText
                    val baselineY = cy - (specialTextPaint.descent() + specialTextPaint.ascent()) * 0.5f
                    canvas.drawText(returnAction.label, cx, baselineY, specialTextPaint)
                }
            }
            KeyCode.SPACE -> {
                val banner = spacebarBannerText
                val label = if (banner != null && SystemClock.uptimeMillis() < spacebarBannerUntilMs) {
                    banner
                } else {
                    "space"
                }
                specialTextPaint.textSize = sp(if (isLandscape) 14.5f else 15.5f)
                specialTextPaint.color = theme.keyCharText
                specialTextPaint.alpha = if (banner != null) 235 else 185
                val baselineY = cy - (specialTextPaint.descent() + specialTextPaint.ascent()) * 0.5f
                canvas.drawText(label, cx, baselineY, specialTextPaint)
                specialTextPaint.alpha = 255
            }
            else -> {
                val label = key.displayLabel(shiftState, returnAction)
                if (spec.type == KeyType.CHARACTER) {
                    val isUpperLetter = shiftState.isUpperCase && label.length == 1 && label[0].isLetter()
                    val isLowerLetter = !isUpperLetter && label.length == 1 && label[0].isLowerCase()
                    textPaint.textSize = when {
                        isLandscape && isUpperLetter -> sp(18.5f)
                        isLandscape -> sp(20f)
                        isUpperLetter -> sp(21.4f)
                        else -> sp(23.2f)
                    }
                    textPaint.color = theme.keyCharText
                    // Exact Apple SF Pro optical centering: lowercase sits slightly higher visually
                    val opticalOffset = when {
                        isLowerLetter -> dp(-1.35f)
                        isUpperLetter -> dp(0.35f)
                        else -> dp(-0.2f)
                    }
                    val baselineY = cy - (textPaint.descent() + textPaint.ascent()) * 0.5f + opticalOffset
                    canvas.drawText(label, cx, baselineY, textPaint)
                } else {
                    specialTextPaint.textSize = sp(if (isLandscape) 14f else 15.4f)
                    specialTextPaint.color = theme.keySpecialText
                    val baselineY = cy - (specialTextPaint.descent() + specialTextPaint.ascent()) * 0.5f
                    canvas.drawText(label, cx, baselineY, specialTextPaint)
                }
            }
        }
    }

    private fun drawBottomChin(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
        // Position slightly higher in chin to stay safely clear of Android's bottom gesture bar
        val chinH = viewHeight - chinTopPx
        val chinCy = chinTopPx + (chinH * 0.44f).coerceAtLeast(dp(14f))
        val iconSize = dp(if (isLandscape) 20f else 23.5f)
        val marginX = dp(if (isLandscape) 42f else 32f)
        val pillR = dp(18f)

        // Left: Globe icon (tap to switch keyboard, hold for system picker)
        if (pressedChinAction == KeyCode.GLOBE) {
            keyPaint.color = theme.chinPressPillBg
            canvas.drawCircle(marginX, chinCy, pillR, keyPaint)
        }
        iconPaint.alpha = if (pressedChinAction == KeyCode.GLOBE) 190 else 255
        IOSVectorIcons.drawGlobeIcon(
            canvas = canvas,
            cx = marginX,
            cy = chinCy,
            size = iconSize,
            color = theme.chinIconColor,
            paint = iconPaint,
            rect = reusableRect2,
            path = reusablePath
        )

        // Right: Keyboard dismiss icon
        if (pressedChinAction == KeyCode.DISMISS) {
            keyPaint.color = theme.chinPressPillBg
            canvas.drawCircle(viewWidth - marginX, chinCy, pillR, keyPaint)
        }
        iconPaint.alpha = if (pressedChinAction == KeyCode.DISMISS) 190 else 255
        IOSVectorIcons.drawKeyboardDismissIcon(
            canvas = canvas,
            cx = viewWidth - marginX,
            cy = chinCy,
            size = iconSize,
            color = theme.chinIconColor,
            paint = iconPaint,
            rect = reusableRect2,
            path = reusablePath
        )
        iconPaint.alpha = 255
    }

    private fun drawActivePopups(canvas: Canvas, viewWidth: Float) {
        if (isTrackpadActive) return

        val fadeKey = fadingPopupKey
        if (fadeKey != null && popupEnabled) {
            val elapsed = SystemClock.uptimeMillis() - fadingPopupStartMs
            if (elapsed < POPUP_FADE_DURATION_MS) {
                val alphaFrac = 1f - (elapsed.toFloat() / POPUP_FADE_DURATION_MS)
                drawTeardropPopup(canvas, fadeKey, fadingPopupLabel, viewWidth, (alphaFrac * 255f).toInt())
                postInvalidateOnAnimation()
            } else {
                fadingPopupKey = null
            }
        }

        for (i in activePointers.indices) {
            val session = activePointers[i]
            if (!session.key.spec.isPopupEligible) continue

            if (session.isLongPressAlternates && session.alternates.isNotEmpty()) {
                drawAlternatesStrip(canvas, session, viewWidth)
            } else if (popupEnabled) {
                val label = session.key.displayLabel(shiftState, returnAction)
                drawTeardropPopup(canvas, session.key, label, viewWidth, 255)
            }
        }
    }

    private fun drawTeardropPopup(
        canvas: Canvas,
        key: ComputedKey,
        label: String,
        viewWidth: Float,
        alpha: Int
    ) {
        val kr = key.drawRect
        val headWidth = (kr.width * 1.56f).coerceAtLeast(dp(48f))
        val desiredHeadHeight = dp(if (isLandscape) 44f else 52f)
        val stemNeckH = dp(10f)
        val rHead = if (theme.isGlassStyle) dp(13f) else dp(9.5f)
        val rBase = dp(theme.keyCornerRadiusDp)

        var headLeft = kr.centerX - headWidth * 0.5f
        when (key.spec.edgeBias) {
            EdgeBias.LEFT -> headLeft = kr.left
            EdgeBias.RIGHT -> headLeft = kr.right - headWidth
            EdgeBias.NONE -> Unit
        }
        headLeft = headLeft.coerceIn(dp(2.5f), (viewWidth - headWidth - dp(2.5f)).coerceAtLeast(dp(2.5f)))
        val headRight = headLeft + headWidth

        val baseTop = kr.top
        val baseBottom = kr.bottom

        // Prevent top-row (Q..P) teardrop from getting vertically crushed when suggestion bar is hidden!
        var headBottom = kr.top - stemNeckH
        var headTop = headBottom - desiredHeadHeight
        val minTop = dp(2f)
        if (headTop < minTop) {
            headTop = minTop
            headBottom = (headTop + dp(44f)).coerceAtMost(kr.top + dp(6f))
        }

        reusablePath.reset()
        reusablePath.moveTo(headLeft + rHead, headTop)
        reusablePath.lineTo(headRight - rHead, headTop)
        reusablePath.quadTo(headRight, headTop, headRight, headTop + rHead)

        if (headBottom < baseTop - dp(2f)) {
            // Classic full teardrop with smooth Bézier neck
            reusablePath.lineTo(headRight, headBottom - rHead)
            reusablePath.cubicTo(
                headRight, headBottom + stemNeckH * 0.35f,
                kr.right, baseTop - stemNeckH * 0.35f,
                kr.right, baseTop + rBase
            )
            reusablePath.lineTo(kr.right, baseBottom - rBase)
            reusablePath.quadTo(kr.right, baseBottom, kr.right - rBase, baseBottom)
            reusablePath.lineTo(kr.left + rBase, baseBottom)
            reusablePath.quadTo(kr.left, baseBottom, kr.left, baseBottom - rBase)
            reusablePath.lineTo(kr.left, baseTop + rBase)
            reusablePath.cubicTo(
                kr.left, baseTop - stemNeckH * 0.35f,
                headLeft, headBottom + stemNeckH * 0.35f,
                headLeft, headBottom - rHead
            )
        } else {
            // Compact rounded magnifier card for top row when space above is very tight
            reusablePath.lineTo(headRight, headBottom - rHead)
            reusablePath.quadTo(headRight, headBottom, headRight - rHead, headBottom)
            reusablePath.lineTo(headLeft + rHead, headBottom)
            reusablePath.quadTo(headLeft, headBottom, headLeft, headBottom - rHead)
        }
        reusablePath.lineTo(headLeft, headTop + rHead)
        reusablePath.quadTo(headLeft, headTop, headLeft + rHead, headTop)
        reusablePath.close()

        // Soft drop shadow
        popupFillPaint.shader = null
        popupFillPaint.color = theme.popupShadow
        popupFillPaint.alpha = ((theme.popupShadow ushr 24) * alpha) / 255
        canvas.save()
        canvas.translate(0f, dp(2.8f))
        canvas.drawPath(reusablePath, popupFillPaint)
        canvas.restore()

        // Optical glass popup fill
        val pShader = popupShader
        if (pShader != null && theme.isTranslucentStyle) {
            shaderMatrix.setTranslate(0f, headTop)
            pShader.setLocalMatrix(shaderMatrix)
            popupFillPaint.shader = pShader
            popupFillPaint.alpha = alpha
        } else {
            popupFillPaint.shader = null
            popupFillPaint.color = theme.popupBgTop
            popupFillPaint.alpha = alpha
        }
        canvas.drawPath(reusablePath, popupFillPaint)
        popupFillPaint.shader = null

        // Crisp outline
        popupStrokePaint.color = theme.popupBorder
        popupStrokePaint.alpha = ((theme.popupBorder ushr 24) * alpha) / 255
        canvas.drawPath(reusablePath, popupStrokePaint)

        // Magnified character
        popupTextPaint.color = theme.popupText
        popupTextPaint.alpha = alpha
        popupTextPaint.textSize = sp(if (isLandscape) 28f else 33f)
        val textCenterY = (headTop + headBottom) * 0.5f - (popupTextPaint.descent() + popupTextPaint.ascent()) * 0.5f
        canvas.drawText(label, (headLeft + headRight) * 0.5f, textCenterY, popupTextPaint)
    }

    private fun drawAlternatesStrip(canvas: Canvas, session: PointerSession, viewWidth: Float) {
        val kr = session.key.drawRect
        val alts = session.alternates
        val cellW = dp(38f)
        val padH = dp(6f)
        val stripW = (alts.size * cellW + padH * 2f).coerceAtMost(viewWidth - dp(8f))
        val actualCellW = (stripW - padH * 2f) / alts.size
        val stripH = dp(52f)
        val stripBottom = (kr.top - dp(8f)).coerceAtLeast(stripH + dp(2f))
        val stripTop = (stripBottom - stripH).coerceAtLeast(dp(2f))
        val stripLeft = (kr.centerX - stripW * 0.5f).coerceIn(dp(4f), (viewWidth - stripW - dp(4f)).coerceAtLeast(dp(4f)))
        val stripRight = stripLeft + stripW
        val stripRadius = if (theme.isGlassStyle) dp(14f) else dp(10f)

        popupFillPaint.shader = null
        popupFillPaint.color = theme.popupShadow
        popupFillPaint.alpha = theme.popupShadow ushr 24
        reusableRect.set(stripLeft, stripTop + dp(3f), stripRight, stripBottom + dp(3f))
        canvas.drawRoundRect(reusableRect, stripRadius, stripRadius, popupFillPaint)

        reusableRect.set(stripLeft, stripTop, stripRight, stripBottom)
        val pShader = popupShader
        if (pShader != null && theme.isTranslucentStyle) {
            shaderMatrix.setTranslate(0f, stripTop)
            pShader.setLocalMatrix(shaderMatrix)
            popupFillPaint.shader = pShader
            popupFillPaint.alpha = 255
        } else {
            popupFillPaint.shader = null
            popupFillPaint.color = theme.popupBgTop
            popupFillPaint.alpha = 255
        }
        canvas.drawRoundRect(reusableRect, stripRadius, stripRadius, popupFillPaint)
        popupFillPaint.shader = null

        popupStrokePaint.color = theme.popupBorder
        popupStrokePaint.alpha = theme.popupBorder ushr 24
        canvas.drawRoundRect(reusableRect, stripRadius, stripRadius, popupStrokePaint)

        popupTextPaint.textSize = sp(22f)
        val baselineY = (stripTop + stripBottom) * 0.5f - (popupTextPaint.descent() + popupTextPaint.ascent()) * 0.5f
        val pillR = if (theme.isGlassStyle) dp(10f) else dp(7f)

        for (i in alts.indices) {
            val cellLeft = stripLeft + padH + i * actualCellW
            val cellRight = cellLeft + actualCellW
            val isSelected = i == session.selectedAlternateIndex

            if (isSelected) {
                keyPaint.color = theme.popupPillSelectedBg
                keyPaint.alpha = 255
                reusableRect2.set(cellLeft + dp(2f), stripTop + dp(5f), cellRight - dp(2f), stripBottom - dp(5f))
                canvas.drawRoundRect(reusableRect2, pillR, pillR, keyPaint)
                popupTextPaint.color = theme.popupPillSelectedText
            } else {
                popupTextPaint.color = theme.popupText
            }
            popupTextPaint.alpha = 255
            canvas.drawText(alts[i], (cellLeft + cellRight) * 0.5f, baselineY, popupTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        val actionIndex = event.actionIndex
        val pointerId = event.getPointerId(actionIndex)

        when (actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                handlePointerDown(pointerId, event.getX(actionIndex), event.getY(actionIndex))
            }
            MotionEvent.ACTION_MOVE -> {
                for (idx in 0 until event.pointerCount) {
                    val pid = event.getPointerId(idx)
                    handlePointerMove(pid, event.getX(idx), event.getY(idx))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                handlePointerUp(pointerId, event.getX(actionIndex), event.getY(actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelAllActiveSessions()
                invalidate()
            }
        }
        return true
    }

    /**
     * Applies subtle upward finger-pad touch compensation (~1.6dp) inside the key grid,
     * matching iOS touch ergonomics where thumb pads naturally strike slightly below visual aim.
     */
    private fun findKeyWithErgonomicBias(x: Float, y: Float): ComputedKey? {
        val adjustedY = (y - dp(1.6f)).coerceAtLeast(keyAreaTopPx)
        return layoutEngine.findKeyAt(computedKeys, x, adjustedY)
    }

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float) {
        if (isTrackpadActive) return

        // 1. QuickType Suggestion Bar hit
        if (suggestionBarEnabled && y <= suggestionBarBottomPx + dp(2f)) {
            val sideInset = if (theme.isGlassStyle) dp(10f) else dp(4f)
            val usableW = (width - sideInset * 2f).coerceAtLeast(1f)
            val slot = ((x - sideInset) / (usableW / 3f)).toInt().coerceIn(0, 2)
            if (slot < suggestions.size) {
                suggestionPointerId = pointerId
                pressedSuggestionIndex = slot
                invalidate()
            }
            return
        }

        // 2. Bottom Chin (Globe left, Dismiss right)
        if (bottomChinEnabled && y >= chinTopPx - dp(2f)) {
            val action = when {
                x <= width * 0.38f -> KeyCode.GLOBE
                x >= width * 0.62f -> KeyCode.DISMISS
                else -> 0
            }
            if (action != 0) {
                chinPointerId = pointerId
                pressedChinAction = action
                globeLongPressTriggered = false
                callback?.onKeyPressFeedback(action, KeyType.MODIFIER)
                if (action == KeyCode.GLOBE) {
                    mainHandler.removeCallbacks(globeLongPressRunnable)
                    mainHandler.postDelayed(globeLongPressRunnable, GLOBE_LONG_PRESS_MS)
                }
                invalidate()
            }
            return
        }

        val hitKey = findKeyWithErgonomicBias(x, y) ?: return

        // 3. Complete Multi-Touch Rollover: immediately commit earlier character when ANY next key
        //    (letter, space, return, backspace, symbols, emoji) touches down so two-thumb typing
        //    NEVER drops or reorders the last letter of a word!
        if (activePointers.isNotEmpty() && hitKey.spec.code != KeyCode.SHIFT) {
            val iter = activePointers.iterator()
            while (iter.hasNext()) {
                val prev = iter.next()
                if (prev.key.spec.type == KeyType.CHARACTER && !prev.isLongPressAlternates) {
                    commitKey(prev.key)
                    startPopupFade(prev.key)
                    iter.remove()
                }
            }
        }

        val now = SystemClock.uptimeMillis()
        val session = PointerSession(
            pointerId = pointerId,
            key = hitKey,
            downX = x,
            downY = y,
            downTimeMs = now
        )
        activePointers.add(session)
        fadingPopupKey = null

        callback?.onKeyPressFeedback(hitKey.spec.code, hitKey.spec.type)

        when (hitKey.spec.code) {
            KeyCode.BACKSPACE -> {
                backspacePointerId = pointerId
                backspaceDownMs = now
                callback?.onBackspace(deleteWord = false)
                mainHandler.removeCallbacks(backspaceRepeatRunnable)
                mainHandler.postDelayed(backspaceRepeatRunnable, BACKSPACE_INITIAL_DELAY_MS)
            }
            KeyCode.SPACE -> {
                mainHandler.removeCallbacks(spaceTrackpadRunnable)
                mainHandler.postDelayed(spaceTrackpadRunnable, SPACE_TRACKPAD_DELAY_MS)
            }
            KeyCode.SHIFT, KeyCode.SYMBOLS, KeyCode.MORE_SYMBOLS, KeyCode.ALPHA, KeyCode.EMOJI -> {
                callback?.onSpecialKey(hitKey.spec.code)
            }
            KeyCode.CHAR -> {
                if (hitKey.spec.alternates.size > 1) {
                    mainHandler.removeCallbacks(longPressAlternatesRunnable)
                    mainHandler.postDelayed(longPressAlternatesRunnable, LONG_PRESS_ALTERNATES_MS)
                }
            }
        }

        invalidate()
    }

    private fun handlePointerMove(pointerId: Int, x: Float, y: Float) {
        if (pointerId == chinPointerId && pressedChinAction != 0) {
            if (y < chinTopPx - dp(18f)) {
                mainHandler.removeCallbacks(globeLongPressRunnable)
                pressedChinAction = 0
                chinPointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }
            return
        }

        if (isTrackpadActive && pointerId == trackpadPointerId) {
            val dx = x - trackpadLastStepX
            trackpadLastStepX = x
            trackpadAccumX += dx
            val stepPx = dp(TRACKPAD_STEP_DP)
            val steps = (trackpadAccumX / stepPx).toInt()
            if (steps != 0) {
                trackpadAccumX -= steps * stepPx
                callback?.onTrackpadMove(steps)
            }
            return
        }

        val session = activePointers.firstOrNull { it.pointerId == pointerId } ?: return

        if (session.isLongPressAlternates && session.alternates.isNotEmpty()) {
            val alts = session.alternates
            val cellW = dp(38f)
            val padH = dp(6f)
            val stripW = (alts.size * cellW + padH * 2f).coerceAtMost(width - dp(8f))
            val actualCellW = (stripW - padH * 2f) / alts.size
            val stripLeft = (session.key.drawRect.centerX - stripW * 0.5f)
                .coerceIn(dp(4f), (width - stripW - dp(4f)).coerceAtLeast(dp(4f)))
            val newIdx = ((x - (stripLeft + padH)) / actualCellW).toInt().coerceIn(0, alts.lastIndex)
            if (newIdx != session.selectedAlternateIndex) {
                session.selectedAlternateIndex = newIdx
                callback?.onPopupSlideTick()
                invalidate()
            }
            return
        }

        if (session.key.spec.code == KeyCode.BACKSPACE && !session.didSwipeDeleteWord) {
            if (session.downX - x > dp(38f)) {
                session.didSwipeDeleteWord = true
                mainHandler.removeCallbacks(backspaceRepeatRunnable)
                callback?.onBackspace(deleteWord = true)
                callback?.onPopupSlideTick()
            }
            return
        }

        if (session.key.spec.code == KeyCode.SPACE) {
            if (abs(x - session.downX) > dp(16f) || abs(y - session.downY) > dp(20f)) {
                mainHandler.removeCallbacks(spaceTrackpadRunnable)
            }
            return
        }

        // Slide hysteresis on character keys: only switch key when finger deliberately enters
        // the neighboring key's actual drawRect (prevents micro-thumb-drift typos on lift!)
        if (session.key.spec.type == KeyType.CHARACTER) {
            val newKey = findKeyWithErgonomicBias(x, y)
            if (newKey != null &&
                newKey.spec.type == KeyType.CHARACTER &&
                newKey.spec.id != session.key.spec.id &&
                newKey.drawRect.contains(x, (y - dp(1.6f)).coerceAtLeast(keyAreaTopPx))
            ) {
                session.key = newKey
                mainHandler.removeCallbacks(longPressAlternatesRunnable)
                if (newKey.spec.alternates.size > 1) {
                    mainHandler.postDelayed(longPressAlternatesRunnable, LONG_PRESS_ALTERNATES_MS)
                }
                invalidate()
            }
        }
    }

    private fun handlePointerUp(pointerId: Int, x: Float, y: Float) {
        if (pointerId == suggestionPointerId && pressedSuggestionIndex >= 0) {
            val idx = pressedSuggestionIndex
            pressedSuggestionIndex = -1
            suggestionPointerId = MotionEvent.INVALID_POINTER_ID
            if (y <= suggestionBarBottomPx + dp(18f)) {
                suggestions.getOrNull(idx)?.let { item ->
                    callback?.onKeyPressFeedback(KeyCode.CHAR, KeyType.MODIFIER)
                    callback?.onSuggestionPicked(item)
                }
            }
            invalidate()
            return
        }

        if (pointerId == chinPointerId) {
            mainHandler.removeCallbacks(globeLongPressRunnable)
            val action = pressedChinAction
            val wasLongPress = globeLongPressTriggered
            pressedChinAction = 0
            chinPointerId = MotionEvent.INVALID_POINTER_ID
            globeLongPressTriggered = false
            if (!wasLongPress && action != 0 && y >= chinTopPx - dp(16f)) {
                callback?.onSpecialKey(action)
            }
            invalidate()
            return
        }

        if (isTrackpadActive && pointerId == trackpadPointerId) {
            isTrackpadActive = false
            trackpadPointerId = MotionEvent.INVALID_POINTER_ID
            activePointers.removeAll { it.pointerId == pointerId }
            callback?.onTrackpadEnd()
            invalidate()
            return
        }

        if (pointerId == backspacePointerId) {
            backspacePointerId = MotionEvent.INVALID_POINTER_ID
            mainHandler.removeCallbacks(backspaceRepeatRunnable)
        }

        val sessionIdx = activePointers.indexOfFirst { it.pointerId == pointerId }
        if (sessionIdx < 0) return
        val session = activePointers.removeAt(sessionIdx)

        mainHandler.removeCallbacks(longPressAlternatesRunnable)
        mainHandler.removeCallbacks(spaceTrackpadRunnable)

        if (session.isLongPressAlternates && session.alternates.isNotEmpty()) {
            val chosen = session.alternates[session.selectedAlternateIndex.coerceIn(0, session.alternates.lastIndex)]
            callback?.onCharacter(chosen)
        } else {
            when (session.key.spec.code) {
                KeyCode.CHAR -> {
                    commitKey(session.key)
                    startPopupFade(session.key)
                }
                KeyCode.SPACE, KeyCode.RETURN -> {
                    callback?.onSpecialKey(session.key.spec.code)
                }
                else -> Unit
            }
        }

        invalidate()
    }

    private fun commitKey(key: ComputedKey) {
        val label = key.displayLabel(shiftState, returnAction)
        callback?.onCharacter(label)
    }

    private fun startPopupFade(key: ComputedKey) {
        if (!popupEnabled || !key.spec.isPopupEligible) return
        fadingPopupKey = key
        fadingPopupLabel = key.displayLabel(shiftState, returnAction)
        fadingPopupStartMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun isKeyPressed(key: ComputedKey): Boolean {
        for (i in activePointers.indices) {
            if (activePointers[i].key.spec.id == key.spec.id) return true
        }
        return false
    }

    fun cancelAllActiveSessions() {
        activePointers.clear()
        pressedSuggestionIndex = -1
        suggestionPointerId = MotionEvent.INVALID_POINTER_ID
        pressedChinAction = 0
        chinPointerId = MotionEvent.INVALID_POINTER_ID
        globeLongPressTriggered = false
        backspacePointerId = MotionEvent.INVALID_POINTER_ID
        if (isTrackpadActive) {
            isTrackpadActive = false
            trackpadPointerId = MotionEvent.INVALID_POINTER_ID
            callback?.onTrackpadEnd()
        }
        mainHandler.removeCallbacks(longPressAlternatesRunnable)
        mainHandler.removeCallbacks(globeLongPressRunnable)
        mainHandler.removeCallbacks(spaceTrackpadRunnable)
        mainHandler.removeCallbacks(backspaceRepeatRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllActiveSessions()
        mainHandler.removeCallbacks(spacebarBannerClearRunnable)
        cachedBackdropBitmap?.recycle()
        cachedBackdropBitmap = null
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val GLASS_TOP_INSET_DP = 4f
        private const val SUGGESTION_BAR_DP = 43f
        private const val TOP_NO_BAR_PAD_DP = 8f
        private const val KEYS_TOP_PAD_DP = 4.5f
        private const val KEYS_BOTTOM_PAD_DP = 5.5f
        private const val ROW_GAP_DP = 11.5f
        private const val BOTTOM_CHIN_DP = 44f

        private const val POPUP_FADE_DURATION_MS = 60L
        private const val LONG_PRESS_ALTERNATES_MS = 350L
        private const val GLOBE_LONG_PRESS_MS = 380L
        private const val SPACE_TRACKPAD_DELAY_MS = 300L
        private const val SPACEBAR_BANNER_DURATION_MS = 1500L
        private const val TRACKPAD_STEP_DP = 9.0f

        private const val BACKSPACE_INITIAL_DELAY_MS = 320L
        private const val BACKSPACE_CHAR_INTERVAL_MS = 52L
        private const val BACKSPACE_WORD_ACCEL_MS = 1050L
        private const val BACKSPACE_WORD_INTERVAL_MS = 110L
    }
}

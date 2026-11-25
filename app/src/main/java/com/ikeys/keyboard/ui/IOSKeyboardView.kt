package com.ikeys.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
 * Ultra-low-latency hardware-accelerated Canvas keyboard view engineered for the latest iOS 26 look & feel:
 * - Zero-allocation onDraw() & onTouchEvent()
 * - Authentic iOS 26 Liquid Glass (floating rounded tray, optical glass keycaps, specular top sheen & rims),
 *   Frosted Blur (smooth frosted translucency), and Classic Solid styles
 * - Integrated QuickType suggestion bar with glass pill highlights & multi-touch-safe pointer tracking
 * - Smooth Bézier teardrop key preview popups + fading tail animation
 * - Long-press diacritics / alternate character slide-to-select pill bar
 * - Multi-touch rollover typing for fast two-thumb input
 * - Spacebar long-press trackpad cursor control with keycap fade-out & language badge flash
 * - Accelerated backspace repeat (character -> whole word) & swipe-left word delete
 * - iPhone-style bottom chin (Globe tap-to-switch + long-press picker, & Keyboard Dismiss)
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

    private val density = resources.displayMetrics.density
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

    // Typefaces cached once
    private val regularTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // Reusable Paints, Paths, and RectFs (ZERO allocation during onDraw)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backdropSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keySheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        strokeWidth = dp(0.75f)
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

    fun applyConfig(
        theme: IOSKeyboardTheme,
        hardwareBlurActive: Boolean,
        popupEnabled: Boolean,
        suggestionBarEnabled: Boolean,
        bottomChinEnabled: Boolean,
        heightPreset: HeightPreset
    ) {
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

        // Slightly bolder legibility on translucent Liquid Glass (matches iOS 26 optical typography)
        textPaint.typeface = if (theme.isGlassStyle) mediumTypeface else regularTypeface
        suggestionPaint.typeface = if (theme.isGlassStyle) mediumTypeface else regularTypeface
        popupTextPaint.typeface = if (theme.isGlassStyle) mediumTypeface else regularTypeface

        if (needsRelayout) {
            requestLayout()
        }
        recomputeGeometry(width)
        rebuildBackdropPaths(width.toFloat(), height.toFloat())
        invalidate()
    }

    fun setKeyboardState(
        mode: KeyboardMode,
        variant: InputVariant,
        shift: ShiftState,
        returnAction: ReturnAction
    ) {
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
        this.suggestions = items
        invalidate()
    }

    /**
     * Briefly displays the active language name on the spacebar (authentic iOS language-switch indicator).
     */
    fun flashSpacebarLanguage(label: String) {
        spacebarBannerText = label
        spacebarBannerUntilMs = SystemClock.uptimeMillis() + SPACEBAR_BANNER_DURATION_MS
        mainHandler.removeCallbacks(spacebarBannerClearRunnable)
        mainHandler.postDelayed(spacebarBannerClearRunnable, SPACEBAR_BANNER_DURATION_MS)
        invalidate()
    }

    fun calculateDesiredHeightPx(): Int {
        val topInset = if (theme.topCornerRadiusDp > 0f) dp(GLASS_TOP_INSET_DP) else 0f
        val suggestionH = if (suggestionBarEnabled) dp(SUGGESTION_BAR_DP) else dp(TOP_NO_BAR_PAD_DP)
        val rowH = dp(heightPreset.rowHeightDp)
        val rowGap = dp(ROW_GAP_DP)
        val keysTopPad = dp(KEYS_TOP_PAD_DP)
        val keysBottomPad = dp(KEYS_BOTTOM_PAD_DP)
        val chinH = if (bottomChinEnabled) dp(BOTTOM_CHIN_DP) else dp(4f)
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
        rebuildBackdropPaths(w.toFloat(), h.toFloat())
    }

    private fun recomputeGeometry(viewWidth: Int) {
        if (viewWidth <= 0) return
        val topInset = if (theme.topCornerRadiusDp > 0f) dp(GLASS_TOP_INSET_DP) else 0f
        suggestionBarTopPx = topInset
        val suggestionH = if (suggestionBarEnabled) dp(SUGGESTION_BAR_DP) else dp(TOP_NO_BAR_PAD_DP)
        suggestionBarBottomPx = suggestionBarTopPx + suggestionH
        keyAreaTopPx = suggestionBarBottomPx + dp(KEYS_TOP_PAD_DP)

        val rowH = dp(heightPreset.rowHeightDp)
        val rowGap = dp(ROW_GAP_DP)
        chinTopPx = keyAreaTopPx + rowH * 4f + rowGap * 3f + dp(KEYS_BOTTOM_PAD_DP)

        val horizPad = if (theme.isGlassStyle) dp(5f) else dp(3.5f)
        val keyGapX = if (theme.isGlassStyle) dp(5.8f) else dp(5.5f)

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

    private fun rebuildBackdropPaths(w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val topR = dp(theme.topCornerRadiusDp).coerceAtMost(w * 0.15f)
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

        // Luminous top glass curve rim path (left curve -> top edge -> right curve)
        topRimPath.reset()
        val inset = dp(0.6f)
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

        // Cache optical glass backdrop sheen gradient (only allocated on size/theme change)
        if (theme.isTranslucentStyle) {
            backdropSheenPaint.shader = LinearGradient(
                0f,
                0f,
                0f,
                h,
                intArrayOf(theme.backdropGradientTop, 0x00000000, theme.backdropGradientBottom),
                floatArrayOf(0f, 0.42f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            backdropSheenPaint.shader = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Keyboard Backdrop (real blur window shows through when hardwareBlurActive == true)
        drawBackdrop(canvas, w, h)

        // 2. QuickType Suggestion Bar
        if (suggestionBarEnabled) {
            drawSuggestionBar(canvas, w)
        }

        // 3. Keys (iOS 26 Liquid Glass / Frosted Blur / Classic Solid)
        val charCornerR = dp(theme.keyCornerRadiusDp)
        val actionCornerR = dp(theme.actionCornerRadiusDp)
        val shadowOffsetY = if (theme.isGlassStyle) dp(1.35f) else dp(1.1f)
        for (i in computedKeys.indices) {
            val key = computedKeys[i]
            val isPressed = isKeyPressed(key)
            val cornerR = if (key.spec.type == KeyType.CHARACTER) charCornerR else actionCornerR
            drawKey(canvas, key, isPressed, cornerR, shadowOffsetY)
        }

        // 4. Optional iPhone-style Bottom Chin (Globe left, Keyboard Dismiss right)
        if (bottomChinEnabled) {
            drawBottomChin(canvas, w, h)
        }

        // 5. Floating Key Preview Popup or Long-Press Accent Strip (drawn on top, zero clipping!)
        drawActivePopups(canvas, w)
    }

    private fun drawBackdrop(canvas: Canvas, w: Float, h: Float) {
        // When hardware cross-window blur is active on Android 12+, the SoftInputWindow background
        // already renders the rounded-top real-time blur & base tint. We only overlay our subtle
        // optical sheen + specular rim so we never double-darken the glass!
        if (!hardwareBlurActive || !theme.useWindowBlur) {
            bgPaint.color = theme.effectiveBackdropColor(hardwareBlurActive = false)
            if (theme.topCornerRadiusDp > 0f) {
                canvas.drawPath(backdropPath, bgPaint)
            } else {
                canvas.drawRect(0f, 0f, w, h, bgPaint)
            }
        }

        // Subtle optical light sheen gradient on Liquid Glass & Frosted Blur
        if (theme.isTranslucentStyle && backdropSheenPaint.shader != null) {
            canvas.drawPath(backdropPath, backdropSheenPaint)
        }

        // Top specular glass rim (or classic hairline)
        if (theme.topCornerRadiusDp > 0f) {
            topRimPaint.color = theme.topBorderColor
            topRimPaint.strokeWidth = if (theme.isGlassStyle) dp(1.15f) else dp(0.85f)
            canvas.drawPath(topRimPath, topRimPaint)

            // Secondary subtle inner glass reflection band on Liquid Glass
            if (theme.isGlassStyle && (theme.topSpecularHighlight ushr 24) > 0) {
                topRimPaint.color = theme.topSpecularHighlight
                topRimPaint.strokeWidth = dp(0.75f)
                canvas.save()
                canvas.translate(0f, dp(1.2f))
                canvas.drawPath(topRimPath, topRimPaint)
                canvas.restore()
            }
        } else {
            bgPaint.color = theme.topBorderColor
            canvas.drawRect(0f, 0f, w, dp(0.75f), bgPaint)
        }
    }

    private fun drawSuggestionBar(canvas: Canvas, viewWidth: Float) {
        val barTop = suggestionBarTopPx
        val barBottom = suggestionBarBottomPx
        val barH = barBottom - barTop

        if ((theme.suggestionBarBg ushr 24) > 0 && !theme.isTranslucentStyle) {
            bgPaint.color = theme.suggestionBarBg
            canvas.drawRect(0f, barTop + dp(0.75f), viewWidth, barBottom, bgPaint)
        }

        val sideInset = if (theme.isGlassStyle) dp(10f) else dp(4f)
        val usableW = (viewWidth - sideInset * 2f).coerceAtLeast(1f)
        val slotCount = 3
        val slotW = usableW / slotCount
        val dividerTop = barTop + dp(10f)
        val dividerBottom = barBottom - dp(10f)
        val dividerW = dp(0.85f)

        // Vertical iOS hairline dividers between the 3 QuickType slots
        bgPaint.color = theme.suggestionDivider
        val div1X = sideInset + slotW
        val div2X = sideInset + slotW * 2f
        canvas.drawRect(div1X - dividerW * 0.5f, dividerTop, div1X + dividerW * 0.5f, dividerBottom, bgPaint)
        canvas.drawRect(div2X - dividerW * 0.5f, dividerTop, div2X + dividerW * 0.5f, dividerBottom, bgPaint)

        suggestionPaint.textSize = sp(15.5f)
        val textCenterY = barTop + barH * 0.5f - (suggestionPaint.descent() + suggestionPaint.ascent()) * 0.5f
        val pillRadius = if (theme.isGlassStyle) dp(11f) else dp(7f)

        for (i in 0 until slotCount) {
            val item = suggestions.getOrNull(i) ?: continue
            val left = sideInset + i * slotW
            val right = left + slotW

            // Highlight glass pill when pressed or active autocorrect candidate
            if (pressedSuggestionIndex == i || item.isAutoCorrectCandidate) {
                keyPaint.color = theme.suggestionHighlightBg
                reusableRect.set(left + dp(5f), barTop + dp(5f), right - dp(5f), barBottom - dp(5f))
                canvas.drawRoundRect(reusableRect, pillRadius, pillRadius, keyPaint)

                if (theme.isGlassStyle && (theme.suggestionHighlightBorder ushr 24) > 0) {
                    keyBorderPaint.color = theme.suggestionHighlightBorder
                    keyBorderPaint.strokeWidth = dp(0.7f)
                    canvas.drawRoundRect(reusableRect, pillRadius, pillRadius, keyBorderPaint)
                }
            }

            suggestionPaint.color = theme.suggestionText
            val label = if (item.isLiteral) "\u201C${item.text}\u201D" else item.text
            val maxChars = ((slotW - dp(16f)) / suggestionPaint.measureText("m")).toInt().coerceAtLeast(6)
            val ellipsized = if (label.length > maxChars + 2) label.take(maxChars) + "…" else label
            canvas.drawText(ellipsized, (left + right) * 0.5f, textCenterY, suggestionPaint)
        }
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

        val shadowColor: Int
        val faceColor: Int
        val topSheenColor: Int
        val borderColor: Int

        when {
            isBlueReturn -> {
                shadowColor = theme.keyActionBlueShadow
                faceColor = if (isPressed) theme.keyActionBlueBgPressed else theme.keyActionBlueBg
                topSheenColor = theme.keyActionBlueBgTop
                borderColor = theme.keyActionBlueBorder
            }
            isShiftActive -> {
                shadowColor = theme.keyCharShadow
                faceColor = theme.keyShiftActiveBg
                topSheenColor = theme.keyCharBgTop
                borderColor = theme.keyCharBorder
            }
            spec.type == KeyType.CHARACTER || spec.type == KeyType.SPACE -> {
                shadowColor = theme.keyCharShadow
                faceColor = if (isPressed) theme.keyCharBgPressed else theme.keyCharBg
                topSheenColor = theme.keyCharBgTop
                borderColor = theme.keyCharBorder
            }
            else -> {
                shadowColor = theme.keySpecialShadow
                faceColor = if (isPressed) theme.keySpecialBgPressed else theme.keySpecialBg
                topSheenColor = theme.keySpecialBgTop
                borderColor = theme.keySpecialBorder
            }
        }

        // Trackpad mode fades keycaps out smoothly
        val alphaScale = if (isTrackpadActive) theme.trackpadKeyAlpha / 255f else 1f

        // 1. Keycap drop shadow (subtle soft optical depth in Liquid Glass, crisp 1dp in Classic)
        shadowPaint.color = shadowColor
        if (isTrackpadActive) {
            shadowPaint.alpha = ((shadowColor ushr 24) * alphaScale).toInt()
        }
        reusableRect.set(r.left, r.top + shadowOffsetY, r.right, r.bottom + shadowOffsetY)
        canvas.drawRoundRect(reusableRect, cornerR, cornerR, shadowPaint)

        // 2. Main keycap surface
        keyPaint.color = faceColor
        if (isTrackpadActive) {
            keyPaint.alpha = ((faceColor ushr 24) * alphaScale).toInt()
        }
        reusableRect.set(r.left, r.top, r.right, r.bottom)
        canvas.drawRoundRect(reusableRect, cornerR, cornerR, keyPaint)

        // 3. Liquid Glass optical refraction highlight & specular edge rim
        if (theme.isGlassStyle && !isTrackpadActive) {
            // Subtle glossy top-half glass sheen
            if (!isPressed && (topSheenColor ushr 24) > 0) {
                keySheenPaint.color = topSheenColor
                keySheenPaint.alpha = ((topSheenColor ushr 24) * 0.28f).toInt()
                val sheenInset = dp(0.8f)
                reusableRect2.set(
                    r.left + sheenInset,
                    r.top + sheenInset,
                    r.right - sheenInset,
                    r.top + r.height * 0.48f
                )
                canvas.drawRoundRect(reusableRect2, (cornerR - sheenInset).coerceAtLeast(2f), (cornerR - sheenInset).coerceAtLeast(2f), keySheenPaint)
            }

            // Luminous specular glass rim stroke around keycap
            if ((borderColor ushr 24) > 0) {
                keyBorderPaint.color = borderColor
                keyBorderPaint.strokeWidth = dp(0.78f)
                val halfStroke = dp(0.39f)
                reusableRect2.set(
                    r.left + halfStroke,
                    r.top + halfStroke,
                    r.right - halfStroke,
                    r.bottom - halfStroke
                )
                canvas.drawRoundRect(reusableRect2, cornerR, cornerR, keyBorderPaint)
            }

            // Crisp specular light catch along top key edge
            val topRimAlpha = (theme.keyCharTopRim ushr 24)
            if (topRimAlpha > 0 && (spec.type == KeyType.CHARACTER || spec.type == KeyType.SPACE || isBlueReturn)) {
                keyBorderPaint.color = if (isBlueReturn) theme.keyActionBlueBorder else theme.keyCharTopRim
                keyBorderPaint.strokeWidth = dp(0.85f)
                val lineInset = cornerR * 0.72f
                val lineY = r.top + dp(0.55f)
                if (r.right - lineInset > r.left + lineInset) {
                    canvas.drawLine(r.left + lineInset, lineY, r.right - lineInset, lineY, keyBorderPaint)
                }
            }
        } else if (theme.isTranslucentStyle && !isTrackpadActive && (borderColor ushr 24) > 0) {
            // Subtle frosted edge definition
            keyBorderPaint.color = borderColor
            keyBorderPaint.strokeWidth = dp(0.6f)
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
                    size = dp(20f),
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
                    size = dp(21f),
                    isPressed = isPressed,
                    fgColor = theme.keySpecialText,
                    bgColor = faceColor,
                    paint = iconPaint,
                    path = reusablePath
                )
            }
            KeyCode.EMOJI -> {
                IOSVectorIcons.drawEmojiSmileyIcon(
                    canvas = canvas,
                    cx = cx,
                    cy = cy,
                    size = dp(21f),
                    color = theme.keySpecialText,
                    paint = iconPaint,
                    rect = reusableRect2
                )
            }
            KeyCode.RETURN -> {
                // Latest iOS 26 uses the sleek return.left arrow icon for standard Return,
                // and crisp action text for Search / Send / Go / Done / Next
                if (returnAction == ReturnAction.RETURN) {
                    IOSVectorIcons.drawReturnArrowIcon(
                        canvas = canvas,
                        cx = cx,
                        cy = cy,
                        size = dp(21f),
                        color = theme.keySpecialText,
                        paint = iconPaint,
                        path = reusablePath
                    )
                } else {
                    specialTextPaint.textSize = sp(15.5f)
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
                specialTextPaint.textSize = sp(15f)
                specialTextPaint.color = theme.keyCharText
                specialTextPaint.alpha = if (banner != null) 220 else 175
                val baselineY = cy - (specialTextPaint.descent() + specialTextPaint.ascent()) * 0.5f
                canvas.drawText(label, cx, baselineY, specialTextPaint)
                specialTextPaint.alpha = 255
            }
            else -> {
                val label = key.displayLabel(shiftState, returnAction)
                if (spec.type == KeyType.CHARACTER) {
                    val isUpperLetter = shiftState.isUpperCase && label.length == 1 && label[0].isLetter()
                    textPaint.textSize = if (isUpperLetter) sp(21f) else sp(22.8f)
                    textPaint.color = theme.keyCharText
                    val opticalOffset = if (!isUpperLetter && label.length == 1 && label[0].isLowerCase()) dp(-1.1f) else dp(0.2f)
                    val baselineY = cy - (textPaint.descent() + textPaint.ascent()) * 0.5f + opticalOffset
                    canvas.drawText(label, cx, baselineY, textPaint)
                } else {
                    specialTextPaint.textSize = sp(15.2f)
                    specialTextPaint.color = theme.keySpecialText
                    val baselineY = cy - (specialTextPaint.descent() + specialTextPaint.ascent()) * 0.5f
                    canvas.drawText(label, cx, baselineY, specialTextPaint)
                }
            }
        }
    }

    private fun drawBottomChin(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
        val chinCy = (chinTopPx + viewHeight) * 0.5f - dp(1f)
        val iconSize = dp(24f)
        val marginX = dp(34f)
        val pillR = dp(18f)

        // Left: Globe icon (tap to switch keyboard, long-press for picker)
        if (pressedChinAction == KeyCode.GLOBE) {
            keyPaint.color = theme.chinPressPillBg
            canvas.drawCircle(marginX, chinCy, pillR, keyPaint)
        }
        iconPaint.alpha = if (pressedChinAction == KeyCode.GLOBE) 180 else 255
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
        iconPaint.alpha = if (pressedChinAction == KeyCode.DISMISS) 180 else 255
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

        // Draw fading release popup first if active
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

        // Draw active pressed key popup or long-press alternates strip
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

    /**
     * Draws the signature connected iOS teardrop key magnifier balloon using smooth cubic Béziers.
     */
    private fun drawTeardropPopup(
        canvas: Canvas,
        key: ComputedKey,
        label: String,
        viewWidth: Float,
        alpha: Int
    ) {
        val kr = key.drawRect
        val headWidth = (kr.width * 1.60f).coerceAtLeast(dp(50f))
        val headHeight = dp(54f)
        val stemNeckH = dp(10.5f)
        val rHead = if (theme.isGlassStyle) dp(13f) else dp(10f)
        val rBase = dp(theme.keyCornerRadiusDp)

        var headLeft = kr.centerX - headWidth * 0.5f
        when (key.spec.edgeBias) {
            EdgeBias.LEFT -> headLeft = kr.left
            EdgeBias.RIGHT -> headLeft = kr.right - headWidth
            EdgeBias.NONE -> Unit
        }
        headLeft = headLeft.coerceIn(dp(3f), (viewWidth - headWidth - dp(3f)).coerceAtLeast(dp(3f)))
        val headRight = headLeft + headWidth

        val headBottom = kr.top - stemNeckH
        val headTop = (headBottom - headHeight).coerceAtLeast(dp(2f))
        val baseTop = kr.top
        val baseBottom = kr.bottom

        reusablePath.reset()
        reusablePath.moveTo(headLeft + rHead, headTop)
        reusablePath.lineTo(headRight - rHead, headTop)
        reusablePath.quadTo(headRight, headTop, headRight, headTop + rHead)
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
        reusablePath.lineTo(headLeft, headTop + rHead)
        reusablePath.quadTo(headLeft, headTop, headLeft + rHead, headTop)
        reusablePath.close()

        // Soft drop shadow
        popupFillPaint.color = theme.popupShadow
        popupFillPaint.alpha = ((theme.popupShadow ushr 24) * alpha) / 255
        canvas.save()
        canvas.translate(0f, dp(2.5f))
        canvas.drawPath(reusablePath, popupFillPaint)
        canvas.restore()

        // Main popup body
        popupFillPaint.color = theme.popupBg
        popupFillPaint.alpha = ((theme.popupBg ushr 24) * alpha) / 255
        canvas.drawPath(reusablePath, popupFillPaint)

        // Crisp luminous outline
        popupStrokePaint.color = theme.popupBorder
        popupStrokePaint.alpha = ((theme.popupBorder ushr 24) * alpha) / 255
        canvas.drawPath(reusablePath, popupStrokePaint)

        // Magnified character centered in head
        popupTextPaint.color = theme.popupText
        popupTextPaint.alpha = alpha
        popupTextPaint.textSize = sp(33f)
        val textCenterY = (headTop + headBottom) * 0.5f - (popupTextPaint.descent() + popupTextPaint.ascent()) * 0.5f
        canvas.drawText(label, (headLeft + headRight) * 0.5f, textCenterY, popupTextPaint)
    }

    /**
     * Draws the iOS long-press accent / diacritic horizontal glass pill tray with blue active pill.
     */
    private fun drawAlternatesStrip(canvas: Canvas, session: PointerSession, viewWidth: Float) {
        val kr = session.key.drawRect
        val alts = session.alternates
        val cellW = dp(38f)
        val padH = dp(6f)
        val stripW = (alts.size * cellW + padH * 2f).coerceAtMost(viewWidth - dp(8f))
        val actualCellW = (stripW - padH * 2f) / alts.size
        val stripH = dp(52f)
        val stripBottom = kr.top - dp(8f)
        val stripTop = (stripBottom - stripH).coerceAtLeast(dp(2f))
        val stripLeft = (kr.centerX - stripW * 0.5f).coerceIn(dp(4f), (viewWidth - stripW - dp(4f)).coerceAtLeast(dp(4f)))
        val stripRight = stripLeft + stripW
        val stripRadius = if (theme.isGlassStyle) dp(14f) else dp(10f)

        // Soft shadow
        popupFillPaint.color = theme.popupShadow
        popupFillPaint.alpha = theme.popupShadow ushr 24
        reusableRect.set(stripLeft, stripTop + dp(3f), stripRight, stripBottom + dp(3f))
        canvas.drawRoundRect(reusableRect, stripRadius, stripRadius, popupFillPaint)

        // Tray background
        popupFillPaint.color = theme.popupBg
        popupFillPaint.alpha = theme.popupBg ushr 24
        reusableRect.set(stripLeft, stripTop, stripRight, stripBottom)
        canvas.drawRoundRect(reusableRect, stripRadius, stripRadius, popupFillPaint)

        popupStrokePaint.color = theme.popupBorder
        popupStrokePaint.alpha = theme.popupBorder ushr 24
        canvas.drawRoundRect(reusableRect, stripRadius, stripRadius, popupStrokePaint)

        popupTextPaint.textSize = sp(22f)
        val baselineY = (stripTop + stripBottom) * 0.5f - (popupTextPaint.descent() + popupTextPaint.ascent()) * 0.5f
        val pillR = if (theme.isGlassStyle) dp(9f) else dp(7f)

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
                val x = event.getX(actionIndex)
                val y = event.getY(actionIndex)
                handlePointerDown(pointerId, x, y)
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

    private fun handlePointerDown(pointerId: Int, x: Float, y: Float) {
        if (isTrackpadActive) return

        // 1. Check QuickType Suggestion Bar hit (pointer-isolated so multi-touch never interferes)
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

        // 2. Check optional Bottom Chin (Globe left, Dismiss right — center dead-zone prevents accidental triggers)
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

        val hitKey = layoutEngine.findKeyAt(computedKeys, x, y) ?: return

        // 3. Multi-touch Rollover: if user starts pressing a new character while another character key is still down,
        // immediately commit the earlier character so fast 2-thumb typing never drops a letter!
        if (hitKey.spec.type == KeyType.CHARACTER && activePointers.isNotEmpty()) {
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

        // Instant tactile & audio feedback on touch down
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
        // Cancel globe long-press if finger slides away from chin
        if (pointerId == chinPointerId && pressedChinAction != 0) {
            if (y < chinTopPx - dp(18f)) {
                mainHandler.removeCallbacks(globeLongPressRunnable)
                pressedChinAction = 0
                chinPointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
            }
            return
        }

        // Spacebar trackpad cursor glide
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

        // Long-press alternates slide selection
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

        // Swipe-left from Backspace deletes whole word
        if (session.key.spec.code == KeyCode.BACKSPACE && !session.didSwipeDeleteWord) {
            if (session.downX - x > dp(38f)) {
                session.didSwipeDeleteWord = true
                mainHandler.removeCallbacks(backspaceRepeatRunnable)
                callback?.onBackspace(deleteWord = true)
                callback?.onPopupSlideTick()
            }
            return
        }

        // Cancel spacebar trackpad timer if finger moves noticeably off spacebar
        if (session.key.spec.code == KeyCode.SPACE) {
            if (abs(x - session.downX) > dp(16f) || abs(y - session.downY) > dp(20f)) {
                mainHandler.removeCallbacks(spaceTrackpadRunnable)
            }
            return
        }

        // Smooth finger slide between character keys before release (authentic iOS slide-to-correct)
        if (session.key.spec.type == KeyType.CHARACTER) {
            val newKey = layoutEngine.findKeyAt(computedKeys, x, y)
            if (newKey != null && newKey.spec.type == KeyType.CHARACTER && newKey.spec.id != session.key.spec.id) {
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
        // Suggestion bar tap release (matched strictly by pointerId)
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

        // Bottom chin tap release (matched strictly by pointerId)
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

        // Exit spacebar trackpad mode
        if (isTrackpadActive && pointerId == trackpadPointerId) {
            isTrackpadActive = false
            trackpadPointerId = MotionEvent.INVALID_POINTER_ID
            activePointers.removeAll { it.pointerId == pointerId }
            callback?.onTrackpadEnd()
            invalidate()
            return
        }

        // Stop backspace repeat
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
                else -> Unit // Modifiers & Backspace already fired on touch-down
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
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val GLASS_TOP_INSET_DP = 5f
        private const val SUGGESTION_BAR_DP = 42f
        private const val TOP_NO_BAR_PAD_DP = 8f
        private const val KEYS_TOP_PAD_DP = 5f
        private const val KEYS_BOTTOM_PAD_DP = 6f
        private const val ROW_GAP_DP = 10.5f
        private const val BOTTOM_CHIN_DP = 42f

        private const val POPUP_FADE_DURATION_MS = 70L
        private const val LONG_PRESS_ALTERNATES_MS = 360L
        private const val GLOBE_LONG_PRESS_MS = 380L
        private const val SPACE_TRACKPAD_DELAY_MS = 310L
        private const val SPACEBAR_BANNER_DURATION_MS = 1500L
        private const val TRACKPAD_STEP_DP = 9.5f

        private const val BACKSPACE_INITIAL_DELAY_MS = 340L
        private const val BACKSPACE_CHAR_INTERVAL_MS = 58L
        private const val BACKSPACE_WORD_ACCEL_MS = 1150L
        private const val BACKSPACE_WORD_INTERVAL_MS = 115L
    }
}

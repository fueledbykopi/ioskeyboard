package com.ikeys.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
 * Ultra-low-latency hardware-accelerated Canvas keyboard view engineered for authentic iOS look & feel:
 * - Zero-allocation onDraw() & onTouchEvent()
 * - Integrated QuickType suggestion bar (prevents popup clipping on top row with zero WindowManager IPC)
 * - Smooth Bézier teardrop key preview popups + fading tail animation
 * - Long-press diacritics / alternate character slide-to-select pill bar
 * - Multi-touch rollover typing for fast two-thumb input
 * - Spacebar long-press trackpad cursor control with keycap fade-out
 * - Accelerated backspace repeat (character -> whole word) & swipe-left word delete
 * - Optional iPhone-style bottom chin (Globe & Keyboard Dismiss)
 */
class IOSKeyboardView(context: Context) : View(context) {

    interface Callback {
        fun onCharacter(text: String)
        fun onSpecialKey(code: Int)
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
    private var theme: IOSKeyboardTheme = IOSKeyboardTheme.Light
    private var keyboardMode: KeyboardMode = KeyboardMode.ALPHA
    private var inputVariant: InputVariant = InputVariant.NORMAL
    private var shiftState: ShiftState = ShiftState.OFF
    private var returnAction: ReturnAction = ReturnAction.RETURN
    private var suggestions: List<SuggestionItem> = emptyList()

    private var popupEnabled: Boolean = true
    private var suggestionBarEnabled: Boolean = true
    private var bottomChinEnabled: Boolean = true
    private var heightPreset: HeightPreset = HeightPreset.STANDARD

    private var computedKeys: List<ComputedKey> = emptyList()
    private var keyAreaTopPx: Float = 0f
    private var chinTopPx: Float = 0f

    // Reusable Paints, Paths, and RectFs (ZERO allocation during onDraw)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val specialTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val suggestionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val popupFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
    }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val reusableRect = RectF()
    private val reusableRect2 = RectF()
    private val reusablePath = Path()

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

    // Suggestion bar press feedback
    private var pressedSuggestionIndex: Int = -1
    private var pressedChinAction: Int = 0 // 0=none, KeyCode.GLOBE, KeyCode.DISMISS

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

    private val spaceTrackpadRunnable = Runnable {
        val session = activePointers.firstOrNull { it.key.spec.code == KeyCode.SPACE } ?: return@Runnable
        isTrackpadActive = true
        trackpadPointerId = session.pointerId
        trackpadLastStepX = session.downX
        trackpadAccumX = 0f
        callback?.onTrackpadStart()
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
        popupEnabled: Boolean,
        suggestionBarEnabled: Boolean,
        bottomChinEnabled: Boolean,
        heightPreset: HeightPreset
    ) {
        val needsRelayout = this.suggestionBarEnabled != suggestionBarEnabled ||
            this.bottomChinEnabled != bottomChinEnabled ||
            this.heightPreset != heightPreset

        this.theme = theme
        this.popupEnabled = popupEnabled
        this.suggestionBarEnabled = suggestionBarEnabled
        this.bottomChinEnabled = bottomChinEnabled
        this.heightPreset = heightPreset

        if (needsRelayout) {
            requestLayout()
        }
        recomputeGeometry(width)
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

    fun calculateDesiredHeightPx(): Int {
        val suggestionH = if (suggestionBarEnabled) dp(SUGGESTION_BAR_DP) else dp(TOP_NO_BAR_PAD_DP)
        val rowH = dp(heightPreset.rowHeightDp)
        val rowGap = dp(ROW_GAP_DP)
        val keysTopPad = dp(KEYS_TOP_PAD_DP)
        val keysBottomPad = dp(KEYS_BOTTOM_PAD_DP)
        val chinH = if (bottomChinEnabled) dp(BOTTOM_CHIN_DP) else 0f
        return (suggestionH + keysTopPad + rowH * 4f + rowGap * 3f + keysBottomPad + chinH).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = calculateDesiredHeightPx()
        setMeasuredDimension(desiredWidth, desiredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGeometry(w)
    }

    private fun recomputeGeometry(viewWidth: Int) {
        if (viewWidth <= 0) return
        val suggestionH = if (suggestionBarEnabled) dp(SUGGESTION_BAR_DP) else dp(TOP_NO_BAR_PAD_DP)
        keyAreaTopPx = suggestionH + dp(KEYS_TOP_PAD_DP)
        val rowH = dp(heightPreset.rowHeightDp)
        val rowGap = dp(ROW_GAP_DP)
        chinTopPx = keyAreaTopPx + rowH * 4f + rowGap * 3f + dp(KEYS_BOTTOM_PAD_DP)

        val rows = IOSKeyboardLayout.rowsFor(keyboardMode, inputVariant)
        computedKeys = layoutEngine.computeKeys(
            rows = rows,
            metrics = KeyboardLayoutEngine.Metrics(
                width = viewWidth.toFloat(),
                topOffset = keyAreaTopPx,
                rowHeight = rowH,
                horizontalPadding = dp(3f),
                keyHorizontalGap = dp(5.5f),
                rowVerticalGap = rowGap
            )
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Keyboard Backdrop
        bgPaint.color = theme.keyboardBg
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Subtle 0.7dp iOS top hairline separator
        bgPaint.color = theme.topBorderColor
        canvas.drawRect(0f, 0f, w, dp(0.75f), bgPaint)

        // 2. QuickType Suggestion Bar
        if (suggestionBarEnabled) {
            drawSuggestionBar(canvas, w)
        }

        // 3. Keys
        val cornerR = dp(KEY_CORNER_RADIUS_DP)
        val shadowOffsetY = dp(1.1f)
        for (i in computedKeys.indices) {
            val key = computedKeys[i]
            val isPressed = isKeyPressed(key)
            drawKey(canvas, key, isPressed, cornerR, shadowOffsetY)
        }

        // 4. Optional iPhone-style Bottom Chin (Globe left, Keyboard Dismiss right)
        if (bottomChinEnabled) {
            drawBottomChin(canvas, w, h)
        }

        // 5. Floating Key Preview Popup or Long-Press Accent Strip (drawn on top, zero clipping!)
        drawActivePopups(canvas, w)
    }

    private fun drawSuggestionBar(canvas: Canvas, viewWidth: Float) {
        val barH = dp(SUGGESTION_BAR_DP)
        bgPaint.color = theme.suggestionBarBg
        canvas.drawRect(0f, dp(0.75f), viewWidth, barH, bgPaint)

        val slotCount = 3
        val slotW = viewWidth / slotCount
        val dividerTop = dp(9f)
        val dividerBottom = barH - dp(9f)
        val dividerW = dp(0.8f)

        // Vertical iOS hairline dividers between the 3 QuickType slots
        bgPaint.color = theme.suggestionDivider
        canvas.drawRect(slotW - dividerW * 0.5f, dividerTop, slotW + dividerW * 0.5f, dividerBottom, bgPaint)
        canvas.drawRect(slotW * 2f - dividerW * 0.5f, dividerTop, slotW * 2f + dividerW * 0.5f, dividerBottom, bgPaint)

        suggestionPaint.textSize = sp(15.5f)
        val textCenterY = barH * 0.5f - (suggestionPaint.descent() + suggestionPaint.ascent()) * 0.5f

        for (i in 0 until slotCount) {
            val item = suggestions.getOrNull(i) ?: continue
            val left = i * slotW
            val right = left + slotW

            // Highlight background when pressed or active autocorrect candidate
            if (pressedSuggestionIndex == i || item.isAutoCorrectCandidate) {
                keyPaint.color = theme.suggestionHighlightBg
                reusableRect.set(left + dp(5f), dp(5f), right - dp(5f), barH - dp(5f))
                canvas.drawRoundRect(reusableRect, dp(6f), dp(6f), keyPaint)
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

        // Shadow color & face color
        val shadowColor: Int
        val faceColor: Int
        when {
            isBlueReturn -> {
                shadowColor = theme.keyActionBlueShadow
                faceColor = if (isPressed) theme.keyActionBlueBgPressed else theme.keyActionBlueBg
            }
            isShiftActive -> {
                shadowColor = theme.keyCharShadow
                faceColor = theme.keyShiftActiveBg
            }
            spec.type == KeyType.CHARACTER || spec.type == KeyType.SPACE -> {
                shadowColor = theme.keyCharShadow
                faceColor = if (isPressed) theme.keyCharBgPressed else theme.keyCharBg
            }
            else -> {
                shadowColor = theme.keySpecialShadow
                faceColor = if (isPressed) theme.keySpecialBgPressed else theme.keySpecialBg
            }
        }

        // Signature iOS crisp 1dp bottom drop shadow
        shadowPaint.color = shadowColor
        shadowPaint.alpha = if (isTrackpadActive) theme.trackpadKeyAlpha else 255
        reusableRect.set(r.left, r.top + shadowOffsetY, r.right, r.bottom + shadowOffsetY)
        canvas.drawRoundRect(reusableRect, cornerR, cornerR, shadowPaint)

        // Keycap surface
        keyPaint.color = faceColor
        keyPaint.alpha = if (isTrackpadActive) theme.trackpadKeyAlpha else 255
        reusableRect.set(r.left, r.top, r.right, r.bottom)
        canvas.drawRoundRect(reusableRect, cornerR, cornerR, keyPaint)

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
            else -> {
                val label = key.displayLabel(shiftState, returnAction)
                if (spec.type == KeyType.CHARACTER) {
                    val isUpperLetter = shiftState.isUpperCase && label.length == 1 && label[0].isLetter()
                    textPaint.textSize = if (isUpperLetter) sp(21.5f) else sp(23f)
                    textPaint.color = theme.keyCharText
                    // Subtle optical vertical centering (lowercase sits slightly higher visually)
                    val opticalOffset = if (!isUpperLetter && label.length == 1 && label[0].isLowerCase()) dp(-1.2f) else dp(0.2f)
                    val baselineY = cy - (textPaint.descent() + textPaint.ascent()) * 0.5f + opticalOffset
                    canvas.drawText(label, cx, baselineY, textPaint)
                } else {
                    specialTextPaint.textSize = sp(15.5f)
                    specialTextPaint.color = if (isBlueReturn) theme.keyActionBlueText else theme.keySpecialText
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

        // Left: Globe icon (switch keyboard / IME)
        val globeAlpha = if (pressedChinAction == KeyCode.GLOBE) 120 else 255
        iconPaint.alpha = globeAlpha
        IOSVectorIcons.drawGlobeIcon(
            canvas = canvas,
            cx = marginX,
            cy = chinCy,
            size = iconSize,
            color = theme.chinIconColor,
            paint = iconPaint,
            rect = reusableRect2
        )

        // Right: Keyboard dismiss icon
        val dismissAlpha = if (pressedChinAction == KeyCode.DISMISS) 120 else 255
        iconPaint.alpha = dismissAlpha
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
        val headWidth = (kr.width * 1.58f).coerceAtLeast(dp(50f))
        val headHeight = dp(54f)
        val stemNeckH = dp(11f)
        val rHead = dp(10f)
        val rBase = dp(KEY_CORNER_RADIUS_DP)

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
        // Start top-left of head after corner radius
        reusablePath.moveTo(headLeft + rHead, headTop)
        // Top edge
        reusablePath.lineTo(headRight - rHead, headTop)
        reusablePath.quadTo(headRight, headTop, headRight, headTop + rHead)
        // Right side of head down to neck
        reusablePath.lineTo(headRight, headBottom - rHead)
        // Smooth S-curve neck merging right edge of head into right edge of keycap
        reusablePath.cubicTo(
            headRight, headBottom + stemNeckH * 0.35f,
            kr.right, baseTop - stemNeckH * 0.35f,
            kr.right, baseTop + rBase
        )
        // Right edge of base keycap
        reusablePath.lineTo(kr.right, baseBottom - rBase)
        reusablePath.quadTo(kr.right, baseBottom, kr.right - rBase, baseBottom)
        // Bottom edge of base keycap
        reusablePath.lineTo(kr.left + rBase, baseBottom)
        reusablePath.quadTo(kr.left, baseBottom, kr.left, baseBottom - rBase)
        // Left edge of base keycap up to neck
        reusablePath.lineTo(kr.left, baseTop + rBase)
        // Smooth S-curve neck merging left edge of keycap into left edge of head
        reusablePath.cubicTo(
            kr.left, baseTop - stemNeckH * 0.35f,
            headLeft, headBottom + stemNeckH * 0.35f,
            headLeft, headBottom - rHead
        )
        // Left side of head
        reusablePath.lineTo(headLeft, headTop + rHead)
        reusablePath.quadTo(headLeft, headTop, headLeft + rHead, headTop)
        reusablePath.close()

        // Soft drop shadow
        popupFillPaint.color = theme.popupShadow
        popupFillPaint.alpha = ((theme.popupShadow ushr 24) * alpha) / 255
        canvas.save()
        canvas.translate(0f, dp(2f))
        canvas.drawPath(reusablePath, popupFillPaint)
        canvas.restore()

        // Main popup body
        popupFillPaint.color = theme.popupBg
        popupFillPaint.alpha = alpha
        canvas.drawPath(reusablePath, popupFillPaint)

        // Subtle crisp outline
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
     * Draws the iOS long-press accent / diacritic horizontal pill tray with blue active pill.
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
        val stripRadius = dp(10f)

        // Soft shadow
        popupFillPaint.color = theme.popupShadow
        popupFillPaint.alpha = theme.popupShadow ushr 24
        reusableRect.set(stripLeft, stripTop + dp(2.5f), stripRight, stripBottom + dp(2.5f))
        canvas.drawRoundRect(reusableRect, stripRadius, stripRadius, popupFillPaint)

        // Tray background
        popupFillPaint.color = theme.popupBg
        popupFillPaint.alpha = 255
        reusableRect.set(stripLeft, stripTop, stripRight, stripBottom)
        canvas.drawRoundRect(reusableRect, stripRadius, stripRadius, popupFillPaint)

        popupStrokePaint.color = theme.popupBorder
        popupStrokePaint.alpha = theme.popupBorder ushr 24
        canvas.drawRoundRect(reusableRect, stripRadius, stripRadius, popupStrokePaint)

        popupTextPaint.textSize = sp(22f)
        val baselineY = (stripTop + stripBottom) * 0.5f - (popupTextPaint.descent() + popupTextPaint.ascent()) * 0.5f

        for (i in alts.indices) {
            val cellLeft = stripLeft + padH + i * actualCellW
            val cellRight = cellLeft + actualCellW
            val isSelected = i == session.selectedAlternateIndex

            if (isSelected) {
                keyPaint.color = theme.popupPillSelectedBg
                keyPaint.alpha = 255
                reusableRect2.set(cellLeft + dp(2f), stripTop + dp(5f), cellRight - dp(2f), stripBottom - dp(5f))
                canvas.drawRoundRect(reusableRect2, dp(7f), dp(7f), keyPaint)
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

        // 1. Check QuickType Suggestion Bar hit
        if (suggestionBarEnabled && y < dp(SUGGESTION_BAR_DP)) {
            val slotW = width / 3f
            val slot = (x / slotW).toInt().coerceIn(0, 2)
            if (slot < suggestions.size) {
                pressedSuggestionIndex = slot
                invalidate()
            }
            return
        }

        // 2. Check optional Bottom Chin (Globe / Dismiss)
        if (bottomChinEnabled && y >= chinTopPx) {
            pressedChinAction = if (x < width * 0.5f) KeyCode.GLOBE else KeyCode.DISMISS
            callback?.onKeyPressFeedback(pressedChinAction, KeyType.MODIFIER)
            invalidate()
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

        // Instant tactile & audio feedback on touch down (crucial for iOS zero-latency feel!)
        callback?.onKeyPressFeedback(hitKey.spec.code, hitKey.spec.type)

        when (hitKey.spec.code) {
            KeyCode.BACKSPACE -> {
                backspacePointerId = pointerId
                backspaceDownMs = now
                // Immediate first deletion on press down (matches native iOS responsiveness)
                callback?.onBackspace(deleteWord = false)
                mainHandler.removeCallbacks(backspaceRepeatRunnable)
                mainHandler.postDelayed(backspaceRepeatRunnable, BACKSPACE_INITIAL_DELAY_MS)
            }
            KeyCode.SPACE -> {
                mainHandler.removeCallbacks(spaceTrackpadRunnable)
                mainHandler.postDelayed(spaceTrackpadRunnable, SPACE_TRACKPAD_DELAY_MS)
            }
            KeyCode.SHIFT, KeyCode.SYMBOLS, KeyCode.MORE_SYMBOLS, KeyCode.ALPHA, KeyCode.EMOJI -> {
                // Modifier keys fire immediately on press down for crisp speed
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
        // Suggestion bar tap release
        if (pressedSuggestionIndex >= 0) {
            val idx = pressedSuggestionIndex
            pressedSuggestionIndex = -1
            if (y < dp(SUGGESTION_BAR_DP) + dp(12f)) {
                suggestions.getOrNull(idx)?.let { item ->
                    callback?.onKeyPressFeedback(KeyCode.CHAR, KeyType.MODIFIER)
                    callback?.onSuggestionPicked(item)
                }
            }
            invalidate()
            return
        }

        // Bottom chin tap release
        if (pressedChinAction != 0) {
            val action = pressedChinAction
            pressedChinAction = 0
            if (y >= chinTopPx - dp(12f)) {
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
        pressedChinAction = 0
        backspacePointerId = MotionEvent.INVALID_POINTER_ID
        if (isTrackpadActive) {
            isTrackpadActive = false
            trackpadPointerId = MotionEvent.INVALID_POINTER_ID
            callback?.onTrackpadEnd()
        }
        mainHandler.removeCallbacks(longPressAlternatesRunnable)
        mainHandler.removeCallbacks(spaceTrackpadRunnable)
        mainHandler.removeCallbacks(backspaceRepeatRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAllActiveSessions()
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val SUGGESTION_BAR_DP = 40f
        private const val TOP_NO_BAR_PAD_DP = 6f
        private const val KEYS_TOP_PAD_DP = 6f
        private const val KEYS_BOTTOM_PAD_DP = 6f
        private const val ROW_GAP_DP = 10.5f
        private const val BOTTOM_CHIN_DP = 40f
        private const val KEY_CORNER_RADIUS_DP = 5f

        private const val POPUP_FADE_DURATION_MS = 65L
        private const val LONG_PRESS_ALTERNATES_MS = 360L
        private const val SPACE_TRACKPAD_DELAY_MS = 310L
        private const val TRACKPAD_STEP_DP = 9.5f

        private const val BACKSPACE_INITIAL_DELAY_MS = 340L
        private const val BACKSPACE_CHAR_INTERVAL_MS = 58L
        private const val BACKSPACE_WORD_ACCEL_MS = 1150L
        private const val BACKSPACE_WORD_INTERVAL_MS = 115L
    }
}

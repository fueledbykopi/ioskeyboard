package com.ikeys.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Ultra-lightweight, zero-inflation iOS-styled Emoji Panel.
 * Renders emoji grids directly onto a single Canvas inside a ScrollView for instant <2ms open latency.
 */
class IOSEmojiPanelView(context: Context) : LinearLayout(context) {

    interface Callback {
        fun onEmojiSelected(emoji: String)
        fun onBackspace()
        fun onReturnToAlpha()
    }

    var callback: Callback? = null

    private data class EmojiCategory(
        val title: String,
        val tabGlyph: String,
        val emojis: List<String>
    )

    private val recentEmojis = ArrayList<String>().apply {
        addAll(listOf("😂", "❤️", "🙏", "🔥", "👍", "😭", "😍", "✨", "😊", "🥰", "🤣", "👌", "🎉", "💯", "🙌", "😎"))
    }

    private val categories: List<EmojiCategory> = listOf(
        EmojiCategory("FREQUENTLY USED", "🕒", recentEmojis),
        EmojiCategory(
            "SMILEYS & PEOPLE", "😀",
            listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂",
                "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
                "😘", "😗", "😚", "😙", "🥲", "😋", "😛", "😜",
                "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🫡",
                "🤐", "🤨", "😐", "😑", "😶", "😏", "😒", "🙄",
                "😬", "😮‍💨", "🤥", "😌", "😔", "😪", "🤤", "😴",
                "😷", "🤒", "🤕", "🤢", "🤮", "🤧", "🥵", "🥶",
                "🥴", "😵", "🤯", "🤠", "🥳", "🥸", "😎", "🤓",
                "🧐", "😕", "🫤", "😟", "🙁", "😮", "😯", "😲",
                "😳", "🥺", "🥹", "😦", "😧", "😨", "😰", "😥",
                "😢", "😭", "😱", "😖", "😣", "😞", "😓", "😩",
                "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "💀"
            )
        ),
        EmojiCategory(
            "GESTURES & HANDS", "👋",
            listOf(
                "👋", "🤚", "🖐️", "✋", "🖖", "👌", "🤌", "🤏",
                "✌️", "🤞", "🤟", "🤘", "🤙", "👈", "👉", "👆",
                "👇", "☝️", "👍", "👎", "✊", "👊", "🤛", "🤜",
                "👏", "🙌", "🫶", "👐", "🤲", "🤝", "🙏", "✍️",
                "💅", "💪", "🦾", "👀", "👁️", "🧠", "🫀", "🗣️"
            )
        ),
        EmojiCategory(
            "HEARTS & EMOTION", "❤️",
            listOf(
                "❤️", "🩷", "🧡", "💛", "💚", "💙", "🩵", "💜",
                "🖤", "🩶", "🤍", "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "💕",
                "💞", "💓", "💗", "💖", "💘", "💝", "💟", "💋",
                "💯", "💢", "💥", "💫", "💦", "💨", "💬", "💭"
            )
        ),
        EmojiCategory(
            "ANIMALS & NATURE", "🌿",
            listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
                "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔",
                "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇", "🐺",
                "🌸", "🌺", "🌻", "🌹", "🌷", "🌱", "🌿", "🍀",
                "🍁", "🍂", "🌈", "☀️", "🌤️", "⛅", "🌧️", "⚡",
                "❄️", "🔥", "💧", "🌊", "✨", "⭐", "🌟", "🌙"
            )
        ),
        EmojiCategory(
            "FOOD & DRINK", "☕",
            listOf(
                "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇",
                "🍓", "🫐", "🍒", "🍑", "🥭", "🍍", "🥥", "🥑",
                "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🍜",
                "🍣", "🍱", "🍛", "🍚", "🍝", "🍗", "🥩", "🍤",
                "🍩", "🍪", "🎂", "🍰", "🧁", "🍫", "🍬", "🍦",
                "☕", "🍵", "🧋", "🥤", "🧃", "🍺", "🥂", "🍷"
            )
        ),
        EmojiCategory(
            "OBJECTS & SYMBOLS", "🎉",
            listOf(
                "🎉", "🎊", "🎈", "🎁", "🏆", "🥇", "⚽", "🏀",
                "🎮", "🎧", "🎵", "🎶", "🎸", "🎹", "📱", "💻",
                "⌚", "📷", "🎥", "💡", "🔋", "🔌", "📚", "📝",
                "📌", "📎", "🔒", "🔑", "✈️", "🚗", "🚀", "🏠",
                "✅", "❌", "⚠️", "❓", "❗", "➕", "➖", "➡️"
            )
        )
    )

    private val density = resources.displayMetrics.density
    private var theme: IOSKeyboardTheme = IOSKeyboardTheme.Light
    private var selectedCategoryIndex: Int = 1

    private val headerTitle: TextView
    private val scrollView: ScrollView
    private val gridCanvas: EmojiGridCanvasView
    private val tabBar: LinearLayout
    private val tabViews = ArrayList<TextView>()
    private val abcButton: TextView
    private val delButton: TextView

    init {
        orientation = VERTICAL

        headerTitle = TextView(context).apply {
            textSize = 11.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        addView(headerTitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        gridCanvas = EmojiGridCanvasView(context).apply {
            onEmojiTap = { emoji ->
                recordRecent(emoji)
                callback?.onEmojiSelected(emoji)
            }
        }
        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(gridCanvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom iOS category bar
        tabBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
        }

        abcButton = TextView(context).apply {
            text = "ABC"
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { callback?.onReturnToAlpha() }
        }
        tabBar.addView(abcButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)))

        categories.forEachIndexed { idx, cat ->
            val tab = TextView(context).apply {
                text = cat.tabGlyph
                textSize = 16f
                gravity = Gravity.CENTER
                setOnClickListener { selectCategory(idx) }
            }
            tabViews.add(tab)
            tabBar.addView(tab, LayoutParams(0, dp(34), 1f))
        }

        delButton = TextView(context).apply {
            text = "⌫"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { callback?.onBackspace() }
        }
        tabBar.addView(delButton, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)))

        addView(tabBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(46)))

        selectCategory(1)
        applyTheme(IOSKeyboardTheme.Light)
    }

    fun applyTheme(newTheme: IOSKeyboardTheme) {
        theme = newTheme
        setBackgroundColor(theme.keyboardBg)
        headerTitle.setTextColor(theme.chinIconColor)
        abcButton.setTextColor(theme.keySpecialText)
        delButton.setTextColor(theme.keySpecialText)
        gridCanvas.applyTheme(theme)
        updateTabVisuals()
    }

    private fun selectCategory(index: Int) {
        selectedCategoryIndex = index.coerceIn(0, categories.lastIndex)
        val cat = categories[selectedCategoryIndex]
        headerTitle.text = cat.title
        gridCanvas.setEmojis(if (selectedCategoryIndex == 0) recentEmojis else cat.emojis)
        scrollView.scrollTo(0, 0)
        updateTabVisuals()
    }

    private fun updateTabVisuals() {
        tabViews.forEachIndexed { idx, tv ->
            tv.alpha = if (idx == selectedCategoryIndex) 1.0f else 0.45f
        }
    }

    private fun recordRecent(emoji: String) {
        recentEmojis.remove(emoji)
        recentEmojis.add(0, emoji)
        if (recentEmojis.size > 32) {
            recentEmojis.removeAt(recentEmojis.lastIndex)
        }
        if (selectedCategoryIndex == 0) {
            gridCanvas.setEmojis(recentEmojis)
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    /**
     * Single-Canvas emoji grid view: zero view-tree bloat, instantaneous scrolling.
     */
    private class EmojiGridCanvasView(context: Context) : View(context) {
        var onEmojiTap: ((String) -> Unit)? = null

        private val density = resources.displayMetrics.density
        private val columns = 8
        private val rowHeightPx = (44f * density)

        private var emojis: List<String> = emptyList()
        private var highlightIndex: Int = -1
        private var downX: Float = 0f
        private var downY: Float = 0f
        private var movedTooMuch: Boolean = false

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 26f * density
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x26007AFF
        }
        private val rect = RectF()

        fun applyTheme(theme: IOSKeyboardTheme) {
            highlightPaint.color = theme.emojiTabActiveBg
            invalidate()
        }

        fun setEmojis(list: List<String>) {
            emojis = list.toList()
            highlightIndex = -1
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val rows = ((emojis.size + columns - 1) / columns).coerceAtLeast(1)
            val h = (rows * rowHeightPx + 12f * density).toInt()
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            if (emojis.isEmpty() || width <= 0) return
            val cellW = width.toFloat() / columns
            val textOffset = -(textPaint.descent() + textPaint.ascent()) * 0.5f

            for (i in emojis.indices) {
                val col = i % columns
                val row = i / columns
                val left = col * cellW
                val top = row * rowHeightPx

                if (i == highlightIndex) {
                    rect.set(left + 4f * density, top + 3f * density, left + cellW - 4f * density, top + rowHeightPx - 3f * density)
                    canvas.drawRoundRect(rect, 8f * density, 8f * density, highlightPaint)
                }
                canvas.drawText(emojis[i], left + cellW * 0.5f, top + rowHeightPx * 0.5f + textOffset, textPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    movedTooMuch = false
                    highlightIndex = indexAt(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    if (dx > 12f * density || dy > 12f * density) {
                        movedTooMuch = true
                        if (highlightIndex != -1) {
                            highlightIndex = -1
                            invalidate()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val idx = highlightIndex
                    highlightIndex = -1
                    invalidate()
                    if (!movedTooMuch && idx in emojis.indices) {
                        onEmojiTap?.invoke(emojis[idx])
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    highlightIndex = -1
                    invalidate()
                }
            }
            return true
        }

        private fun indexAt(x: Float, y: Float): Int {
            if (width <= 0 || y < 0f) return -1
            val col = (x / (width.toFloat() / columns)).toInt().coerceIn(0, columns - 1)
            val row = (y / rowHeightPx).toInt()
            val idx = row * columns + col
            return if (idx in emojis.indices) idx else -1
        }
    }
}

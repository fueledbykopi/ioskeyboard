package com.ikeys.keyboard.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.ikeys.keyboard.prefs.BlurIntensity
import com.ikeys.keyboard.prefs.HapticStrength
import com.ikeys.keyboard.prefs.HeightPreset
import com.ikeys.keyboard.prefs.IKeysPreferences
import com.ikeys.keyboard.prefs.KeyboardStyle
import com.ikeys.keyboard.prefs.ThemeMode

/**
 * Clean, ultra-lightweight iOS Settings-inspired Companion & Onboarding Activity.
 * Provides guided 2-step IME activation, a live typing sandbox, and real-time keyboard customization.
 */
class MainActivity : Activity() {

    private lateinit var prefs: IKeysPreferences
    private var isNightUi: Boolean = false

    private var enableStepBadge: TextView? = null
    private var selectStepBadge: TextView? = null

    private val density: Float get() = resources.displayMetrics.density

    // iOS Settings UI palette
    private val pageBg: Int get() = if (isNightUi) 0xFF000000.toInt() else 0xFFF2F2F7.toInt()
    private val cardBg: Int get() = if (isNightUi) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    private val primaryText: Int get() = if (isNightUi) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val secondaryText: Int get() = if (isNightUi) 0xFF8E8E93.toInt() else 0xFF6C6C70.toInt()
    private val dividerColor: Int get() = if (isNightUi) 0xFF2C2C2E.toInt() else 0xFFE5E5EA.toInt()
    private val segmentTrackBg: Int get() = if (isNightUi) 0xFF2C2C2E.toInt() else 0xFFE3E3E8.toInt()
    private val segmentSelectedBg: Int get() = if (isNightUi) 0xFF636366.toInt() else 0xFFFFFFFF.toInt()
    private val iosBlue: Int get() = if (isNightUi) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    private val iosGreen: Int get() = if (isNightUi) 0xFF30D158.toInt() else 0xFF34C759.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = IKeysPreferences(this)
        isNightUi = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshImeStatusBadges()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshImeStatusBadges()
        }
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageBg)
            isVerticalScrollBarEnabled = false
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(36))
        }

        // Large iOS Navigation Title
        root.addView(TextView(this).apply {
            text = "iKeys"
            textSize = 34f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(primaryText)
        })
        root.addView(TextView(this).apply {
            text = "Native iOS Keyboard for Android • Ultra-Light & Private"
            textSize = 13.5f
            setTextColor(secondaryText)
            setPadding(0, dp(2), 0, dp(18))
        })

        // 1. Setup Section
        root.addView(sectionHeader("QUICK SETUP"))
        val setupCard = createCard()
        val (step1Row, step1Badge) = createActionRow(
            title = "1. Enable iKeys Keyboard",
            subtitle = "Turn on iKeys in Android System Settings",
            onClick = {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        )
        enableStepBadge = step1Badge
        setupCard.addView(step1Row)
        setupCard.addView(createDivider())

        val (step2Row, step2Badge) = createActionRow(
            title = "2. Select iKeys as Active Keyboard",
            subtitle = "Choose iKeys from the Input Method Picker",
            onClick = {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
        )
        selectStepBadge = step2Badge
        setupCard.addView(step2Row)
        root.addView(setupCard)

        // 2. Live Sandbox Typing Field with vibrant iOS wallpaper card so Liquid Glass & Frosted Blur shine visibly
        root.addView(sectionHeader("LIVE TEST SANDBOX"))
        val sandboxCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (isNightUi) {
                    intArrayOf(0xFF1A2540.toInt(), 0xFF2B1D3D.toInt(), 0xFF152A38.toInt())
                } else {
                    intArrayOf(0xFFE3F0FF.toInt(), 0xFFF3E8FF.toInt(), 0xFFE2F7F0.toInt())
                }
            ).apply {
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val sandboxInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(if (isNightUi) 0xCC161822.toInt() else 0xD9FFFFFF.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }
        val sandboxInput = EditText(this).apply {
            hint = "Tap here to test Liquid Glass, Frosted Blur, instant typing, QuickType & Globe…"
            setHintTextColor(secondaryText)
            setTextColor(primaryText)
            textSize = 16f
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        sandboxInner.addView(sandboxInput)
        sandboxCard.addView(sandboxInner)
        root.addView(sandboxCard)

        // 3. Appearance & Material
        root.addView(sectionHeader("APPEARANCE & GLASS MATERIAL"))
        val appearanceCard = createCard()
        appearanceCard.addView(
            createSegmentedRow(
                label = "Keyboard Style",
                subtitle = "iOS 26 Liquid Glass, Frosted Blur, or Classic solid keys",
                options = KeyboardStyle.entries.map { it.displayName },
                selectedIndex = KeyboardStyle.entries.indexOf(prefs.keyboardStyle),
                onSelected = { idx -> prefs.keyboardStyle = KeyboardStyle.entries[idx] }
            )
        )
        appearanceCard.addView(createDivider())
        appearanceCard.addView(
            createSegmentedRow(
                label = "Glass & Blur Intensity",
                subtitle = "Live backdrop blur on Android 12+ with adaptive glass fallback",
                options = BlurIntensity.entries.map { it.displayName },
                selectedIndex = BlurIntensity.entries.indexOf(prefs.blurIntensity),
                onSelected = { idx -> prefs.blurIntensity = BlurIntensity.entries[idx] }
            )
        )
        appearanceCard.addView(createDivider())
        appearanceCard.addView(
            createSegmentedRow(
                label = "Color Theme",
                options = ThemeMode.entries.map { it.displayName },
                selectedIndex = ThemeMode.entries.indexOf(prefs.themeMode),
                onSelected = { idx -> prefs.themeMode = ThemeMode.entries[idx] }
            )
        )
        appearanceCard.addView(createDivider())
        appearanceCard.addView(
            createSegmentedRow(
                label = "Key Height",
                options = HeightPreset.entries.map { it.displayName.substringBefore(" ") },
                selectedIndex = HeightPreset.entries.indexOf(prefs.heightPreset),
                onSelected = { idx -> prefs.heightPreset = HeightPreset.entries[idx] }
            )
        )
        appearanceCard.addView(createDivider())
        appearanceCard.addView(
            createToggleRow(
                title = "Character Preview Popup",
                subtitle = "Signature iOS teardrop magnifier on keypress",
                initialValue = prefs.keyPopupEnabled,
                onChange = { prefs.keyPopupEnabled = it }
            )
        )
        appearanceCard.addView(createDivider())
        appearanceCard.addView(
            createToggleRow(
                title = "Bottom iPhone Chin Bar",
                subtitle = "Tap Globe to switch keyboard, hold Globe for picker • Dismiss on right",
                initialValue = prefs.bottomChinEnabled,
                onChange = { prefs.bottomChinEnabled = it }
            )
        )
        root.addView(appearanceCard)

        // 4. Sound & Taptics
        root.addView(sectionHeader("SOUND & TAPTIC FEEDBACK"))
        val feedbackCard = createCard()
        feedbackCard.addView(
            createToggleRow(
                title = "iOS Key Click Sound",
                subtitle = "Synthesized woody tock clicks (respects Silent mode)",
                initialValue = prefs.soundEnabled,
                onChange = { prefs.soundEnabled = it }
            )
        )
        feedbackCard.addView(createDivider())
        feedbackCard.addView(createVolumeRow())
        feedbackCard.addView(createDivider())
        feedbackCard.addView(
            createSegmentedRow(
                label = "Taptic Haptics",
                options = listOf("Off", "Light", "Med", "Strong"),
                selectedIndex = HapticStrength.entries.indexOf(prefs.hapticStrength),
                onSelected = { idx -> prefs.hapticStrength = HapticStrength.entries[idx] }
            )
        )
        root.addView(feedbackCard)

        // 5. Smart Typing
        root.addView(sectionHeader("SMART TYPING"))
        val typingCard = createCard()
        typingCard.addView(
            createToggleRow(
                title = "QuickType Prediction Bar",
                subtitle = "Bilingual English & Indonesian instant suggestions",
                initialValue = prefs.predictionBarEnabled,
                onChange = { prefs.predictionBarEnabled = it }
            )
        )
        typingCard.addView(createDivider())
        typingCard.addView(
            createToggleRow(
                title = "Auto-Correction",
                subtitle = "Automatically apply highlighted QuickType fix on Space",
                initialValue = prefs.autoCorrect,
                onChange = { prefs.autoCorrect = it }
            )
        )
        typingCard.addView(createDivider())
        typingCard.addView(
            createToggleRow(
                title = "Auto-Capitalization",
                subtitle = "Capitalize start of sentences automatically",
                initialValue = prefs.autoCapitalize,
                onChange = { prefs.autoCapitalize = it }
            )
        )
        typingCard.addView(createDivider())
        typingCard.addView(
            createToggleRow(
                title = "\".\" Shortcut (Double-Space)",
                subtitle = "Double-tapping spacebar inserts a period and space",
                initialValue = prefs.doubleSpacePeriod,
                onChange = { prefs.doubleSpacePeriod = it }
            )
        )
        root.addView(typingCard)

        // Privacy Footer
        root.addView(TextView(this).apply {
            text = "iKeys requests zero internet permissions. Everything you type remains 100% private and local on your device."
            textSize = 12.5f
            setTextColor(secondaryText)
            setPadding(dp(8), dp(14), dp(8), dp(10))
        })

        scroll.addView(root)
        setContentView(scroll)
        refreshImeStatusBadges()
    }

    private fun refreshImeStatusBadges() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val isEnabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val isActive = defaultIme.startsWith("$packageName/")

        enableStepBadge?.apply {
            text = if (isEnabled) "Enabled ✓" else "Open"
            setTextColor(if (isEnabled) iosGreen else iosBlue)
        }
        selectStepBadge?.apply {
            text = if (isActive) "Active ✓" else "Choose"
            setTextColor(if (isActive) iosGreen else iosBlue)
        }
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 12f
        letterSpacing = 0.04f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(secondaryText)
        setPadding(dp(12), dp(16), dp(12), dp(6))
    }

    private fun createCard(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cardBg)
            cornerRadius = dp(12).toFloat()
        }
    }

    private fun createDivider(): View = View(this).apply {
        setBackgroundColor(dividerColor)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (0.65f * density).toInt().coerceAtLeast(1)
        ).apply {
            marginStart = dp(14)
        }
    }

    private fun createActionRow(
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            setOnClickListener { onClick() }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            setTextColor(primaryText)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 12.5f
            setTextColor(secondaryText)
            setPadding(0, dp(2), 0, 0)
        })

        val badge = TextView(this).apply {
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(iosBlue)
            setPadding(dp(10), 0, 0, 0)
        }

        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return row to badge
    }

    private fun createToggleRow(
        title: String,
        subtitle: String,
        initialValue: Boolean,
        onChange: (Boolean) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            setTextColor(primaryText)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 12.5f
            setTextColor(secondaryText)
            setPadding(0, dp(2), dp(10), 0)
        })

        val toggle = IOSSwitchView(this).apply {
            setDarkMode(isNightUi)
            setChecked(initialValue, animate = false, notify = false)
            onCheckedChangeListener = onChange
        }

        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(toggle)
        return row
    }

    private fun createVolumeRow(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val title = TextView(this).apply {
            text = "Click Volume"
            textSize = 15.5f
            setTextColor(primaryText)
        }
        val pctLabel = TextView(this).apply {
            text = "${(prefs.soundVolume * 100).toInt()}%"
            textSize = 13.5f
            setTextColor(secondaryText)
        }
        labelRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        labelRow.addView(pctLabel)

        val seekBar = SeekBar(this).apply {
            max = 90
            progress = ((prefs.soundVolume - 0.1f) * 100f).toInt().coerceIn(0, 90)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val vol = (progress + 10) / 100f
                    pctLabel.text = "${(vol * 100).toInt()}%"
                    if (fromUser) prefs.soundVolume = vol
                }
                override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                override fun onStopTrackingTouch(sb: SeekBar?) = Unit
            })
        }

        container.addView(labelRow)
        container.addView(seekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        return container
    }

    private fun createSegmentedRow(
        label: String,
        options: List<String>,
        selectedIndex: Int,
        subtitle: String? = null,
        onSelected: (Int) -> Unit
    ): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(12))
        }
        container.addView(TextView(this).apply {
            text = label
            textSize = 15.5f
            setTextColor(primaryText)
            setPadding(0, 0, 0, if (subtitle == null) dp(8) else dp(2))
        })
        if (subtitle != null) {
            container.addView(TextView(this).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(secondaryText)
                setPadding(0, 0, 0, dp(8))
            })
        }

        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                setColor(segmentTrackBg)
                cornerRadius = dp(9).toFloat()
            }
        }

        var currentIdx = selectedIndex.coerceIn(0, options.lastIndex)
        val buttons = ArrayList<TextView>(options.size)

        fun refreshButtons() {
            buttons.forEachIndexed { idx, tv ->
                if (idx == currentIdx) {
                    tv.background = GradientDrawable().apply {
                        setColor(segmentSelectedBg)
                        cornerRadius = dp(7).toFloat()
                    }
                    tv.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                } else {
                    tv.background = null
                    tv.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                tv.setTextColor(primaryText)
            }
        }

        options.forEachIndexed { idx, opt ->
            val btn = TextView(this).apply {
                text = opt
                textSize = 13.5f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(7), dp(4), dp(7))
                setOnClickListener {
                    if (currentIdx != idx) {
                        currentIdx = idx
                        refreshButtons()
                        onSelected(idx)
                    }
                }
            }
            buttons.add(btn)
            track.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        refreshButtons()

        container.addView(track, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return container
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}

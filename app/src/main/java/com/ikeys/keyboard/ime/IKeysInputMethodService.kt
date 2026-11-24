package com.ikeys.keyboard.ime

import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.ikeys.keyboard.audio.IOSSoundEngine
import com.ikeys.keyboard.haptic.IOSHapticEngine
import com.ikeys.keyboard.layout.InputVariant
import com.ikeys.keyboard.layout.KeyCode
import com.ikeys.keyboard.layout.KeyType
import com.ikeys.keyboard.layout.KeyboardMode
import com.ikeys.keyboard.layout.ReturnAction
import com.ikeys.keyboard.prediction.SuggestionEngine
import com.ikeys.keyboard.prediction.SuggestionItem
import com.ikeys.keyboard.prefs.IKeysPreferences
import com.ikeys.keyboard.ui.IOSEmojiPanelView
import com.ikeys.keyboard.ui.IOSKeyboardTheme
import com.ikeys.keyboard.ui.IOSKeyboardView

/**
 * Core Android InputMethodService for iKeys.
 * Designed for ultra-low memory footprint, instant cold start, and seamless edge-to-edge iOS polish.
 */
class IKeysInputMethodService : InputMethodService(), IOSKeyboardView.Callback, IOSEmojiPanelView.Callback {

    private lateinit var prefs: IKeysPreferences
    private lateinit var soundEngine: IOSSoundEngine
    private lateinit var hapticEngine: IOSHapticEngine
    private val suggestionEngine = SuggestionEngine()
    private val inputController = TextInputController()

    private var rootContainer: FrameLayout? = null
    private var keyboardView: IOSKeyboardView? = null
    private var emojiPanelView: IOSEmojiPanelView? = null

    private var currentVariant: InputVariant = InputVariant.NORMAL
    private var currentReturnAction: ReturnAction = ReturnAction.RETURN
    private var fieldSupportsAutoCap: Boolean = true
    private var fieldSupportsPredictions: Boolean = true
    private var activeImeActionId: Int = EditorInfo.IME_ACTION_NONE

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        syncPreferencesToEnginesAndUi()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = IKeysPreferences(this)
        soundEngine = IOSSoundEngine(this)
        hapticEngine = IOSHapticEngine(this)
        prefs.registerListener(prefListener)
        syncPreferencesToEnginesAndUi()
    }

    override fun onCreateInputView(): View {
        val container = FrameLayout(this)

        val kbView = IOSKeyboardView(this).apply {
            callback = this@IKeysInputMethodService
        }
        val emojiView = IOSEmojiPanelView(this).apply {
            callback = this@IKeysInputMethodService
            visibility = View.GONE
        }

        container.addView(
            kbView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        container.addView(
            emojiView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                kbView.calculateDesiredHeightPx(),
                Gravity.BOTTOM
            )
        )

        rootContainer = container
        keyboardView = kbView
        emojiPanelView = emojiView

        syncPreferencesToEnginesAndUi()
        pushKeyboardState()
        return container
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inspectEditorInfo(info)
        syncPreferencesToEnginesAndUi()

        val textBefore = currentInputConnection?.getTextBeforeCursor(80, 0)
        inputController.resetForNewInput(
            textBeforeCursor = textBefore,
            autoCapEnabled = prefs.autoCapitalize,
            fieldSupportsAutoCap = fieldSupportsAutoCap
        )
        showEmojiPanel(false)
        pushKeyboardState()
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        keyboardView?.cancelAllActiveSessions()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncPreferencesToEnginesAndUi()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val textBefore = currentInputConnection?.getTextBeforeCursor(80, 0)
        inputController.updateAutoShift(
            textBeforeCursor = textBefore,
            autoCapEnabled = prefs.autoCapitalize,
            fieldSupportsAutoCap = fieldSupportsAutoCap
        )
        pushKeyboardState()
        refreshSuggestions()
    }

    override fun onDestroy() {
        prefs.unregisterListener(prefListener)
        soundEngine.release()
        super.onDestroy()
    }

    // --- IOSKeyboardView.Callback ---

    override fun onKeyPressFeedback(code: Int, type: KeyType) {
        when (code) {
            KeyCode.BACKSPACE -> {
                soundEngine.play(IOSSoundEngine.SoundType.DELETE_CLICK)
                hapticEngine.keyTap()
            }
            KeyCode.CHAR -> {
                soundEngine.play(IOSSoundEngine.SoundType.KEY_CLICK)
                hapticEngine.keyTap()
            }
            else -> {
                soundEngine.play(IOSSoundEngine.SoundType.MODIFIER_CLICK)
                hapticEngine.modifierTap()
            }
        }
    }

    override fun onCharacter(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        inputController.onCharacterCommitted(text)
        pushKeyboardState()
        refreshSuggestions()
    }

    override fun onSpecialKey(code: Int) {
        when (code) {
            KeyCode.SHIFT -> {
                inputController.onShiftTap(SystemClock.uptimeMillis())
                pushKeyboardState()
            }
            KeyCode.SYMBOLS -> {
                inputController.setMode(KeyboardMode.SYMBOLS)
                showEmojiPanel(false)
                pushKeyboardState()
            }
            KeyCode.MORE_SYMBOLS -> {
                inputController.setMode(KeyboardMode.MORE_SYMBOLS)
                showEmojiPanel(false)
                pushKeyboardState()
            }
            KeyCode.ALPHA -> {
                inputController.setMode(KeyboardMode.ALPHA)
                showEmojiPanel(false)
                val textBefore = currentInputConnection?.getTextBeforeCursor(80, 0)
                inputController.updateAutoShift(textBefore, prefs.autoCapitalize, fieldSupportsAutoCap)
                pushKeyboardState()
            }
            KeyCode.EMOJI -> {
                showEmojiPanel(true)
            }
            KeyCode.SPACE -> handleSpaceKey()
            KeyCode.RETURN -> handleReturnKey()
            KeyCode.GLOBE -> switchToNextKeyboardOrPicker()
            KeyCode.DISMISS -> requestHideSelf(0)
        }
    }

    override fun onBackspace(deleteWord: Boolean) {
        val ic = currentInputConnection ?: return

        // If user has an active selection, delete the selected text cleanly
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            val textBefore = ic.getTextBeforeCursor(64, 0)
            val count = if (deleteWord) {
                TextInputController.computeWordBackspaceCount(textBefore)
            } else {
                TextInputController.computeSingleBackspaceCount(textBefore)
            }
            ic.deleteSurroundingText(count, 0)
        }

        val updatedBefore = ic.getTextBeforeCursor(80, 0)
        inputController.updateAutoShift(updatedBefore, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions()
    }

    override fun onSuggestionPicked(item: SuggestionItem) {
        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(64, 0)
        val currentWord = inputController.extractCurrentWord(textBefore)

        ic.beginBatchEdit()
        if (currentWord.isNotEmpty()) {
            ic.deleteSurroundingText(currentWord.length, 0)
        }
        ic.commitText("${item.text} ", 1)
        ic.endBatchEdit()

        suggestionEngine.learnWord(item.text, boost = 30)
        val after = ic.getTextBeforeCursor(80, 0)
        inputController.updateAutoShift(after, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions()
    }

    override fun onTrackpadStart() {
        hapticEngine.trackpadEngage()
    }

    override fun onTrackpadMove(steps: Int) {
        val ic = currentInputConnection ?: return
        val keyCode = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val count = kotlin.math.abs(steps).coerceAtMost(8)
        for (i in 0 until count) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        hapticEngine.microTick()
    }

    override fun onTrackpadEnd() {
        val textBefore = currentInputConnection?.getTextBeforeCursor(80, 0)
        inputController.updateAutoShift(textBefore, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions()
    }

    override fun onPopupSlideTick() {
        hapticEngine.microTick()
    }

    // --- IOSEmojiPanelView.Callback ---

    override fun onEmojiSelected(emoji: String) {
        soundEngine.play(IOSSoundEngine.SoundType.KEY_CLICK)
        hapticEngine.keyTap()
        currentInputConnection?.commitText(emoji, 1)
    }

    override fun onBackspace() {
        soundEngine.play(IOSSoundEngine.SoundType.DELETE_CLICK)
        hapticEngine.keyTap()
        onBackspace(deleteWord = false)
    }

    override fun onReturnToAlpha() {
        soundEngine.play(IOSSoundEngine.SoundType.MODIFIER_CLICK)
        hapticEngine.modifierTap()
        showEmojiPanel(false)
        inputController.setMode(KeyboardMode.ALPHA)
        pushKeyboardState()
    }

    // --- Internal Logic ---

    private fun handleSpaceKey() {
        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(64, 0)
        val currentWord = inputController.extractCurrentWord(textBefore)

        // Optional unobtrusive autocorrect on space when QuickType has a high-confidence replacement
        if (prefs.autoCorrect && fieldSupportsPredictions && currentWord.length >= 2) {
            val replacement = suggestionEngine.autoCorrectFor(currentWord)
            if (replacement != null && !replacement.equals(currentWord, ignoreCase = false)) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(currentWord.length, 0)
                ic.commitText("$replacement ", 1)
                ic.endBatchEdit()
                suggestionEngine.learnWord(replacement, boost = 15)
                postSpaceUpdate()
                return
            }
        }

        if (currentWord.length >= 2) {
            suggestionEngine.learnWord(currentWord, boost = 10)
        }

        val action = inputController.onSpaceTap(
            nowMs = SystemClock.uptimeMillis(),
            textBeforeCursor = textBefore,
            doubleSpacePeriodEnabled = prefs.doubleSpacePeriod
        )

        if (action.shouldInsertPeriodInstead) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
        } else {
            ic.commitText(" ", 1)
        }

        postSpaceUpdate()
    }

    private fun postSpaceUpdate() {
        val updatedBefore = currentInputConnection?.getTextBeforeCursor(80, 0)
        inputController.updateAutoShift(updatedBefore, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions()
    }

    private fun handleReturnKey() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val isMultiLine = info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        if (!isMultiLine && activeImeActionId != EditorInfo.IME_ACTION_NONE && activeImeActionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(activeImeActionId)
        } else {
            ic.commitText("\n", 1)
        }

        val updatedBefore = ic.getTextBeforeCursor(80, 0)
        inputController.updateAutoShift(updatedBefore, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions()
    }

    private fun switchToNextKeyboardOrPicker() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            false
        }
        if (!switched) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
        }
    }

    private fun showEmojiPanel(show: Boolean) {
        val kb = keyboardView ?: return
        val emoji = emojiPanelView ?: return
        if (show) {
            emoji.layoutParams = emoji.layoutParams.apply {
                height = kb.height.takeIf { it > 0 } ?: kb.calculateDesiredHeightPx()
            }
            kb.visibility = View.INVISIBLE
            emoji.visibility = View.VISIBLE
        } else {
            emoji.visibility = View.GONE
            kb.visibility = View.VISIBLE
        }
    }

    private fun inspectEditorInfo(info: EditorInfo?) {
        if (info == null) {
            currentVariant = InputVariant.NORMAL
            currentReturnAction = ReturnAction.RETURN
            fieldSupportsAutoCap = true
            fieldSupportsPredictions = true
            activeImeActionId = EditorInfo.IME_ACTION_NONE
            return
        }

        val inputType = info.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        currentVariant = when {
            inputClass == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> InputVariant.EMAIL
            inputClass == InputType.TYPE_CLASS_TEXT &&
                variation == InputType.TYPE_TEXT_VARIATION_URI -> InputVariant.URI
            else -> InputVariant.NORMAL
        }

        val isPassword = inputClass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )

        val noSuggestionsFlag = (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        fieldSupportsPredictions = !isPassword && !noSuggestionsFlag &&
            currentVariant == InputVariant.NORMAL &&
            inputClass != InputType.TYPE_CLASS_NUMBER &&
            inputClass != InputType.TYPE_CLASS_PHONE

        fieldSupportsAutoCap = !isPassword &&
            currentVariant == InputVariant.NORMAL &&
            inputClass == InputType.TYPE_CLASS_TEXT

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        activeImeActionId = if (noEnterAction) EditorInfo.IME_ACTION_NONE else action

        currentReturnAction = when (activeImeActionId) {
            EditorInfo.IME_ACTION_SEARCH -> ReturnAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> ReturnAction.SEND
            EditorInfo.IME_ACTION_GO -> ReturnAction.GO
            EditorInfo.IME_ACTION_NEXT -> ReturnAction.NEXT
            EditorInfo.IME_ACTION_DONE -> ReturnAction.DONE
            else -> ReturnAction.RETURN
        }
    }

    private fun refreshSuggestions() {
        val kb = keyboardView ?: return
        if (!prefs.predictionBarEnabled || !fieldSupportsPredictions) {
            kb.setSuggestions(emptyList())
            return
        }
        val textBefore = currentInputConnection?.getTextBeforeCursor(48, 0)
        val word = inputController.extractCurrentWord(textBefore)
        kb.setSuggestions(suggestionEngine.suggest(word, maxResults = 3))
    }

    private fun pushKeyboardState() {
        keyboardView?.setKeyboardState(
            mode = inputController.keyboardMode,
            variant = currentVariant,
            shift = inputController.shiftState,
            returnAction = currentReturnAction
        )
    }

    private fun syncPreferencesToEnginesAndUi() {
        soundEngine.enabled = prefs.soundEnabled
        soundEngine.volume = prefs.soundVolume
        hapticEngine.strength = prefs.hapticStrength

        val theme = IOSKeyboardTheme.resolve(this, prefs.themeMode)
        keyboardView?.applyConfig(
            theme = theme,
            popupEnabled = prefs.keyPopupEnabled,
            suggestionBarEnabled = prefs.predictionBarEnabled && fieldSupportsPredictions,
            bottomChinEnabled = prefs.bottomChinEnabled,
            heightPreset = prefs.heightPreset
        )
        emojiPanelView?.applyTheme(theme)
        syncNavigationBarWithTheme(theme)
    }

    private fun syncNavigationBarWithTheme(theme: IOSKeyboardTheme) {
        val imeWindow = window?.window ?: return
        imeWindow.navigationBarColor = theme.keyboardBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = if (theme.isDark) 0 else WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            imeWindow.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else {
            @Suppress("DEPRECATION")
            val flags = imeWindow.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            imeWindow.decorView.systemUiVisibility = if (theme.isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }
}

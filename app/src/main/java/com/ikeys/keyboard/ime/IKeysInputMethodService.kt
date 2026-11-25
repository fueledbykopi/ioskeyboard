package com.ikeys.keyboard.ime

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
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
import java.util.function.Consumer

/**
 * Core Android InputMethodService for iKeys.
 * Engineered for:
 * - Instant zero-lag Liquid Glass & Frosted Blur (with real Android 12+ cross-window blur + graceful fallback)
 * - Reliable composing-text QuickType predictions & contextual next-word bigrams
 * - 100% reliable Globe keyboard switching (tap = next keyboard, long-press = system picker)
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
    private var fieldSupportsAutoCorrect: Boolean = true
    private var activeImeActionId: Int = EditorInfo.IME_ACTION_NONE

    // Local composing state so QuickType predictions never lag behind async Binder IPC
    private val composingWord = StringBuilder(32)
    private var lastCompletedWord: String = ""
    private var lastComposingEditMs: Long = 0L
    private var learnedWordsDirtyCount: Int = 0

    // Real-time Android 12+ cross-window blur tracking
    private var hardwareBlurEnabled: Boolean = false
    private var blurListenerRegistered: Boolean = false
    private val blurEnabledListener = Consumer<Boolean> { enabled ->
        if (hardwareBlurEnabled != enabled) {
            hardwareBlurEnabled = enabled
            syncPreferencesToEnginesAndUi()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != "learned_words_csv") {
            syncPreferencesToEnginesAndUi()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = IKeysPreferences(this)
        soundEngine = IOSSoundEngine(this)
        hapticEngine = IOSHapticEngine(this)
        suggestionEngine.loadUserWordsCsv(prefs.learnedWordsCsv)
        syncSubtypeLanguage(getCurrentSubtypeSafely(), showBanner = false)
        prefs.registerListener(prefListener)
        syncPreferencesToEnginesAndUi()
    }

    override fun onCreateInputView(): View {
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

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

        registerBlurListenerIfSupported()
        syncPreferencesToEnginesAndUi()
        pushKeyboardState()
        refreshSuggestions()
        return container
    }

    override fun onWindowShown() {
        super.onWindowShown()
        registerBlurListenerIfSupported()
        syncPreferencesToEnginesAndUi()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inspectEditorInfo(info)
        composingWord.setLength(0)

        val textBefore = currentInputConnection?.getTextBeforeCursor(80, 0)
        lastCompletedWord = inputController.extractPreviousWord(textBefore)
        if (lastCompletedWord.isEmpty() && !textBefore.isNullOrEmpty() && textBefore.endsWith(" ")) {
            lastCompletedWord = inputController.extractCurrentWord(textBefore.trimEnd())
        }

        inputController.resetForNewInput(
            textBeforeCursor = textBefore,
            autoCapEnabled = prefs.autoCapitalize,
            fieldSupportsAutoCap = fieldSupportsAutoCap
        )
        syncPreferencesToEnginesAndUi()
        showEmojiPanel(false)
        pushKeyboardState()
        refreshSuggestions(textBeforeOverride = textBefore)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        commitComposingIfNeeded(currentInputConnection)
        flushLearnedWordsIfNeeded(force = true)
        keyboardView?.cancelAllActiveSessions()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncPreferencesToEnginesAndUi()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        syncSubtypeLanguage(newSubtype, showBanner = true)
        refreshSuggestions()
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

        val now = SystemClock.uptimeMillis()
        val hasSelectionRange = newSelStart != newSelEnd
        val textBefore = currentInputConnection?.getTextBeforeCursor(80, 0)

        // Reset composing buffer only if user externally moved cursor or selected text
        if (composingWord.isNotEmpty()) {
            if (hasSelectionRange) {
                composingWord.setLength(0)
                currentInputConnection?.finishComposingText()
            } else if (now - lastComposingEditMs > 220L) {
                val active = composingWord.toString()
                if (textBefore != null && !textBefore.endsWith(active)) {
                    composingWord.setLength(0)
                    currentInputConnection?.finishComposingText()
                }
            }
        }

        if (composingWord.isEmpty() && textBefore != null) {
            lastCompletedWord = inputController.extractPreviousWord(textBefore)
        }

        inputController.updateAutoShift(
            textBeforeCursor = textBefore,
            autoCapEnabled = prefs.autoCapitalize,
            fieldSupportsAutoCap = fieldSupportsAutoCap
        )
        pushKeyboardState()
        refreshSuggestions(textBeforeOverride = textBefore)
    }

    override fun onDestroy() {
        unregisterBlurListenerIfSupported()
        flushLearnedWordsIfNeeded(force = true)
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
        val isWordChar = text.isNotEmpty() && text.all { it.isLetter() || it == '\'' }

        if (fieldSupportsPredictions && prefs.predictionBarEnabled && isWordChar) {
            if (composingWord.isEmpty()) {
                val textBefore = ic.getTextBeforeCursor(64, 0)
                val existingWord = inputController.extractCurrentWord(textBefore)
                if (textBefore != null) {
                    lastCompletedWord = inputController.extractPreviousWord(textBefore)
                }
                if (existingWord.isNotEmpty()) {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(existingWord.length, 0)
                    composingWord.append(existingWord).append(text)
                    ic.setComposingText(composingWord.toString(), 1)
                    ic.endBatchEdit()
                } else {
                    composingWord.append(text)
                    ic.setComposingText(composingWord.toString(), 1)
                }
            } else {
                composingWord.append(text)
                ic.setComposingText(composingWord.toString(), 1)
            }
            lastComposingEditMs = SystemClock.uptimeMillis()
        } else {
            commitComposingIfNeeded(ic)
            ic.commitText(text, 1)
            if (text.any { it == '.' || it == '?' || it == '!' || it == '\n' }) {
                lastCompletedWord = ""
            }
        }

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
                commitComposingIfNeeded(currentInputConnection)
                showEmojiPanel(true)
            }
            KeyCode.SPACE -> handleSpaceKey()
            KeyCode.RETURN -> handleReturnKey()
            KeyCode.GLOBE -> switchToNextKeyboardOrPicker()
            KeyCode.DISMISS -> {
                commitComposingIfNeeded(currentInputConnection)
                requestHideSelf(0)
            }
        }
    }

    override fun onGlobeLongPress() {
        commitComposingIfNeeded(currentInputConnection)
        showInputMethodPickerDialog()
    }

    override fun onBackspace(deleteWord: Boolean) {
        val ic = currentInputConnection ?: return

        // 1. If user has an active selection, delete the selected text cleanly
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            composingWord.setLength(0)
            ic.commitText("", 1)
        } else if (composingWord.isNotEmpty()) {
            // 2. Fast local composing deletion (instant QuickType update, zero IPC lag)
            if (deleteWord) {
                composingWord.setLength(0)
                ic.commitText("", 1)
            } else {
                composingWord.deleteCharAt(composingWord.length - 1)
                if (composingWord.isNotEmpty()) {
                    ic.setComposingText(composingWord.toString(), 1)
                } else {
                    ic.commitText("", 1)
                }
            }
            lastComposingEditMs = SystemClock.uptimeMillis()
        } else {
            // 3. Standard deletion before cursor (safe for surrogate pairs & ZWJ emojis)
            val textBefore = ic.getTextBeforeCursor(64, 0)
            val count = if (deleteWord) {
                TextInputController.computeWordBackspaceCount(textBefore)
            } else {
                TextInputController.computeSingleBackspaceCount(textBefore)
            }
            ic.deleteSurroundingText(count, 0)
        }

        val updatedBefore = ic.getTextBeforeCursor(80, 0)
        if (composingWord.isEmpty()) {
            lastCompletedWord = inputController.extractPreviousWord(updatedBefore)
        }
        inputController.updateAutoShift(updatedBefore, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions(textBeforeOverride = updatedBefore)
    }

    override fun onSuggestionPicked(item: SuggestionItem) {
        val ic = currentInputConnection ?: return
        val picked = item.text

        ic.beginBatchEdit()
        if (composingWord.isNotEmpty()) {
            // Atomically replaces the active composing span across all Android editors
            ic.commitText("$picked ", 1)
            composingWord.setLength(0)
        } else {
            val textBefore = ic.getTextBeforeCursor(64, 0)
            val existingWord = inputController.extractCurrentWord(textBefore)
            if (existingWord.isNotEmpty()) {
                ic.deleteSurroundingText(existingWord.length, 0)
            }
            ic.commitText("$picked ", 1)
        }
        ic.endBatchEdit()

        lastComposingEditMs = SystemClock.uptimeMillis()
        recordLearnedWordAndBigram(picked, boost = 30)
        lastCompletedWord = picked

        val after = ic.getTextBeforeCursor(80, 0)
        inputController.updateAutoShift(after, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions(textBeforeOverride = after)
    }

    override fun onTrackpadStart() {
        commitComposingIfNeeded(currentInputConnection)
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
        lastCompletedWord = inputController.extractPreviousWord(textBefore)
        inputController.updateAutoShift(textBefore, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions(textBeforeOverride = textBefore)
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
        val activeWord = if (composingWord.isNotEmpty()) {
            composingWord.toString()
        } else {
            inputController.extractCurrentWord(textBefore)
        }

        // High-confidence autocorrect on space (contractions, shorthands, clear 1-edit typos)
        if (prefs.autoCorrect && fieldSupportsAutoCorrect && activeWord.length >= 2) {
            val replacement = suggestionEngine.autoCorrectFor(activeWord, previousWord = lastCompletedWord)
            if (replacement != null && !replacement.equals(activeWord, ignoreCase = false)) {
                ic.beginBatchEdit()
                if (composingWord.isNotEmpty()) {
                    ic.commitText("$replacement ", 1)
                    composingWord.setLength(0)
                } else {
                    ic.deleteSurroundingText(activeWord.length, 0)
                    ic.commitText("$replacement ", 1)
                }
                ic.endBatchEdit()
                lastComposingEditMs = SystemClock.uptimeMillis()
                recordLearnedWordAndBigram(replacement, boost = 15)
                lastCompletedWord = replacement
                postSpaceUpdate()
                return
            }
        }

        // Finalize composing word cleanly before inserting space
        if (composingWord.isNotEmpty()) {
            ic.finishComposingText()
            composingWord.setLength(0)
            lastComposingEditMs = SystemClock.uptimeMillis()
        }

        if (activeWord.length >= 2) {
            recordLearnedWordAndBigram(activeWord, boost = 10)
            lastCompletedWord = activeWord
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
            lastCompletedWord = ""
        } else {
            ic.commitText(" ", 1)
        }

        postSpaceUpdate()
    }

    private fun postSpaceUpdate() {
        val updatedBefore = currentInputConnection?.getTextBeforeCursor(80, 0)
        inputController.updateAutoShift(updatedBefore, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions(textBeforeOverride = updatedBefore)
    }

    private fun handleReturnKey() {
        val ic = currentInputConnection ?: return
        commitComposingIfNeeded(ic)

        val info = currentInputEditorInfo
        val isMultiLine = info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        if (!isMultiLine && activeImeActionId != EditorInfo.IME_ACTION_NONE && activeImeActionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(activeImeActionId)
        } else {
            ic.commitText("\n", 1)
        }

        lastCompletedWord = ""
        val updatedBefore = ic.getTextBeforeCursor(80, 0)
        inputController.updateAutoShift(updatedBefore, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestions(textBeforeOverride = updatedBefore)
    }

    /**
     * Fixes Globe icon switching across all Android versions & OEMs:
     * Directly switches to the next enabled third-party/system keyboard (instead of getting
     * trapped ping-ponging between iKeys's own en_US / id_ID subtypes via MRU), and falls
     * back seamlessly to Android's native Input Method Picker.
     */
    private fun switchToNextKeyboardOrPicker() {
        commitComposingIfNeeded(currentInputConnection)

        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val enabledImes = try {
            imm?.enabledInputMethodList.orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }

        val otherImes = enabledImes.filter { it.packageName != packageName }
        if (otherImes.isNotEmpty()) {
            // Find the IME immediately after ours in the enabled list (wrapping around)
            val myIndex = enabledImes.indexOfFirst { it.packageName == packageName }
            val nextIme = if (myIndex >= 0) {
                var candidate = otherImes.first()
                for (offset in 1 until enabledImes.size) {
                    val item = enabledImes[(myIndex + offset) % enabledImes.size]
                    if (item.packageName != packageName) {
                        candidate = item
                        break
                    }
                }
                candidate
            } else {
                otherImes.first()
            }

            try {
                switchInputMethod(nextIme.id)
                return
            } catch (_: Throwable) {
                // Fall through to picker if OEM restricts direct switchInputMethod
            }
        }

        // If no other IME is enabled or direct switch failed, open the system keyboard picker
        showInputMethodPickerDialog()
    }

    private fun showInputMethodPickerDialog() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun commitComposingIfNeeded(ic: InputConnection?) {
        if (composingWord.isNotEmpty()) {
            val word = composingWord.toString()
            ic?.finishComposingText()
            composingWord.setLength(0)
            lastComposingEditMs = SystemClock.uptimeMillis()
            if (word.length >= 2) {
                recordLearnedWordAndBigram(word, boost = 10)
                lastCompletedWord = word
            }
        }
    }

    private fun recordLearnedWordAndBigram(word: String, boost: Int) {
        suggestionEngine.learnWord(word, boost = boost)
        if (lastCompletedWord.isNotEmpty()) {
            suggestionEngine.learnTransition(lastCompletedWord, word, boost = boost)
        }
        learnedWordsDirtyCount++
        if (learnedWordsDirtyCount >= 5) {
            flushLearnedWordsIfNeeded(force = false)
        }
    }

    private fun flushLearnedWordsIfNeeded(force: Boolean) {
        if (learnedWordsDirtyCount == 0 && !force) return
        if (learnedWordsDirtyCount > 0) {
            prefs.learnedWordsCsv = suggestionEngine.exportUserWordsCsv(maxWords = 200)
            learnedWordsDirtyCount = 0
        }
    }

    private fun getCurrentSubtypeSafely(): InputMethodSubtype? {
        return try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.currentInputMethodSubtype
        } catch (_: Throwable) {
            null
        }
    }

    private fun syncSubtypeLanguage(subtype: InputMethodSubtype?, showBanner: Boolean) {
        @Suppress("DEPRECATION")
        val rawLocale = subtype?.languageTag?.ifBlank { subtype.locale }.orEmpty().lowercase()

        when {
            rawLocale.startsWith("id") || rawLocale.startsWith("in") -> {
                suggestionEngine.preferredLanguage = "id"
                if (showBanner) keyboardView?.flashSpacebarLanguage("Indonesia")
            }
            rawLocale.startsWith("en") -> {
                suggestionEngine.preferredLanguage = "en"
                if (showBanner) keyboardView?.flashSpacebarLanguage("English")
            }
            else -> {
                suggestionEngine.preferredLanguage = null
            }
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
            fieldSupportsAutoCorrect = true
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

        val isPassword = (inputClass == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )) || (inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

        val isNumericOnly = inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE ||
            inputClass == InputType.TYPE_CLASS_DATETIME

        val noSuggestionsFlag = (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        // Keep QuickType predictions available in normal, search, filter, and web text fields
        // (hide only for passwords & pure numeric/phone inputs). Honor NO_SUGGESTIONS by disabling autocorrect.
        fieldSupportsPredictions = !isPassword && !isNumericOnly
        fieldSupportsAutoCorrect = fieldSupportsPredictions && !noSuggestionsFlag && currentVariant == InputVariant.NORMAL

        fieldSupportsAutoCap = !isPassword &&
            currentVariant == InputVariant.NORMAL &&
            (inputClass == InputType.TYPE_CLASS_TEXT || inputClass == InputType.TYPE_NULL)

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

    private fun refreshSuggestions(textBeforeOverride: CharSequence? = null) {
        val kb = keyboardView ?: return
        if (!prefs.predictionBarEnabled || !fieldSupportsPredictions) {
            kb.setSuggestions(emptyList())
            return
        }

        // Prefer our zero-latency local composing buffer whenever active!
        val currentWord: String
        val prevWord: String
        if (composingWord.isNotEmpty()) {
            currentWord = composingWord.toString()
            prevWord = lastCompletedWord
        } else {
            val textBefore = textBeforeOverride ?: currentInputConnection?.getTextBeforeCursor(64, 0)
            currentWord = inputController.extractCurrentWord(textBefore)
            prevWord = inputController.extractPreviousWord(textBefore).ifEmpty {
                if (currentWord.isEmpty()) lastCompletedWord else ""
            }
        }

        kb.setSuggestions(
            suggestionEngine.suggest(
                currentWord = currentWord,
                maxResults = 3,
                previousWord = prevWord
            )
        )
    }

    private fun pushKeyboardState() {
        keyboardView?.setKeyboardState(
            mode = inputController.keyboardMode,
            variant = currentVariant,
            shift = inputController.shiftState,
            returnAction = currentReturnAction
        )
    }

    private fun registerBlurListenerIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            hardwareBlurEnabled = false
            return
        }
        val wm = window?.window?.windowManager ?: return
        hardwareBlurEnabled = try {
            wm.isCrossWindowBlurEnabled
        } catch (_: Throwable) {
            false
        }
        if (!blurListenerRegistered) {
            try {
                wm.addCrossWindowBlurEnabledListener(mainExecutor, blurEnabledListener)
                blurListenerRegistered = true
            } catch (_: Throwable) {
                blurListenerRegistered = false
            }
        }
    }

    private fun unregisterBlurListenerIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurListenerRegistered) {
            try {
                window?.window?.windowManager?.removeCrossWindowBlurEnabledListener(blurEnabledListener)
            } catch (_: Throwable) {
                // Ignore if window already torn down
            }
            blurListenerRegistered = false
        }
    }

    private fun syncPreferencesToEnginesAndUi() {
        soundEngine.enabled = prefs.soundEnabled
        soundEngine.volume = prefs.soundVolume
        hapticEngine.strength = prefs.hapticStrength

        val theme = IOSKeyboardTheme.resolve(
            context = this,
            mode = prefs.themeMode,
            style = prefs.keyboardStyle,
            blurIntensity = prefs.blurIntensity
        )
        val useLiveBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            theme.useWindowBlur &&
            hardwareBlurEnabled &&
            !isFullscreenMode

        applyWindowSurfaceAndBlur(theme, useLiveBlur)

        keyboardView?.applyConfig(
            theme = theme,
            hardwareBlurActive = useLiveBlur,
            popupEnabled = prefs.keyPopupEnabled,
            suggestionBarEnabled = prefs.predictionBarEnabled && fieldSupportsPredictions,
            bottomChinEnabled = prefs.bottomChinEnabled,
            heightPreset = prefs.heightPreset
        )
        emojiPanelView?.applyTheme(theme, hardwareBlurActive = useLiveBlur)
        syncNavigationBarWithTheme(theme, useLiveBlur)
    }

    private fun applyWindowSurfaceAndBlur(theme: IOSKeyboardTheme, useLiveBlur: Boolean) {
        val imeWindow = window?.window ?: return
        val density = resources.displayMetrics.density
        imeWindow.setFormat(PixelFormat.TRANSLUCENT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useLiveBlur) {
            val topR = theme.topCornerRadiusDp * density
            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(topR, topR, topR, topR, 0f, 0f, 0f, 0f)
                setColor(theme.effectiveBackdropColor(hardwareBlurActive = true))
            }
            imeWindow.setBackgroundDrawable(bgDrawable)
            val blurPx = (theme.blurIntensity.blurRadiusDp * density).toInt().coerceIn(12, 140)
            try {
                imeWindow.setBackgroundBlurRadius(blurPx)
            } catch (_: Throwable) {
                // Fallback gracefully if OEM blocks blur radius
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    imeWindow.setBackgroundBlurRadius(0)
                } catch (_: Throwable) {
                    // Ignore
                }
            }
            // Transparent window background lets our Canvas rounded-top glass/frosted surface clip cleanly
            imeWindow.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun syncNavigationBarWithTheme(theme: IOSKeyboardTheme, useLiveBlur: Boolean) {
        val imeWindow = window?.window ?: return
        imeWindow.navigationBarColor = if (theme.isTranslucentStyle) {
            theme.effectiveBackdropColor(hardwareBlurActive = useLiveBlur)
        } else {
            theme.keyboardBg
        }
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

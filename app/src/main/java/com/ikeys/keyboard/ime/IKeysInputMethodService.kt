package com.ikeys.keyboard.ime

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/**
 * Core Android InputMethodService for iKeys.
 *
 * Ultra-low-latency architecture:
 * - ZERO synchronous Binder IPC (getTextBeforeCursor/getSelectedText) on the hot typing path!
 *   Characters hit the screen immediately on frame 0 via local shadow context + setComposingText.
 * - Background suggestion worker with monotonic sequence counter so QuickType never stalls typing.
 * - Real Android 12+ cross-window blur (clearing opaque system inputArea backgrounds) + rich Liquid Glass / Frosted Blur.
 * - 100% reliable Globe switcher (tap = next keyboard, long-press = system picker).
 */
class IKeysInputMethodService : InputMethodService(), IOSKeyboardView.Callback, IOSEmojiPanelView.Callback {

    private lateinit var prefs: IKeysPreferences
    private lateinit var soundEngine: IOSSoundEngine
    private lateinit var hapticEngine: IOSHapticEngine
    private val suggestionEngine = SuggestionEngine()
    private val inputController = TextInputController()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val suggestionExecutor = Executors.newSingleThreadExecutor()
    private val suggestionSeq = AtomicLong(0L)

    private var rootContainer: FrameLayout? = null
    private var keyboardView: IOSKeyboardView? = null
    private var emojiPanelView: IOSEmojiPanelView? = null

    private var currentVariant: InputVariant = InputVariant.NORMAL
    private var currentReturnAction: ReturnAction = ReturnAction.RETURN
    private var fieldSupportsAutoCap: Boolean = true
    private var fieldSupportsPredictions: Boolean = true
    private var fieldSupportsAutoCorrect: Boolean = true
    private var activeImeActionId: Int = EditorInfo.IME_ACTION_NONE

    // Local zero-IPC typing shadow state (eliminates synchronous getTextBeforeCursor stalls!)
    private val composingWord = StringBuilder(32)
    private val localTail = StringBuilder(64)
    private var lastCompletedWord: String = ""
    private var prevCompletedWord: String = ""
    private var hasActiveSelection: Boolean = false
    private var lastLocalEditMs: Long = 0L
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
        val savedCsv = prefs.learnedWordsCsv
        if (savedCsv.isNotBlank()) {
            suggestionExecutor.execute { suggestionEngine.loadUserWordsCsv(savedCsv) }
        }
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
        refreshSuggestionsAsync()
        return container
    }

    override fun onWindowShown() {
        super.onWindowShown()
        soundEngine.refreshRingerMode()
        clearOpaqueSystemInputParents()
        registerBlurListenerIfSupported()
        syncPreferencesToEnginesAndUi()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inspectEditorInfo(info)
        clearOpaqueSystemInputParents()
        composingWord.setLength(0)
        hasActiveSelection = false

        val textBefore = currentInputConnection?.getTextBeforeCursor(64, 0)
        resetLocalTailFrom(textBefore)
        syncPreviousWordsFrom(textBefore)

        inputController.resetForNewInput(
            textBeforeCursor = textBefore,
            autoCapEnabled = prefs.autoCapitalize,
            fieldSupportsAutoCap = fieldSupportsAutoCap
        )
        syncPreferencesToEnginesAndUi()
        showEmojiPanel(false)
        pushKeyboardState()
        refreshSuggestionsAsync()
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
        refreshSuggestionsAsync()
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

        hasActiveSelection = newSelStart != newSelEnd
        val now = SystemClock.uptimeMillis()

        // Fast-path: ignore delayed selection echoes triggered by our own recent keystrokes!
        // This eliminates redundant cross-process getTextBeforeCursor() Binder stalls during typing.
        if (!hasActiveSelection && now - lastLocalEditMs < 350L) {
            return
        }

        val textBefore = currentInputConnection?.getTextBeforeCursor(64, 0)
        if (composingWord.isNotEmpty()) {
            if (hasActiveSelection || (textBefore != null && !textBefore.endsWith(composingWord.toString()))) {
                composingWord.setLength(0)
                currentInputConnection?.finishComposingText()
            }
        }

        if (textBefore != null) {
            resetLocalTailFrom(textBefore)
            if (composingWord.isEmpty()) {
                syncPreviousWordsFrom(textBefore)
            }
        }

        inputController.updateAutoShift(
            textBeforeCursor = localTail,
            autoCapEnabled = prefs.autoCapitalize,
            fieldSupportsAutoCap = fieldSupportsAutoCap
        )
        pushKeyboardState()
        refreshSuggestionsAsync()
    }

    override fun onDestroy() {
        unregisterBlurListenerIfSupported()
        flushLearnedWordsIfNeeded(force = true)
        prefs.unregisterListener(prefListener)
        soundEngine.release()
        suggestionExecutor.shutdownNow()
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

    /**
     * Zero-delay character output: immediately dispatches text to InputConnection without
     * blocking on synchronous getTextBeforeCursor IPC or main-thread suggestion computation.
     */
    override fun onCharacter(text: String) {
        val ic = currentInputConnection ?: return
        val isWordChar = text.isNotEmpty() && text.all { it.isLetter() || it == '\'' }
        lastLocalEditMs = SystemClock.uptimeMillis()

        if (fieldSupportsPredictions && prefs.predictionBarEnabled && isWordChar) {
            composingWord.append(text)
            ic.setComposingText(composingWord.toString(), 1)
            appendLocalTail(text)
        } else {
            commitComposingIfNeeded(ic)
            ic.commitText(text, 1)
            appendLocalTail(text)
            if (text.any { it == '.' || it == '?' || it == '!' || it == '\n' }) {
                prevCompletedWord = ""
                lastCompletedWord = ""
            }
        }

        hasActiveSelection = false
        inputController.onCharacterCommitted(text)
        pushKeyboardState()
        refreshSuggestionsAsync()
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
                inputController.updateAutoShift(localTail, prefs.autoCapitalize, fieldSupportsAutoCap)
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
        lastLocalEditMs = SystemClock.uptimeMillis()

        // 1. Delete active text selection only when one is known to exist
        if (hasActiveSelection) {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                composingWord.setLength(0)
                hasActiveSelection = false
                ic.commitText("", 1)
                val after = ic.getTextBeforeCursor(64, 0)
                resetLocalTailFrom(after)
                syncPreviousWordsFrom(after)
                inputController.updateAutoShift(localTail, prefs.autoCapitalize, fieldSupportsAutoCap)
                pushKeyboardState()
                refreshSuggestionsAsync()
                return
            }
            hasActiveSelection = false
        }

        // 2. Zero-IPC fast composing backspace!
        if (composingWord.isNotEmpty()) {
            if (deleteWord) {
                val len = composingWord.length
                composingWord.setLength(0)
                ic.commitText("", 1)
                trimLocalTail(len)
            } else {
                composingWord.deleteCharAt(composingWord.length - 1)
                if (composingWord.isNotEmpty()) {
                    ic.setComposingText(composingWord.toString(), 1)
                } else {
                    ic.commitText("", 1)
                }
                trimLocalTail(1)
            }
        } else {
            // 3. Delete before cursor cleanly (surrogate-pair & ZWJ emoji safe)
            val textBefore = if (localTail.isNotEmpty()) localTail else ic.getTextBeforeCursor(64, 0)
            val count = if (deleteWord) {
                TextInputController.computeWordBackspaceCount(textBefore)
            } else {
                TextInputController.computeSingleBackspaceCount(textBefore)
            }
            ic.deleteSurroundingText(count, 0)
            trimLocalTail(count)
            if (localTail.isEmpty()) {
                val refreshed = ic.getTextBeforeCursor(64, 0)
                resetLocalTailFrom(refreshed)
            }
            syncPreviousWordsFrom(localTail)
        }

        inputController.updateAutoShift(localTail, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestionsAsync()
    }

    override fun onSuggestionPicked(item: SuggestionItem) {
        val ic = currentInputConnection ?: return
        val picked = item.text
        lastLocalEditMs = SystemClock.uptimeMillis()
        hasActiveSelection = false

        ic.beginBatchEdit()
        if (composingWord.isNotEmpty()) {
            val prevLen = composingWord.length
            ic.commitText("$picked ", 1)
            composingWord.setLength(0)
            trimLocalTail(prevLen)
            appendLocalTail("$picked ")
        } else {
            val existingWord = inputController.extractCurrentWord(localTail)
            if (existingWord.isNotEmpty()) {
                ic.deleteSurroundingText(existingWord.length, 0)
                trimLocalTail(existingWord.length)
            }
            ic.commitText("$picked ", 1)
            appendLocalTail("$picked ")
        }
        ic.endBatchEdit()

        recordLearnedWordAndBigramAsync(picked, boost = 30)
        advanceCompletedWords(picked)

        inputController.updateAutoShift(localTail, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestionsAsync()
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
        val textBefore = currentInputConnection?.getTextBeforeCursor(64, 0)
        resetLocalTailFrom(textBefore)
        syncPreviousWordsFrom(textBefore)
        inputController.updateAutoShift(localTail, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestionsAsync()
    }

    override fun onPopupSlideTick() {
        hapticEngine.microTick()
    }

    // --- IOSEmojiPanelView.Callback ---

    override fun onEmojiSelected(emoji: String) {
        soundEngine.play(IOSSoundEngine.SoundType.KEY_CLICK)
        hapticEngine.keyTap()
        lastLocalEditMs = SystemClock.uptimeMillis()
        currentInputConnection?.commitText(emoji, 1)
        appendLocalTail(emoji)
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
        lastLocalEditMs = SystemClock.uptimeMillis()
        hasActiveSelection = false

        val activeWord = if (composingWord.isNotEmpty()) {
            composingWord.toString()
        } else {
            inputController.extractCurrentWord(localTail)
        }

        // High-confidence autocorrect on space (contractions, shorthands, clear 1-edit typos)
        if (prefs.autoCorrect && fieldSupportsAutoCorrect && activeWord.length >= 2) {
            val replacement = suggestionEngine.autoCorrectFor(activeWord, previousWord = lastCompletedWord)
            if (replacement != null && !replacement.equals(activeWord, ignoreCase = false)) {
                ic.beginBatchEdit()
                if (composingWord.isNotEmpty()) {
                    ic.commitText("$replacement ", 1)
                    trimLocalTail(composingWord.length)
                    composingWord.setLength(0)
                } else {
                    ic.deleteSurroundingText(activeWord.length, 0)
                    trimLocalTail(activeWord.length)
                    ic.commitText("$replacement ", 1)
                }
                ic.endBatchEdit()
                appendLocalTail("$replacement ")
                recordLearnedWordAndBigramAsync(replacement, boost = 15)
                advanceCompletedWords(replacement)
                postSpaceUpdate()
                return
            }
        }

        if (composingWord.isNotEmpty()) {
            ic.finishComposingText()
            composingWord.setLength(0)
        }

        if (activeWord.length >= 2) {
            recordLearnedWordAndBigramAsync(activeWord, boost = 10)
            advanceCompletedWords(activeWord)
        }

        val action = inputController.onSpaceTap(
            nowMs = lastLocalEditMs,
            textBeforeCursor = localTail,
            doubleSpacePeriodEnabled = prefs.doubleSpacePeriod
        )

        if (action.shouldInsertPeriodInstead) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            trimLocalTail(1)
            appendLocalTail(". ")
            prevCompletedWord = ""
            lastCompletedWord = ""
        } else {
            ic.commitText(" ", 1)
            appendLocalTail(" ")
        }

        postSpaceUpdate()
    }

    private fun postSpaceUpdate() {
        inputController.updateAutoShift(localTail, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestionsAsync()
    }

    private fun handleReturnKey() {
        val ic = currentInputConnection ?: return
        lastLocalEditMs = SystemClock.uptimeMillis()
        commitComposingIfNeeded(ic)

        val info = currentInputEditorInfo
        val isMultiLine = info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0

        if (!isMultiLine && activeImeActionId != EditorInfo.IME_ACTION_NONE && activeImeActionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(activeImeActionId)
        } else {
            ic.commitText("\n", 1)
            appendLocalTail("\n")
        }

        prevCompletedWord = ""
        lastCompletedWord = ""
        inputController.updateAutoShift(localTail, prefs.autoCapitalize, fieldSupportsAutoCap)
        pushKeyboardState()
        refreshSuggestionsAsync()
    }

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
            }
        }

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
            lastLocalEditMs = SystemClock.uptimeMillis()
            if (word.length >= 2) {
                recordLearnedWordAndBigramAsync(word, boost = 10)
                advanceCompletedWords(word)
            }
        }
    }

    private fun advanceCompletedWords(word: String) {
        prevCompletedWord = lastCompletedWord
        lastCompletedWord = word
    }

    private fun recordLearnedWordAndBigramAsync(word: String, boost: Int) {
        val prev = lastCompletedWord
        suggestionExecutor.execute {
            suggestionEngine.learnWord(word, boost = boost)
            if (prev.isNotEmpty()) {
                suggestionEngine.learnTransition(prev, word, boost = boost)
            }
        }
        learnedWordsDirtyCount++
        if (learnedWordsDirtyCount >= 6) {
            flushLearnedWordsIfNeeded(force = false)
        }
    }

    private fun flushLearnedWordsIfNeeded(force: Boolean) {
        if (learnedWordsDirtyCount == 0 && !force) return
        if (learnedWordsDirtyCount > 0) {
            learnedWordsDirtyCount = 0
            suggestionExecutor.execute {
                val csv = suggestionEngine.exportUserWordsCsv(maxWords = 200)
                if (csv.isNotEmpty()) {
                    prefs.learnedWordsCsv = csv
                }
            }
        }
    }

    private fun resetLocalTailFrom(text: CharSequence?) {
        localTail.setLength(0)
        if (!text.isNullOrEmpty()) {
            val start = (text.length - MAX_LOCAL_TAIL_LEN).coerceAtLeast(0)
            localTail.append(text, start, text.length)
        }
    }

    private fun appendLocalTail(str: CharSequence) {
        localTail.append(str)
        if (localTail.length > MAX_LOCAL_TAIL_LEN) {
            localTail.delete(0, localTail.length - MAX_LOCAL_TAIL_LEN)
        }
    }

    private fun trimLocalTail(count: Int) {
        if (count <= 0 || localTail.isEmpty()) return
        val remove = count.coerceAtMost(localTail.length)
        localTail.delete(localTail.length - remove, localTail.length)
    }

    private fun syncPreviousWordsFrom(text: CharSequence?) {
        val (p2, p1) = inputController.extractPreviousTwoWords(text)
        prevCompletedWord = p2
        lastCompletedWord = p1
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

    /**
     * Computes QuickType suggestions asynchronously off the UI thread so keystrokes
     * ALWAYS hit the screen immediately with zero latency.
     */
    private fun refreshSuggestionsAsync() {
        val kb = keyboardView ?: return
        if (!prefs.predictionBarEnabled || !fieldSupportsPredictions) {
            suggestionSeq.incrementAndGet()
            kb.setSuggestions(emptyList())
            return
        }

        val currentWord = if (composingWord.isNotEmpty()) {
            composingWord.toString()
        } else {
            inputController.extractCurrentWord(localTail)
        }
        val prev1 = lastCompletedWord
        val prev2 = prevCompletedWord
        val token = suggestionSeq.incrementAndGet()

        suggestionExecutor.execute {
            val results = suggestionEngine.suggest(
                currentWord = currentWord,
                maxResults = 3,
                previousWord = prev1,
                prevPreviousWord = prev2
            )
            if (suggestionSeq.get() == token) {
                mainHandler.post {
                    if (suggestionSeq.get() == token) {
                        keyboardView?.setSuggestions(results)
                    }
                }
            }
        }
    }

    private fun pushKeyboardState() {
        keyboardView?.setKeyboardState(
            mode = inputController.keyboardMode,
            variant = currentVariant,
            shift = inputController.shiftState,
            returnAction = currentReturnAction
        )
    }

    /**
     * Clears any opaque system background on InputMethodService's internal inputFrame / inputArea
     * parents so Android 12+ live cross-window blur & rounded top corners are genuinely visible!
     */
    private fun clearOpaqueSystemInputParents() {
        val decor = window?.window?.decorView
        var current = rootContainer?.parent
        var hops = 0
        while (current is View && current !== decor && hops < 5) {
            current.background = null
            current = current.parent
            hops++
        }
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
        imeWindow.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && useLiveBlur) {
            val topR = theme.topCornerRadiusDp * density
            val bgDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(topR, topR, topR, topR, 0f, 0f, 0f, 0f)
                setColor(theme.effectiveBackdropColor(hardwareBlurActive = true))
            }
            imeWindow.setBackgroundDrawable(bgDrawable)
            val blurPx = (theme.blurIntensity.blurRadiusDp * density).toInt().coerceIn(16, 150)
            try {
                imeWindow.setBackgroundBlurRadius(blurPx)
            } catch (_: Throwable) {
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    imeWindow.setBackgroundBlurRadius(0)
                } catch (_: Throwable) {
                }
            }
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

    companion object {
        private const val MAX_LOCAL_TAIL_LEN = 64
    }
}

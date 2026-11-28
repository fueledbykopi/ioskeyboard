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
 * - Direct commitText / raw key events for Termux, terminals, TYPE_NULL, NO_SUGGESTIONS, and custom editors
 *   so characters appear immediately with ZERO composing-buffer delay.
 * - ZERO synchronous Binder IPC (getTextBeforeCursor/getSelectedText/ringerMode/vibrator) on the typing hot path.
 * - Lock-free cached autocorrect + coalesced background QuickType worker so Spacebar never stalls the UI thread.
 * - Real Android 12+ cross-window blur (capped for 120fps) + iOS 26 Liquid Glass / Frosted Blur / Classic styles.
 * - 100% reliable Globe switcher (tap = next keyboard, long-press = system picker).
 */
class IKeysInputMethodService : InputMethodService(), IOSKeyboardView.Callback, IOSEmojiPanelView.Callback {

    private lateinit var prefs: IKeysPreferences
    private lateinit var soundEngine: IOSSoundEngine
    private lateinit var hapticEngine: IOSHapticEngine
    private val suggestionEngine = SuggestionEngine(deferDictionaryLoad = true)
    private val inputController = TextInputController()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val suggestionExecutor = Executors.newSingleThreadExecutor()
    private val suggestionSeq = AtomicLong(0L)

    private var rootContainer: FrameLayout? = null
    private var keyboardView: IOSKeyboardView? = null
    private var emojiPanelView: IOSEmojiPanelView? = null

    private var currentVariant: InputVariant = InputVariant.NORMAL
    private var currentReturnAction: ReturnAction = ReturnAction.RETURN
    private var isRawTerminalInput: Boolean = false
    private var fieldSupportsComposing: Boolean = true
    private var fieldSupportsAutoCap: Boolean = true
    private var fieldSupportsPredictions: Boolean = true
    private var fieldSupportsAutoCorrect: Boolean = true
    private var fieldSupportsDoubleSpacePeriod: Boolean = true
    private var activeImeActionId: Int = EditorInfo.IME_ACTION_NONE

    // Local zero-IPC typing shadow state (eliminates synchronous getTextBeforeCursor stalls!)
    private val composingWord = StringBuilder(32)
    private val localTail = StringBuilder(64)
    private var lastCompletedWord: String = ""
    private var prevCompletedWord: String = ""
    private var hasActiveSelection: Boolean = false
    private var lastLocalEditMs: Long = 0L
    private var learnedWordsDirtyCount: Int = 0

    // Lock-free cached autocorrect candidate populated by background QuickType worker
    @Volatile
    private var cachedAutoCorrectInput: String = ""
    @Volatile
    private var cachedAutoCorrectTarget: String? = null

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
        if (key != "learned_words_csv" && key != "learned_bigrams_csv") {
            syncPreferencesToEnginesAndUi()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = IKeysPreferences(this)
        soundEngine = IOSSoundEngine(this)
        hapticEngine = IOSHapticEngine(this)

        val savedWordsCsv = prefs.learnedWordsCsv
        val savedBigramsCsv = prefs.learnedBigramsCsv
        suggestionExecutor.execute {
            suggestionEngine.ensureDictionaryLoaded()
            if (savedWordsCsv.isNotBlank()) {
                suggestionEngine.loadUserWordsCsv(savedWordsCsv)
            }
            if (savedBigramsCsv.isNotBlank()) {
                suggestionEngine.loadUserBigramsCsv(savedBigramsCsv)
            }
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
        clearAutoCorrectCache()
        hasActiveSelection = false

        val textBefore = if (!isRawTerminalInput && fieldSupportsPredictions) {
            currentInputConnection?.getTextBeforeCursor(64, 0)
        } else {
            null
        }
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
        keyboardView?.onOrientationOrMetricsChanged()
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
        if (isRawTerminalInput || !fieldSupportsPredictions) {
            return
        }

        val now = SystemClock.uptimeMillis()

        // Fast-path: ignore delayed selection echoes triggered by our own recent keystrokes!
        if (!hasActiveSelection && now - lastLocalEditMs < 450L) {
            return
        }

        val textBefore = currentInputConnection?.getTextBeforeCursor(64, 0)
        if (composingWord.isNotEmpty()) {
            if (hasActiveSelection || (textBefore != null && !textBefore.endsWith(composingWord.toString()))) {
                composingWord.setLength(0)
                clearAutoCorrectCache()
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
        hapticEngine.release()
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
     * Zero-delay character output:
     * - In Termux, terminals, TYPE_NULL, NO_SUGGESTIONS, URI/Email, and custom editors:
     *   commits directly via ic.commitText(text, 1) with ZERO composing buffer delay!
     * - In standard rich text fields with QuickType active: updates composing span on frame 0
     *   with zero synchronous Binder reads.
     */
    override fun onCharacter(text: String) {
        val ic = currentInputConnection ?: return
        val isWordChar = text.isNotEmpty() && text.all { it.isLetter() || it == '\'' }
        lastLocalEditMs = SystemClock.uptimeMillis()

        if (fieldSupportsComposing && prefs.predictionBarEnabled && isWordChar) {
            composingWord.append(text)
            ic.setComposingText(composingWord.toString(), 1)
            appendLocalTail(text)
        } else {
            if (composingWord.isNotEmpty()) {
                commitComposingIfNeeded(ic)
            }
            ic.commitText(text, 1)
            appendLocalTail(text)
            if (text.any { it == '.' || it == '?' || it == '!' || it == '\n' }) {
                prevCompletedWord = ""
                lastCompletedWord = ""
                clearAutoCorrectCache()
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

        // 0. Raw terminal / Termux fast-path: send hardware KEYCODE_DEL directly (zero IPC delay!)
        if (isRawTerminalInput) {
            composingWord.setLength(0)
            hasActiveSelection = false
            val count = if (deleteWord) {
                TextInputController.computeWordBackspaceCount(localTail).coerceIn(1, 16)
            } else {
                1
            }
            for (i in 0 until count) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            trimLocalTail(count)
            return
        }

        // 1. Delete active text selection only when one is known to exist
        if (hasActiveSelection) {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                composingWord.setLength(0)
                clearAutoCorrectCache()
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
                clearAutoCorrectCache()
                ic.commitText("", 1)
                trimLocalTail(len)
            } else {
                composingWord.deleteCharAt(composingWord.length - 1)
                if (composingWord.isNotEmpty()) {
                    ic.setComposingText(composingWord.toString(), 1)
                } else {
                    clearAutoCorrectCache()
                    ic.commitText("", 1)
                }
                trimLocalTail(1)
            }
        } else {
            // 3. Delete before cursor cleanly (surrogate-pair & ZWJ emoji safe, minimal IPC)
            val textBefore = if (localTail.isNotEmpty()) localTail else ic.getTextBeforeCursor(64, 0)
            val count = if (deleteWord) {
                TextInputController.computeWordBackspaceCount(textBefore)
            } else {
                TextInputController.computeSingleBackspaceCount(textBefore)
            }
            ic.deleteSurroundingText(count, 0)
            trimLocalTail(count)
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
        clearAutoCorrectCache()

        if (composingWord.isNotEmpty()) {
            val prevLen = composingWord.length
            ic.commitText("$picked ", 1)
            composingWord.setLength(0)
            trimLocalTail(prevLen)
            appendLocalTail("$picked ")
        } else {
            val existingWord = inputController.extractCurrentWord(localTail)
            if (existingWord.isNotEmpty()) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(existingWord.length, 0)
                ic.commitText("$picked ", 1)
                ic.endBatchEdit()
                trimLocalTail(existingWord.length)
                appendLocalTail("$picked ")
            } else {
                ic.commitText("$picked ", 1)
                appendLocalTail("$picked ")
            }
        }

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
        val keyCode = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val count = kotlin.math.abs(steps).coerceAtMost(8)
        for (i in 0 until count) {
            sendDownUpKeyEvents(keyCode)
        }
        hapticEngine.microTick()
    }

    override fun onTrackpadEnd() {
        if (isRawTerminalInput || !fieldSupportsPredictions) return
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

        // Raw terminal / Termux fast-path: send space immediately, never autocorrect or double-space-period!
        if (isRawTerminalInput) {
            ic.commitText(" ", 1)
            appendLocalTail(" ")
            val action = inputController.onSpaceTap(
                nowMs = lastLocalEditMs,
                textBeforeCursor = localTail,
                doubleSpacePeriodEnabled = false
            )
            if (action.shouldReturnToAlpha) {
                pushKeyboardState()
            }
            return
        }

        val activeWord = if (composingWord.isNotEmpty()) {
            composingWord.toString()
        } else {
            inputController.extractCurrentWord(localTail)
        }

        // High-confidence autocorrect on space (lock-free cached lookup first, read-lock fallback if needed!)
        if (prefs.autoCorrect && fieldSupportsAutoCorrect && activeWord.isNotEmpty()) {
            val replacement = if (cachedAutoCorrectInput == activeWord) {
                cachedAutoCorrectTarget
            } else {
                suggestionEngine.autoCorrectFor(activeWord, previousWord = lastCompletedWord)
            }
            if (replacement != null && !replacement.equals(activeWord, ignoreCase = false)) {
                if (composingWord.isNotEmpty()) {
                    // Single atomic IPC replaces composing span + appends space!
                    ic.commitText("$replacement ", 1)
                    trimLocalTail(composingWord.length)
                    composingWord.setLength(0)
                } else {
                    ic.beginBatchEdit()
                    ic.deleteSurroundingText(activeWord.length, 0)
                    ic.commitText("$replacement ", 1)
                    ic.endBatchEdit()
                    trimLocalTail(activeWord.length)
                }
                clearAutoCorrectCache()
                appendLocalTail("$replacement ")
                recordLearnedWordAndBigramAsync(replacement, boost = 15)
                advanceCompletedWords(replacement)
                postSpaceUpdate()
                return
            }
        }

        val hadComposing = composingWord.isNotEmpty()
        if (hadComposing) {
            // Single atomic IPC finishes composing + inserts space!
            val word = composingWord.toString()
            ic.commitText("$word ", 1)
            composingWord.setLength(0)
            clearAutoCorrectCache()
            appendLocalTail(" ")
            if (word.length >= 2) {
                recordLearnedWordAndBigramAsync(word, boost = 10)
                advanceCompletedWords(word)
            }
            inputController.onSpaceTap(
                nowMs = lastLocalEditMs,
                textBeforeCursor = localTail,
                doubleSpacePeriodEnabled = false
            )
            postSpaceUpdate()
            return
        }

        if (activeWord.length >= 2) {
            recordLearnedWordAndBigramAsync(activeWord, boost = 10)
            advanceCompletedWords(activeWord)
        }

        val action = inputController.onSpaceTap(
            nowMs = lastLocalEditMs,
            textBeforeCursor = localTail,
            doubleSpacePeriodEnabled = prefs.doubleSpacePeriod && fieldSupportsDoubleSpacePeriod
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

        clearAutoCorrectCache()
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

        // Raw terminal / Termux fast-path: send real KEYCODE_ENTER (\r) directly!
        if (isRawTerminalInput) {
            composingWord.setLength(0)
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            appendLocalTail("\n")
            prevCompletedWord = ""
            lastCompletedWord = ""
            return
        }

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
        clearAutoCorrectCache()
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
            clearAutoCorrectCache()
            lastLocalEditMs = SystemClock.uptimeMillis()
            if (word.length >= 2) {
                recordLearnedWordAndBigramAsync(word, boost = 10)
                advanceCompletedWords(word)
            }
        }
    }

    private fun clearAutoCorrectCache() {
        cachedAutoCorrectInput = ""
        cachedAutoCorrectTarget = null
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
                val wordsCsv = suggestionEngine.exportUserWordsCsv(maxWords = 250)
                if (wordsCsv.isNotEmpty()) {
                    prefs.learnedWordsCsv = wordsCsv
                }
                val bigramsCsv = suggestionEngine.exportUserBigramsCsv(maxEntries = 150)
                if (bigramsCsv.isNotEmpty()) {
                    prefs.learnedBigramsCsv = bigramsCsv
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
            isRawTerminalInput = false
            fieldSupportsComposing = true
            fieldSupportsAutoCap = true
            fieldSupportsPredictions = true
            fieldSupportsAutoCorrect = true
            fieldSupportsDoubleSpacePeriod = true
            activeImeActionId = EditorInfo.IME_ACTION_NONE
            return
        }

        val inputType = info.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val pkg = info.packageName.orEmpty().lowercase()

        val isTerminalPackage = pkg == "com.termux" ||
            pkg.startsWith("com.termux.") ||
            pkg.contains("terminal") ||
            pkg.contains("connectbot") ||
            pkg.contains("juicessh") ||
            pkg.contains("termius")

        val isRawInput = inputClass == InputType.TYPE_NULL || isTerminalPackage

        currentVariant = when {
            isRawInput -> InputVariant.NORMAL
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
        val isFilterVariation = inputClass == InputType.TYPE_CLASS_TEXT &&
            variation == InputType.TYPE_TEXT_VARIATION_FILTER
        val hasNoSelectionBuffer = info.initialSelStart < 0 && info.initialSelEnd < 0

        isRawTerminalInput = isRawInput
        fieldSupportsPredictions = !isRawInput && !isPassword && !isNumericOnly && !noSuggestionsFlag && !isFilterVariation
        fieldSupportsComposing = fieldSupportsPredictions &&
            currentVariant == InputVariant.NORMAL &&
            !hasNoSelectionBuffer
        fieldSupportsAutoCorrect = fieldSupportsComposing
        fieldSupportsDoubleSpacePeriod = !isRawInput &&
            !isPassword &&
            !isNumericOnly &&
            !noSuggestionsFlag &&
            currentVariant == InputVariant.NORMAL

        fieldSupportsAutoCap = !isRawInput &&
            !isPassword &&
            !isNumericOnly &&
            currentVariant == InputVariant.NORMAL &&
            inputClass == InputType.TYPE_CLASS_TEXT

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        activeImeActionId = if (isRawInput || noEnterAction) EditorInfo.IME_ACTION_NONE else action

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
     * Drops superseded burst keystroke jobs BEFORE running suggestion lookup!
     */
    private fun refreshSuggestionsAsync() {
        val kb = keyboardView ?: return
        if (!prefs.predictionBarEnabled || !fieldSupportsPredictions) {
            suggestionSeq.incrementAndGet()
            clearAutoCorrectCache()
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
            // Skip stale queued keystrokes immediately before doing any work!
            if (suggestionSeq.get() != token) return@execute

            val results = suggestionEngine.suggest(
                currentWord = currentWord,
                maxResults = 3,
                previousWord = prev1,
                prevPreviousWord = prev2
            )

            if (suggestionSeq.get() == token) {
                val acCandidate = results.firstOrNull { it.isAutoCorrectCandidate }?.text
                cachedAutoCorrectInput = currentWord
                cachedAutoCorrectTarget = acCandidate

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
            // Cap blur radius at 72px to preserve buttery 120fps compositing on all GPUs
            val blurPx = (theme.blurIntensity.blurRadiusDp * density).toInt().coerceIn(16, 72)
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

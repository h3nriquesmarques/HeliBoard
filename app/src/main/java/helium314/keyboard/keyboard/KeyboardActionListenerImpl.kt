// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.text.InputType
import android.util.SparseArray
import android.view.KeyEvent
import android.view.inputmethod.InputMethodSubtype
import androidx.core.util.forEach
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import helium314.keyboard.event.Event
import helium314.keyboard.event.HangulEventDecoder
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.event.HardwareEventDecoder
import helium314.keyboard.event.HardwareKeyboardEventDecoder
import helium314.keyboard.keyboard.internal.LayoutDirective
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.EmojiAltPhysicalKeyDetector
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.RichInputMethodManager
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.suggestions.SuggestionStripLayoutHelper
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.common.combiningRange
import helium314.keyboard.latin.common.moveStepsToCharCount
import helium314.keyboard.latin.define.ProductionFlags
import helium314.keyboard.latin.inputlogic.InputLogic
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackgroundGatheringCache
import helium314.keyboard.latin.utils.GestureDataGatheringSettings
import helium314.keyboard.latin.utils.RecapitalizeMode
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.prefs
import kotlin.math.abs

class KeyboardActionListenerImpl(private val latinIME: LatinIME, private val inputLogic: InputLogic) : KeyboardActionListener {

    private val connection = inputLogic.mConnection
    private val emojiAltPhysicalKeyDetector by lazy { EmojiAltPhysicalKeyDetector(latinIME.resources) }

    // We expect to have only one decoder in almost all cases, hence the default capacity of 1.
    // If it turns out we need several, it will get grown seamlessly.
    private val hardwareEventDecoders: SparseArray<HardwareEventDecoder> = SparseArray(1)

    private val keyboardSwitcher = KeyboardSwitcher.getInstance()
    private val settings = Settings.getInstance()
    private val audioAndHapticFeedbackManager = AudioAndHapticFeedbackManager.getInstance()

    // language slide state
    private var initialSubtype: InputMethodSubtype? = null
    private var subtypeSwitchCount = 0

    override fun onPressKey(primaryCode: Int, repeatCount: Int, pointerCount: Int, hapticEvent: HapticEvent) {
        metaOnPressKey(primaryCode)
        keyboardSwitcher.onPressKey(primaryCode, pointerCount, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
        // we need to use LatinIME for handling of key-down audio and haptics
        latinIME.hapticAndAudioFeedback(primaryCode, repeatCount, hapticEvent)
    }

    override fun onLongPressKey(primaryCode: Int) {
        metaOnLongPressKey(primaryCode)
        performHapticFeedback(HapticEvent.KEY_LONG_PRESS)
    }

    override fun onReleaseKey(primaryCode: Int, withSliding: Boolean) {
        metaOnReleaseKey(primaryCode)
        keyboardSwitcher.onReleaseKey(primaryCode, withSliding, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
    }

    override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent): Boolean {
        emojiAltPhysicalKeyDetector.onKeyUp(keyEvent)
        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED)
            return false

        val keyIdentifier = keyEvent.deviceId.toLong() shl 32 + keyEvent.keyCode
        return inputLogic.mCurrentlyPressedHardwareKeys.remove(keyIdentifier)
    }

    override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent): Boolean {
        emojiAltPhysicalKeyDetector.onKeyDown(keyEvent)
        if (!ProductionFlags.IS_HARDWARE_KEYBOARD_SUPPORTED)
            return false

        val event: Event
        if (settings.current.mLocale.language == "ko") { // todo: this does not appear to be the right place
            val subtype = keyboardSwitcher.keyboard?.mId?.subtype ?: RichInputMethodManager.getInstance().currentSubtype
            event = HangulEventDecoder.decodeHardwareKeyEvent(subtype, keyEvent) {
                getHardwareKeyEventDecoder(keyEvent.deviceId).decodeHardwareKey(keyEvent)
            }
        } else {
            event = getHardwareKeyEventDecoder(keyEvent.deviceId).decodeHardwareKey(keyEvent)
        }

        if (event.isHandled) {
            inputLogic.onCodeInput(
                settings.current, event,
                keyboardSwitcher.getKeyboardCapsMode(), // TODO: this is not necessarily correct for a hardware keyboard right now
                keyboardSwitcher.getCurrentKeyboardScript(),
                latinIME.mHandler
            )
            return true
        }
        return false
    }

    override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
        // Any real keypress invalidates the cycling snapshot.
        resetSuggestionCycle()
        when (primaryCode) {
            KeyCode.TOGGLE_AUTOCORRECT -> return settings.toggleAutoCorrect()
            KeyCode.TOGGLE_INCOGNITO_MODE -> {
                settings.toggleAlwaysIncognitoMode()
                BackgroundGatheringCache.clear()
                latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
                return
            }
            KeyCode.BACKGROUND_GATHERING -> {
                if (BackgroundGatheringCache.isEmpty) {
                    // only enable, no toggle
                    GestureDataGatheringSettings.setBackgroundGatheringEnabled(latinIME.prefs(), true)
                    latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
                } else {
                    if (GestureDataGatheringSettings.isDiscardByDefault(latinIME))
                        BackgroundGatheringCache.save(latinIME)
                    else
                        BackgroundGatheringCache.clear()
                }
                return
            }
            KeyCode.BACKGROUND_GATHERING_TEMP_OFF -> {
                GestureDataGatheringSettings.tempDisableBackgroundGathering(latinIME.prefs())
                BackgroundGatheringCache.clear()
                latinIME.setGestureDataGatheringMode(latinIME.currentInputEditorInfo, false)
                return
            }
        }
        if (Settings.getValues().mIsLocked && KeyCode.isIsBlockedWhenLocked(primaryCode))
            return
        val mkv = keyboardSwitcher.mainKeyboardView

        // checking if the character is a combining accent
        val event = if (primaryCode in combiningRange) { // todo: should this be done later, maybe in inputLogic?
            Event.createSoftwareDeadEvent(primaryCode, 0, metaState, mkv.getKeyX(x), mkv.getKeyY(y), null)
        } else {
            // todo:
            //  setting meta shift should only be done for arrow and similar cursor movement keys
            //  should only be enabled once it works more reliably (currently depends on app for some reason)
//            if (mkv.keyboard?.mId?.isAlphabetShiftedManually == true)
//                Event.createSoftwareKeypressEvent(primaryCode, metaState or KeyEvent.META_SHIFT_ON, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
//            else Event.createSoftwareKeypressEvent(primaryCode, metaState, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
            Event.createSoftwareKeypressEvent(primaryCode, metaState, mkv.getKeyX(x), mkv.getKeyY(y), isKeyRepeat)
        }
        latinIME.onEvent(event)
        metaAfterCodeInput(primaryCode)
    }

    override fun onTextInput(text: String?) = latinIME.onTextInput(text)

    override fun onContent(content: InputContentInfoCompat) {
        val editorInfo = latinIME.currentInputEditorInfo
        val editorMimeTypes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        if (editorMimeTypes.any { content.description.hasMimeType(it) }) {
            connection.commitContent(content, editorInfo)
        } else if (editorMimeTypes.isEmpty()) { // make the fallback optional?
            latinIME.clipboardHistoryManager.pasteWithoutChangingClips(content)
        }
    }

    override fun onStartBatchInput() = latinIME.onStartBatchInput()

    override fun onUpdateBatchInput(batchPointers: InputPointers?) = latinIME.onUpdateBatchInput(batchPointers)

    override fun onEndBatchInput(batchPointers: InputPointers?) = latinIME.onEndBatchInput(batchPointers)

    override fun onCancelBatchInput() = latinIME.onCancelBatchInput()

    // User released a finger outside any key
    override fun onCancelInput() { }

    override fun onFinishSlidingInput() =
        keyboardSwitcher.onFinishSlidingInput(latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)

    override fun onCustomRequest(request: KeyboardActionListener.CustomAction) = when (request) {
        KeyboardActionListener.CustomAction.SHOW_INPUT_METHOD_PICKER -> latinIME.showInputPickerDialog()
        KeyboardActionListener.CustomAction.TOUCHPAD_ON -> {
            keyboardSwitcher.mainKeyboardView?.alpha = 0.5f
            true
        }
        KeyboardActionListener.CustomAction.TOUCHPAD_OFF -> {
            keyboardSwitcher.mainKeyboardView?.alpha = 1f
            true
        }
        KeyboardActionListener.CustomAction.PERFORM_HAPTIC -> {
            performHapticFeedback(HapticEvent.KEY_LONG_PRESS)
            true
        }
    }

    override fun onHorizontalSpaceSwipe(steps: Int): Boolean = when (Settings.getValues().mSpaceSwipeHorizontal) {
        KeyboardActionListener.SwipeAction.MOVE_CURSOR -> onMoveCursorHorizontally(steps)
        KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE -> onLanguageSlide(steps)
        KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD -> {
            toggleLayout(LayoutDirective.Utility.NUMPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.TOGGLE_DPAD -> {
            toggleLayout(LayoutDirective.Utility.DPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.INSERT_SPACE -> onInsertSpace()
        KeyboardActionListener.SwipeAction.DELETE_WORD -> onDeleteWord()
        KeyboardActionListener.SwipeAction.ACCEPT_SUGGESTION -> onAcceptSuggestion()
        KeyboardActionListener.SwipeAction.UNDO_AUTOCORRECT -> onUndoAutocorrect()
        KeyboardActionListener.SwipeAction.CYCLE_SUGGESTION_NEXT -> onCycleSuggestion(1)
        KeyboardActionListener.SwipeAction.CYCLE_SUGGESTION_PREV -> onCycleSuggestion(-1)
        else -> false
    }

    override fun onVerticalSpaceSwipe(steps: Int): Boolean = when (Settings.getValues().mSpaceSwipeVertical) {
        KeyboardActionListener.SwipeAction.MOVE_CURSOR -> onMoveCursorVertically(steps)
        KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE -> onLanguageSlide(steps)
        KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD -> {
            toggleLayout(LayoutDirective.Utility.NUMPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.TOGGLE_DPAD -> {
            toggleLayout(LayoutDirective.Utility.DPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            true
        }
        KeyboardActionListener.SwipeAction.HIDE_KEYBOARD -> {
            latinIME.requestHideSelf(0)
            true
        }
        KeyboardActionListener.SwipeAction.TOUCHPAD_MODE -> {
            // Activate touchpad mode - the actual cursor movement will be handled in PointerTracker

            // Activation and ensure enough room for navigation.
            val requiredSteps = 8

            if (abs(steps) >= requiredSteps) {
                TouchpadHandler.setTouchpadModeActive(true)
                true
            } else {
                false
            }
        }
        KeyboardActionListener.SwipeAction.INSERT_SPACE -> onInsertSpace()
        KeyboardActionListener.SwipeAction.DELETE_WORD -> onDeleteWord()
        KeyboardActionListener.SwipeAction.ACCEPT_SUGGESTION -> onAcceptSuggestion()
        KeyboardActionListener.SwipeAction.UNDO_AUTOCORRECT -> onUndoAutocorrect()
        KeyboardActionListener.SwipeAction.CYCLE_SUGGESTION_NEXT -> onCycleSuggestion(1)
        KeyboardActionListener.SwipeAction.CYCLE_SUGGESTION_PREV -> onCycleSuggestion(-1)
        else -> false
    }

    override fun onEndSpaceSwipe() {
        initialSubtype = null
        subtypeSwitchCount = 0
    }

    override fun onKeySwipeAction(action: KeyboardActionListener.SwipeAction) {
        // Any action other than cycling itself ends the cycling session. Gesture-inserted
        // spaces do not go through the listener's onCodeInput, so without this the stale
        // snapshot survived and the next cycle reverted/inserted against a dead list.
        if (action != KeyboardActionListener.SwipeAction.CYCLE_SUGGESTION_NEXT
            && action != KeyboardActionListener.SwipeAction.CYCLE_SUGGESTION_PREV) {
            resetSuggestionCycle()
        }
        when (action) {
            KeyboardActionListener.SwipeAction.INSERT_SPACE -> onInsertSpace()
            KeyboardActionListener.SwipeAction.DELETE_WORD -> onDeleteWord()
            KeyboardActionListener.SwipeAction.ACCEPT_SUGGESTION -> onAcceptSuggestion()
            KeyboardActionListener.SwipeAction.UNDO_AUTOCORRECT -> onUndoAutocorrect()
            KeyboardActionListener.SwipeAction.CYCLE_SUGGESTION_NEXT -> onCycleSuggestion(1)
            KeyboardActionListener.SwipeAction.CYCLE_SUGGESTION_PREV -> onCycleSuggestion(-1)
            KeyboardActionListener.SwipeAction.HIDE_KEYBOARD -> latinIME.requestHideSelf(0)
            KeyboardActionListener.SwipeAction.SWITCH_LANGUAGE -> onLanguageSlide(1)
            KeyboardActionListener.SwipeAction.TOGGLE_NUMPAD ->
                toggleLayout(LayoutDirective.Utility.NUMPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            KeyboardActionListener.SwipeAction.TOGGLE_DPAD ->
                toggleLayout(LayoutDirective.Utility.DPAD, latinIME.currentAutoCapsState, latinIME.currentRecapitalizeState)
            else -> Unit
        }
    }

    // --- Suggestion cycling (Fleksy up/down) ----------------------------
    // Fleksy had no "undo": if the wrong word came out you flicked down until
    // the right one appeared. Cycling walks the suggestion list captured when
    // cycling started, so the list does not shift under the user as each pick
    // regenerates suggestions.

    private var cycleList: List<SuggestedWords.SuggestedWordInfo> = emptyList()
    private var cycleIndex = 0

    /** Set while waiting for the suggestions of a word just reopened by a swipe up. */
    private var awaitingReopenCorrection = false

    /**
     * Called when fresh suggestions arrive, so a reopened word can be corrected as soon as
     * there is something to correct it with.
     */
    fun onSuggestionsUpdated(words: SuggestedWords) {
        if (!awaitingReopenCorrection) return
        awaitingReopenCorrection = false
        if (words.isEmpty || words.isPunctuationSuggestions) return
        val composing = inputLogic.composingWordOrNull() ?: return
        val list = stripOrder(words)
        if (list.isEmpty()) return
        cycleList = list
        // A reopened word has no pending autocorrection, so the centre slot holds the word
        // as written -- the wrong one, since that is why the user swiped back. Take the best
        // candidate that actually differs from it: that is the correction being asked for.
        val best = (0 until words.size())
            .mapNotNull { words.getInfo(it) }
            .firstOrNull { it.mWord != null && it.mWord.isNotEmpty() && it.mWord != composing && !it.isEmoji }
        if (best == null) {
            // Genuinely nothing to fix; leave the word open for editing.
            cycleIndex = (DEFAULT_SUGGESTIONS_IN_STRIP / 2).coerceAtMost(list.size - 1)
            return
        }
        if (!inputLogic.setComposingWordForCycling(best)) return
        // Keep cycling anchored where the applied word sits on the strip.
        cycleIndex = list.indexOfFirst { it.mWord == best.mWord }
            .takeIf { it >= 0 } ?: (DEFAULT_SUGGESTIONS_IN_STRIP / 2).coerceAtMost(list.size - 1)
    }

    internal fun resetSuggestionCycle() {
        awaitingReopenCorrection = false
        cycleList = emptyList()
        cycleIndex = 0
    }

    /**
     * The three candidates the strip actually draws, in left-to-right order.
     *
     * Deliberately limited to what is on screen. The engine does return more (up to
     * MAX_SUGGESTIONS), but past the third candidate they are mostly noise -- typing "tud"
     * offers Tudjman, Rus, Tudesco after Tudo -- so cycling into them is worse than not
     * cycling at all. Three reliable candidates beat fourteen unreliable ones.
     *
     * Position is resolved with the strip's own layout helper rather than assumed, because
     * the index order flips depending on whether an autocorrection is pending: the centre
     * slot is the autocorrection when there is one, and the typed word when there is not.
     */
    private fun stripOrder(words: SuggestedWords): List<SuggestedWords.SuggestedWordInfo> {
        val center = DEFAULT_SUGGESTIONS_IN_STRIP / 2
        val typedWordPos = center - 1
        val omitTypedWord = SuggestionStripLayoutHelper.shouldOmitTypedWord(
            words.mInputStyle, Settings.getValues().mGestureFloatingPreviewTextEnabled, true)
        return (0 until words.size())
            .mapNotNull { index ->
                val info = words.getInfo(index) ?: return@mapNotNull null
                if (info.mWord.isNullOrEmpty()) return@mapNotNull null
                val pos = SuggestionStripLayoutHelper.getPositionInSuggestionStrip(
                    index, words.mWillAutoCorrect, omitTypedWord, center, typedWordPos)
                if (pos in 0 until DEFAULT_SUGGESTIONS_IN_STRIP) pos to info else null
            }
            .sortedBy { it.first }
            .map { it.second }
            .distinctBy { it.mWord }
    }

    /**
     * Moves to the neighbouring candidate on the strip: down goes right, up goes left,
     * starting from the centre slot. Nothing is committed -- the composing word is swapped
     * in place, so the next separator commits whatever is showing.
     */
    private fun onCycleSuggestion(delta: Int): Boolean {
        if (cycleList.isEmpty()) {
            // Nothing composing: if the user just committed a word with a space, swiping up
            // reopens it for editing instead of doing nothing. Same gesture, decided by
            // context -- cycle while writing, reopen right after committing.
            if (delta < 0 && inputLogic.composingWordOrNull() == null) {
                if (!inputLogic.reopenLastWordForCycling(settings.current, keyboardSwitcher.currentKeyboardScript))
                    return false
                // The word is reopened exactly as it was written -- which is the wrong word,
                // since that is why the user swiped back. Applying the best candidate is the
                // point of the gesture: it means "fix this", not "hand it back to me".
                // Suggestions for the reopened word are computed asynchronously, so this is
                // finished in onSuggestionsUpdated once they arrive.
                awaitingReopenCorrection = true
                return true
            }
            val words = inputLogic.mSuggestedWords
            if (words.isEmpty || words.isPunctuationSuggestions) return false
            val list = stripOrder(words)
            if (list.size < 2) return false
            cycleList = list
            // Start at the centre slot: that is the word the keyboard would commit on space,
            // and the one the user sees as "current".
            cycleIndex = (DEFAULT_SUGGESTIONS_IN_STRIP / 2).coerceAtMost(list.size - 1)
        }
        val list = cycleList
        val next = cycleIndex + delta
        // Stop at the ends instead of wrapping: wrapping past the edge makes it impossible
        // to tell where you are in a three-item list.
        if (next !in list.indices) return false
        if (!inputLogic.setComposingWordForCycling(list[next])) {
            resetSuggestionCycle()
            return false
        }
        cycleIndex = next
        return true
    }

    // --- Fleksy-style swipe actions -------------------------------------
    // All of these are one-shot (see PointerTracker.oneShotSwipe): they run
    // once per swipe regardless of how far the finger travels.

    private fun onInsertSpace(): Boolean {
        latinIME.onCodeInput(
            Constants.CODE_SPACE,
            Constants.NOT_A_COORDINATE,
            Constants.NOT_A_COORDINATE,
            false
        )
        return true
    }

    /** Deletes the word before the cursor, plus any whitespace trailing it. */
    private fun onDeleteWord(): Boolean {
        if (connection.hasSelection()) {
            // A selection is already an explicit range; just remove it.
            latinIME.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            return true
        }
        inputLogic.finishInput()
        val before = connection.getTextBeforeCursor(DELETE_WORD_LOOKBEHIND, 0) ?: return false
        if (before.isEmpty()) return false

        var end = before.length
        // Swallow whitespace directly before the cursor first, so a swipe after
        // "hello world " removes "world " rather than only the space.
        while (end > 0 && before[end - 1].isWhitespace()) end--
        if (end == 0) {
            // Only whitespace behind the cursor: remove it and stop.
            connection.deleteTextBeforeCursor(before.length)
            return true
        }
        var start = end
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        connection.deleteTextBeforeCursor(before.length - start)
        return true
    }

    /** Commits the suggestion the keyboard would have picked on space. */
    private fun onAcceptSuggestion(): Boolean {
        val suggestions = inputLogic.mSuggestedWords
        if (suggestions.isEmpty) return false
        val index = SuggestedWords.INDEX_OF_AUTO_CORRECTION
        if (index >= suggestions.size()) return false
        latinIME.pickSuggestionManually(suggestions.getInfo(index) ?: return false)
        return true
    }

    private fun onUndoAutocorrect(): Boolean =
        inputLogic.revertLastAutocorrect(Settings.getValues(), keyboardSwitcher.keyboardCapsMode)

    override fun toggleLayout(layout: LayoutDirective.Utility, autoCapsFlags: Int, recapitalizeMode: RecapitalizeMode?) {
        keyboardSwitcher.toggleLayout(layout, autoCapsFlags, recapitalizeMode)
    }

    override fun onLongPressAlphaSymbolForNumpad() {
        keyboardSwitcher.onLongPressAlphaSymbolForNumpad()
    }

    override fun onMoveDeletePointer(steps: Int) {
        inputLogic.finishInput()
        val end = connection.expectedSelectionEnd
        val actualSteps = actualSteps(steps)
        val start = connection.expectedSelectionStart + actualSteps
        if (start > end) return
        gestureMoveBackHaptics()
        connection.setSelection(start, end)
    }

    private fun actualSteps(steps: Int): Int {
        val text = if (steps > 0) connection.getSelectedText(0) ?: return steps
        else connection.getTextBeforeCursor(-steps * 4, 0) ?: return steps
        return moveStepsToCharCount(text, steps)
    }

    override fun onUpWithDeletePointerActive() {
        if (!connection.hasSelection()) return
        inputLogic.finishInput()
        onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun resetMetaState() {
        metaState = 0
    }

    private fun onLanguageSlide(steps: Int): Boolean {
        if (abs(steps) < settings.current.mLanguageSwipeDistance) return false
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        if (subtypes.size <= 1) { // only allow if we have more than one subtype
            return false
        }
        // decide next or previous dependent on up or down
        val current = RichInputMethodManager.getInstance().currentSubtype.rawSubtype
        var wantedIndex = subtypes.indexOf(current) + if (steps > 0) 1 else -1
        wantedIndex %= subtypes.size
        if (wantedIndex < 0) {
            wantedIndex += subtypes.size
        }
        val newSubtype = subtypes[wantedIndex]

        // do not switch if we would switch to the initial subtype after cycling all other subtypes
        if (initialSubtype == null) initialSubtype = current
        if (initialSubtype == newSubtype) {
            if ((subtypeSwitchCount > 0 && steps > 0) || (subtypeSwitchCount < 0 && steps < 0)) {
                return true
            }
        }
        if (steps > 0) subtypeSwitchCount++ else subtypeSwitchCount--

        keyboardSwitcher.switchToSubtype(newSubtype)
        return true
    }

    private fun onMoveCursorVertically(steps: Int): Boolean {
        if (steps == 0) return false
        val code = if (steps < 0) {
            gestureMoveBackHaptics()
            KeyCode.ARROW_UP
        } else {
            gestureMoveForwardHaptics()
            KeyCode.ARROW_DOWN
        }
        onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        return true
    }

    private fun onMoveCursorHorizontally(rawSteps: Int): Boolean {
        if (rawSteps == 0) return false
        // for RTL languages we want to invert pointer movement
        val rtl = RichInputMethodManager.getInstance().currentSubtype.isRtlSubtype
        val steps = if (rtl) -rawSteps else rawSteps
        val moveSteps: Int
        if (steps < 0) {
            val text = connection.getTextBeforeCursor(-steps * 4, 0) ?: return false
            moveSteps = moveStepsToCharCount(text, steps)
            if (moveSteps == 0) {
                // some apps don't return any text via input connection, and the cursor can't be moved
                // we fall back to virtually pressing the left/right key one or more times instead
                repeat(-steps) {
                    onCodeInput(if (rtl) KeyCode.ARROW_RIGHT else KeyCode.ARROW_LEFT, Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE, false)
                }
                if (text.isNotEmpty()) {
                    gestureMoveBackHaptics()
                }
                return true
            }
            gestureMoveBackHaptics()
        } else {
            val text = connection.getTextAfterCursor(steps * 4, 0) ?: return false
            moveSteps = moveStepsToCharCount(text, steps)
            if (moveSteps == 0) {
                // some apps don't return any text via input connection, and the cursor can't be moved
                // we fall back to virtually pressing the left/right key one or more times instead
                repeat(steps) {
                    onCodeInput(if (rtl) KeyCode.ARROW_LEFT else KeyCode.ARROW_RIGHT, Constants.NOT_A_COORDINATE,
                        Constants.NOT_A_COORDINATE, false)
                }
                if (text.isNotEmpty()) {
                    gestureMoveForwardHaptics(true)
                }
                return true
            }
            gestureMoveForwardHaptics(text.isNotEmpty())
        }
        inputLogic.setExpectCursorMove()

        // the shortcut below causes issues due to horrible handling of text fields by Firefox and forks
        // issues:
        //  * setSelection "will cause the editor to call onUpdateSelection", see: https://developer.android.com/reference/android/view/inputmethod/InputConnection#setSelection(int,%20int)
        //     but Firefox is simply not doing this within the same word... WTF?
        //     https://github.com/HeliBorg/HeliBoard/issues/1139#issuecomment-2588169384
        //  * inputType is NOT of variant InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT (variant appears to always be 0)
        //     -> this is "fixed" now using AppWorkarounds.adjustInputType
        val variation = InputType.TYPE_MASK_VARIATION and Settings.getValues().mInputAttributes.mInputType
        if (variation != InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT
                && inputLogic.moveCursorByAndReturnIfInsideComposingWord(moveSteps)) {
            // no need to finish input and restart suggestions if we're still in the word
            // this is a noticeable performance improvement when moving through long words
            val newPosition = connection.expectedSelectionStart + moveSteps
            connection.setSelection(newPosition, newPosition)
            return true
        }

        inputLogic.finishInput()
        val newPosition = connection.expectedSelectionStart + moveSteps
        connection.setSelection(newPosition, newPosition)
        inputLogic.restartSuggestionsOnWordTouchedByCursor(settings.current, keyboardSwitcher.currentKeyboardScript)
        return true
    }

    private fun gestureMoveBackHaptics() {
        if (connection.canDeleteCharacters()) {
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
        }
    }

    // hasTextAfterCursor is used because text before the cursor is cached, going through the InputConnection can be slow
    private fun gestureMoveForwardHaptics(hasTextAfterCursor: Boolean? = null) {
        if (hasTextAfterCursor ?: connection.hasTextAfterCursor()) {
            performHapticFeedback(HapticEvent.GESTURE_MOVE)
        }
    }

    private fun performHapticFeedback(hapticEvent: HapticEvent) {
        audioAndHapticFeedbackManager.performHapticFeedback(keyboardSwitcher.visibleKeyboardView, hapticEvent)
    }

    private fun getHardwareKeyEventDecoder(deviceId: Int): HardwareEventDecoder {
        hardwareEventDecoders.get(deviceId)?.let { return it }

        // TODO: create the decoder according to the specification
        val newDecoder = HardwareKeyboardEventDecoder(deviceId)
        hardwareEventDecoders.put(deviceId, newDecoder)
        return newDecoder
    }

    // -------------------------- meta state handling -----------------------------

    // current state
    // press enables meta
    // release keeps meta enabled, unless there was a onCodeInput for a different key in between
    // onCodeInput ends the meta if it was enabled
    // long press on meta key also ends meta so popups are handled properly
    // sliding from a meta key to some other words too, though this was not intended (and there are no sliding key input graphics)

    // todo: move meta state tracking to KeyboardState? seems more suitable, also for handling sliding input
    //  but the issue is that meta state is used in Event to determine whether it's a functional Event (does not add a character)
    //  (and also it's in the hardware keyEvents which are handled by onKeyUp/Down, but that should be manageable)

    /** actual Android metaState like in KeyEvent */
    private var metaState = 0

    /** keeps track of the state of meta keys by (HeliBoard) KeyCodes */
    private val metaPressStates = SparseArray<MetaPressState>(4)

    // todo: lock and non-lock versions interact badly: when any of them is released, the meta state is removed
    //  this is not wanted, especially because the state of the other key is not affected (still looks pressed)
    private fun metaOnPressKey(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState() ?: return
        if (primaryCode.isMetaLock()) {
            // if unset -> lock, otherwise set to UNSET_ON_RELEASE so it's unset on release
            if (metaPressStates[primaryCode] != MetaPressState.LOCKED) {
                metaPressStates[primaryCode] = MetaPressState.LOCKED
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
                metaState = metaState or metaCode
            } else {
                metaPressStates[primaryCode] = MetaPressState.UNSET_ON_RELEASE
            }
            return
        }
        if (metaPressStates[primaryCode] == MetaPressState.RELEASED_BUT_ACTIVE) {
            // meta key is pressed again without other input -> should be disabled on release
            metaPressStates[primaryCode] = MetaPressState.UNSET_ON_RELEASE
        } else {
            // otherwise just press it normally
            metaPressStates[primaryCode] = MetaPressState.PRESSED
        }
        metaState = metaState or metaCode
        // pressed graphics are set anyway, no need to lock it
    }

    // looks like this is not called if there are no popups
    private fun metaOnLongPressKey(primaryCode: Int) {
        if (metaPressStates[primaryCode] != MetaPressState.PRESSED) return
        // we long-pressed a meta key that has popups -> disable so the meta state is not used for the popup
        metaPressStates[primaryCode] = MetaPressState.UNSET
        keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
        val metaCode = primaryCode.toMetaState() ?: return
        metaState = metaState and metaCode.inv()
    }

    private fun metaOnReleaseKey(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState() ?: return
        val metaPressState = metaPressStates[primaryCode]
        if (metaPressState == MetaPressState.UNSET_ON_RELEASE) {
            metaPressStates[primaryCode] = MetaPressState.UNSET
            metaState = metaState and metaCode.inv()
            keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
        } else if (metaPressState == MetaPressState.PRESSED) {
            metaPressStates[primaryCode] = MetaPressState.RELEASED_BUT_ACTIVE
            keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
        }
    }

    private fun metaAfterCodeInput(primaryCode: Int) {
        val metaCode = primaryCode.toMetaState()
        if (metaCode != null) {
            // meta key might be a popup key, we just toggle between set and unset
            val metaPressState = metaPressStates[primaryCode] ?: MetaPressState.UNSET
            if (metaPressState == MetaPressState.UNSET) {
                metaPressStates[primaryCode] = MetaPressState.SET
                metaState = metaState or metaCode
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, true)
            } else if (metaPressState == MetaPressState.SET) {
                metaPressStates[primaryCode] = MetaPressState.UNSET
                metaState = metaState and metaCode.inv()
                keyboardSwitcher.mainKeyboardView?.updateLockState(primaryCode, false)
            }
        } else if (metaState != 0) {
            // non-meta key -> unset all set / released_but_active, and mark pressed as UNSET_ON_RELEASE
            metaPressStates.forEach { key, value ->
                if (value == MetaPressState.RELEASED_BUT_ACTIVE || value == MetaPressState.SET) {
                    metaPressStates[key] = MetaPressState.UNSET
                    keyboardSwitcher.mainKeyboardView?.updateLockState(key, false)
                    val metaCode = key.toMetaState() ?: return@forEach
                    metaState = metaState and metaCode.inv()
                } else if (value == MetaPressState.PRESSED) {
                    metaPressStates[key] = MetaPressState.UNSET_ON_RELEASE
                }
            }
        }
    }

    companion object {
        /** How far back to look when deleting a word; longer words are rare. */
        private const val DELETE_WORD_LOOKBEHIND = 64

        private const val DEFAULT_SUGGESTIONS_IN_STRIP = 3

        private enum class MetaPressState {
            UNSET, // default state, not active
            SET, // enabled without onPressKey (e.g. in popup)
            PRESSED, // key is pressed
            UNSET_ON_RELEASE, // key is pressed, but state will be unset on release
            RELEASED_BUT_ACTIVE, // key was released without UNSET_ON_RELEASE state, meta state is still set
            LOCKED, // key is locked and will be released only by pressing the same key again
        }

        private fun Int.toMetaState() = when (this) {
            KeyCode.CTRL, KeyCode.CTRL_LOCK -> KeyEvent.META_CTRL_ON
            KeyCode.CTRL_LEFT               -> KeyEvent.META_CTRL_LEFT_ON
            KeyCode.CTRL_RIGHT              -> KeyEvent.META_CTRL_RIGHT_ON
            KeyCode.ALT, KeyCode.ALT_LOCK   -> KeyEvent.META_ALT_ON
            KeyCode.ALT_LEFT                -> KeyEvent.META_ALT_LEFT_ON
            KeyCode.ALT_RIGHT               -> KeyEvent.META_ALT_RIGHT_ON
            KeyCode.FN, KeyCode.FN_LOCK     -> KeyEvent.META_FUNCTION_ON
            KeyCode.META, KeyCode.META_LOCK -> KeyEvent.META_META_ON
            KeyCode.META_LEFT               -> KeyEvent.META_META_LEFT_ON
            KeyCode.META_RIGHT              -> KeyEvent.META_META_RIGHT_ON
            else -> null
        }

        private fun Int.isMetaLock() = this == KeyCode.CTRL_LOCK || this == KeyCode.ALT_LOCK || this == KeyCode.FN_LOCK || this == KeyCode.META_LOCK
    }
}

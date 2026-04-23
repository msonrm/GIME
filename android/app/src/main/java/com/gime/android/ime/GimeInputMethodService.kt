package com.gime.android.ime

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gime.android.engine.JapaneseConverter
import com.gime.android.engine.PinyinEngine
import com.gime.android.input.GamepadInputManager
import com.gime.android.input.GamepadSnapshot
import com.gime.android.learn.DatabaseProvider
import com.gime.android.osc.OscSender
import com.gime.android.osc.VrChatOscOutput
import com.gime.android.osc.VrChatOscSettings
import com.kazumaproject.markdownhelperkeyboard.repository.LearnRepository
import com.kazumaproject.markdownhelperkeyboard.repository.UserDictionaryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * GIME を Android システム IME として動作させるサービス。
 *
 * Phase A6-6 Step 5 (rev. b):
 * Gemini アドバイス + ComponentActivity の setContent 実装を参考に、
 * LifecycleOwner 等を Service 側で実装し、owner は IME window の decorView に
 * 設定する方式。IME では AbstractInputMethodService.onBind が final なので
 * ServiceLifecycleDispatcher は使えず、生の LifecycleRegistry を
 * handleLifecycleEvent で駆動する。
 */
class GimeInputMethodService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "GimeIme"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // IME では AbstractInputMethodService.onBind が final のため
    // ServiceLifecycleDispatcher は使えない。生の LifecycleRegistry を
    // handleLifecycleEvent で駆動する。
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreInstance = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInstance

    val inputManager = GamepadInputManager()
    private var currentSnapshot = GamepadSnapshot()
    private var inputView: GimeInputView? = null

    private var imeComposing = false
    private var imeComposingText = ""

    // VRChat OSC 連携（Phase A7-3/4）
    private var vrChatSettings: VrChatOscSettings? = null
    private var vrChatOutput: VrChatOscOutput? = null

    /// VRChat モードで LS 送信するまでの累積テキスト（確定済み文節 + 句読点）。
    /// 現在 composing 中の `imeComposingText` はまだ含まない。
    /// chatbox 下書きとしてユーザーに見せているのは `vrChatAccumulated + imeComposingText`。
    private var vrChatAccumulated: String = ""

    /// ビジュアライザのカウンター表示用。Compose 観測可能な状態として保持。
    /// `vrChatAccumulated + imeComposingText` の文字数を手で同期する。
    val draftLengthState = mutableIntStateOf(0)

    private fun updateDraftLength() {
        draftLengthState.intValue = vrChatAccumulated.length + imeComposingText.length
    }

    /// chatbox の下書きを今の状態から再送する。VRChat モード ON 時のみ呼ぶ。
    private fun sendVrChatDraft() {
        updateDraftLength()
        val out = vrChatOutput ?: return
        val draft = vrChatAccumulated + imeComposingText
        out.sendComposingText(draft)
    }

    /// 設定を元に VrChatOscOutput を起動/更新/停止する。
    private fun refreshVrChatOutput() {
        val s = vrChatSettings ?: return
        if (s.enabled) {
            val customMsgs = s.resolvedCustomTypingMessages()
            val existing = vrChatOutput
            if (existing != null) {
                existing.updateTarget(s.host, s.port)
                existing.commitOnly = s.commitOnlyMode
                existing.sendTypingIndicator = s.typingIndicatorEnabled
                existing.typingStartMessage = customMsgs?.first
                existing.typingEndMessage = customMsgs?.second
            } else {
                try {
                    val sender = OscSender(s.host, s.port)
                    vrChatOutput = VrChatOscOutput(sender, serviceScope).apply {
                        commitOnly = s.commitOnlyMode
                        sendTypingIndicator = s.typingIndicatorEnabled
                        typingStartMessage = customMsgs?.first
                        typingEndMessage = customMsgs?.second
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "OscSender init failed", t)
                }
            }
        } else {
            vrChatOutput?.close()
            vrChatOutput = null
        }
    }

    override fun onCreate() {
        // SavedStateRegistry は super.onCreate の前に attach / restore する
        // （ComponentActivity の実装順と同じ）。状態は INITIALIZED のままでよい。
        try {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
        } catch (t: Throwable) {
            Log.e(TAG, "savedStateRegistry init failed", t)
        }
        super.onCreate()
        // Lifecycle を CREATE → START → RESUME と段階的に進める
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (t: Throwable) {
            Log.e(TAG, "lifecycle init failed", t)
        }
        Log.d(TAG, "onCreate")

        val db = DatabaseProvider.get(this)
        val userDict = UserDictionaryRepository(db.userWordDao())
        val learnRepo = LearnRepository(db.learnDao())

        val pinyinEngine = PinyinEngine()
        pinyinEngine.load(this)
        inputManager.pinyinEngine = pinyinEngine

        val japaneseConverter = JapaneseConverter().apply {
            this.userDict = userDict
            this.learnRepo = learnRepo
        }
        japaneseConverter.initializeAsync(this, serviceScope)
        inputManager.japaneseConverter = japaneseConverter
        inputManager.coroutineScope = serviceScope

        // 永続化された言語モード設定を反映（Activity 側で変更があれば IME にも反映される）
        val modeSettings = com.gime.android.settings.GimeModeSettings(this)
        inputManager.updateEnabledModes(modeSettings.enabledModes)

        // VRChat OSC 設定をロード（enabled 時のみソケットを open する）
        vrChatSettings = VrChatOscSettings(this)
        refreshVrChatOutput()

        wireCallbacks()
        observeComposingState()
    }

    private fun observeComposingState() {
        serviceScope.launch {
            snapshotFlow {
                inputManager.hiraganaBuffer.isNotEmpty() || inputManager.isConverting
            }
                .distinctUntilChanged()
                .collect { nowComposing ->
                    if (!nowComposing && imeComposing) {
                        currentInputConnection?.finishComposingText()
                        imeComposing = false
                        imeComposingText = ""
                        updateDraftLength()
                    }
                }
        }
    }

    override fun onDestroy() {
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (_: Throwable) {}
        Log.d(TAG, "onDestroy")
        try { vrChatOutput?.close() } catch (_: Throwable) {}
        vrChatOutput = null
        serviceScope.cancel()
        try {
            viewModelStoreInstance.clear()
        } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "onStartInput restarting=$restarting inputType=${attribute?.inputType}")
        // 設定変更を拾うため毎セッション refresh
        refreshVrChatOutput()
        checkConnectedGamepads()
    }

    override fun onFinishInput() {
        Log.d(TAG, "onFinishInput")
        imeComposing = false
        imeComposingText = ""
        updateDraftLength()
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        val v = GimeInputView(this, this)
        inputView = v
        // Gemini アドバイス + ComponentActivity 実装を参考に、
        // ComposeView が view tree 探索で見つけられるよう owner を decorView に設定する。
        // ComposeView 自体への設定より確実で、Compose がデフォルトで参照するパス。
        try {
            val decor = window?.window?.decorView
            if (decor != null) {
                decor.setViewTreeLifecycleOwner(this)
                decor.setViewTreeViewModelStoreOwner(this)
                decor.setViewTreeSavedStateRegistryOwner(this)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "decorView owner setup failed", t)
        }
        return v
    }

    /// Compose 側のレイアウト変化を受けてインセットを再計算するためのフック。
    /// InputMethodService 自身が公開している insets 再計算 API は無いので、
    /// view に requestLayout をかけてフレームワーク側の layout → onComputeInsets
    /// の流れに載せる。onGloballyPositioned は layout 中に呼ばれるため、
    /// requestLayout は次フレームに仮予約される（安全）。
    fun onLayoutUpdated() {
        // no-op: onComputeInsets は view の layout 完了後にフレームワークが呼ぶ。
        // 現状値は inputView のフィールドから直接読み取るので、明示的な
        // invalidation は不要。将来ちらつきが出たら requestLayout を追加する。
    }

    /// IME の「見かけの占有領域」を compact バーから下だけに絞り、
    /// 候補オーバーレイ（compact バーの上）はアプリのレイアウトに影響させない。
    /// これで compact モードでは常に compact バー分（約 36dp）しかアプリの
    /// 入力欄を押し上げず、候補が出ても引っ込んでも縦位置は変わらない。
    override fun onComputeInsets(outInsets: InputMethodService.Insets?) {
        if (outInsets == null) {
            super.onComputeInsets(outInsets)
            return
        }
        val view = inputView
        if (view == null || view.width <= 0 || view.height <= 0) {
            super.onComputeInsets(outInsets)
            return
        }

        val barTop = view.compactBarTopInViewPx.coerceIn(0, view.height)

        outInsets.contentTopInsets = barTop
        outInsets.visibleTopInsets = barTop
        // TOUCHABLE_INSETS_CONTENT: contentTopInsets より下の領域だけが touchable。
        // オーバーレイ領域（candidates）は透過で、タッチはアプリ側へ抜ける。
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_CONTENT
    }

    // MARK: - ハードウェアキー受信

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && GamepadSnapshot.isGamepad(event.device)) {
            ensureConnected(event.device)
            currentSnapshot = GamepadSnapshot.updateFromKeyEvent(event, pressed = true, currentSnapshot)
            inputManager.updateSnapshot(currentSnapshot)
            inputView?.updateStatus()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && GamepadSnapshot.isGamepad(event.device)) {
            currentSnapshot = GamepadSnapshot.updateFromKeyEvent(event, pressed = false, currentSnapshot)
            inputManager.updateSnapshot(currentSnapshot)
            inputView?.updateStatus()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && handleMotionEvent(event)) {
            inputView?.updateStatus()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!GamepadSnapshot.isGamepad(event.device)) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        ensureConnected(event.device)
        currentSnapshot = GamepadSnapshot.fromMotionEvent(event, currentSnapshot)
        inputManager.updateSnapshot(currentSnapshot)
        return true
    }

    // MARK: - InputConnection 出力配線

    private fun wireCallbacks() {
        inputManager.onDirectInsert = { text, replaceCount ->
            val nowComposing = inputManager.hiraganaBuffer.isNotEmpty() ||
                inputManager.isConverting

            // --- InputConnection 側（常に書く：GIME 自身の編集欄も含めて反映） ---
            val ic = currentInputConnection
            if (ic != null) {
                ic.beginBatchEdit()
                if (nowComposing) {
                    val newComposing = if (replaceCount >= imeComposingText.length) {
                        text
                    } else {
                        imeComposingText.dropLast(replaceCount) + text
                    }
                    ic.setComposingText(newComposing, 1)
                    imeComposingText = newComposing
                    imeComposing = true
                } else {
                    if (imeComposing) {
                        ic.finishComposingText()
                        imeComposing = false
                        imeComposingText = ""
                    }
                    if (replaceCount > 0) {
                        ic.deleteSurroundingText(replaceCount, 0)
                    }
                    if (text.isNotEmpty()) {
                        ic.commitText(text, 1)
                    }
                }
                ic.endBatchEdit()
            } else {
                // InputConnection が無い場合でも VRChat 用の状態は更新する
                if (nowComposing) {
                    imeComposingText = if (replaceCount >= imeComposingText.length) text
                                       else imeComposingText.dropLast(replaceCount) + text
                    imeComposing = true
                } else {
                    if (imeComposing) {
                        imeComposing = false
                        imeComposingText = ""
                    }
                }
            }

            // --- VRChat OSC 側（dual output） ---
            if (vrChatOutput != null) {
                if (!nowComposing && text.isNotEmpty()) {
                    // 非 composing の直接 commit（句読点等）→ 累積テキストに追加
                    vrChatAccumulated += text
                }
                sendVrChatDraft()
            }
        }

        inputManager.onFinalizeComposing = {
            val ic = currentInputConnection
            // VRChat: composing を累積に追加してから draft 送信
            val wasComposing = imeComposing
            val committedSegment = imeComposingText
            if (ic != null && wasComposing) {
                ic.finishComposingText()
            }
            imeComposing = false
            imeComposingText = ""

            if (vrChatOutput != null && wasComposing) {
                vrChatAccumulated += committedSegment
                sendVrChatDraft()
            }
        }

        inputManager.onDeleteBackward = {
            val ic = currentInputConnection
            val wasComposing = imeComposing

            if (ic != null) {
                if (wasComposing) {
                    val newBuffer = inputManager.hiraganaBuffer
                    if (newBuffer.isEmpty() && !inputManager.isConverting) {
                        ic.setComposingText("", 1)
                        ic.finishComposingText()
                        imeComposing = false
                        imeComposingText = ""
                    } else {
                        ic.setComposingText(newBuffer, 1)
                        imeComposingText = newBuffer
                    }
                } else {
                    val selected = ic.getSelectedText(0)
                    if (selected != null && selected.isNotEmpty()) {
                        ic.commitText("", 1)
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                }
            } else {
                // InputConnection が無くても VRChat 側の状態は更新する
                if (wasComposing) {
                    val newBuffer = inputManager.hiraganaBuffer
                    if (newBuffer.isEmpty() && !inputManager.isConverting) {
                        imeComposing = false
                        imeComposingText = ""
                    } else {
                        imeComposingText = newBuffer
                    }
                }
            }

            if (vrChatOutput != null) {
                if (!wasComposing && vrChatAccumulated.isNotEmpty()) {
                    // 非 composing 時の削除 → 累積の末尾 1 文字を削る
                    vrChatAccumulated = vrChatAccumulated.dropLast(1)
                }
                sendVrChatDraft()
            }
        }

        inputManager.onCursorMove = { offset ->
            val ic = currentInputConnection
            if (ic != null) {
                val key = if (offset > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                val steps = kotlin.math.abs(offset)
                repeat(steps) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                }
            }
        }

        inputManager.onCursorMoveVertical = { direction ->
            val ic = currentInputConnection
            if (ic != null) {
                val key = if (direction > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
            }
        }

        inputManager.onConfirmOrNewline = {
            val out = vrChatOutput
            if (out != null && vrChatAccumulated.isNotEmpty() && imeComposingText.isEmpty()) {
                // VRChat モード: 累積を chatbox に即時確定送信
                // （Enter 入力の代わりに「メッセージ送信」セマンティクス）
                out.commit(vrChatAccumulated)
                vrChatAccumulated = ""
                updateDraftLength()
            } else {
                val ic = currentInputConnection
                val info = currentInputEditorInfo
                val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
                val noEnterAction = (info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) ?: 0) != 0
                val hasNoAction = noEnterAction ||
                    action == EditorInfo.IME_ACTION_NONE ||
                    action == EditorInfo.IME_ACTION_UNSPECIFIED
                if (ic != null) {
                    if (hasNoAction) {
                        ic.commitText("\n", 1)
                    } else {
                        ic.performEditorAction(action)
                    }
                }
            }
        }

        inputManager.onGetLastCharacter = {
            currentInputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()
        }
    }

    // MARK: - ヘルパー

    private fun ensureConnected(device: InputDevice?) {
        if (!inputManager.isConnected && device != null) {
            inputManager.onGamepadConnected(device.name ?: "Gamepad")
        }
    }

    private fun checkConnectedGamepads() {
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            if (GamepadSnapshot.isGamepad(device)) {
                if (!inputManager.isConnected) {
                    inputManager.onGamepadConnected(device.name ?: "Gamepad")
                }
                return
            }
        }
    }
}

package com.gime.android.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
import com.gime.android.MainActivity
import com.gime.android.R
import com.gime.android.engine.JapaneseConverter
import com.gime.android.engine.PinyinEngine
import com.gime.android.input.GamepadInputManager
import com.gime.android.input.GamepadSnapshot
import com.gime.android.learn.DatabaseProvider
import com.gime.android.osc.OscSender
import com.gime.android.osc.VrChatOscOutput
import com.gime.android.osc.VrChatOscSettings
import com.gime.android.settings.GimeModeSettings
import com.kazumaproject.markdownhelperkeyboard.repository.LearnRepository
import com.kazumaproject.markdownhelperkeyboard.repository.UserDictionaryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * GIME のビジュアライザをフローティングオーバーレイとして表示するサービス。
 *
 * VRChat OSC 連携時に「Android 側では IME を使わず、バブルでビジュアライザだけを
 * 表示しつつゲームパッド入力→ OSC 送信」という用途を想定している。
 *
 * - `TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCH_MODAL` で他アプリの操作は妨げない
 * - フォーカス可能なウィンドウなのでゲームパッドの KeyEvent / MotionEvent を受け取れる
 * - 出力先は VRChat OSC のみ（IME のような InputConnection は持たない）
 */
class BubbleService :
    Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "BubbleService"
        private const val NOTIF_CHANNEL_ID = "gime_bubble"
        private const val NOTIF_ID = 7301
        const val ACTION_START = "com.gime.android.bubble.START"
        const val ACTION_STOP = "com.gime.android.bubble.STOP"

        /// オーバーレイ権限の有無。
        fun hasOverlayPermission(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val intent = Intent(context, BubbleService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BubbleService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreInstance = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreInstance

    val inputManager = GamepadInputManager()
    private var currentSnapshot = GamepadSnapshot()
    private var bubbleView: BubbleView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var vrChatSettings: VrChatOscSettings? = null
    private var vrChatOutput: VrChatOscOutput? = null

    /// chatbox の累積テキスト（LS 確定送信までの下書き）。
    private var vrChatAccumulated: String = ""
    /// 現在 composing 中のテキスト（InputConnection は無いのでここで追跡）。
    private var composingText: String = ""

    /// バブル UI が表示する「これから送る下書き」テキスト。
    /// 韓国語 / 英語など直接入力モードだと画面上に入力内容が出ないので、
    /// バブルでプレビューを見せるために Compose 観測可能な state にしておく。
    val draftPreview = mutableStateOf("")

    private fun refreshDraftPreview() {
        draftPreview.value = vrChatAccumulated + composingText
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        try {
            savedStateRegistryController.performAttach()
            savedStateRegistryController.performRestore(null)
        } catch (t: Throwable) {
            Log.e(TAG, "savedStateRegistry init failed", t)
        }
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
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

        val modeSettings = GimeModeSettings(this)
        inputManager.updateEnabledModes(modeSettings.enabledModes)

        vrChatSettings = VrChatOscSettings(this)
        refreshVrChatOutput()

        wireCallbacks()
        observeComposingState()
    }

    /// 日本語で「変換せずに LS（= ひらがなそのまま確定）」を押したとき、
    /// GamepadInputManager は resetComposingState() を呼ぶだけで
    /// onFinalizeComposing を発火しない。そのため hiraganaBuffer が
    /// non-empty → empty に遷移したのを検知して、バブル側の
    /// composingText を vrChatAccumulated に逃がす。
    /// （GimeInputMethodService の observeComposingState と同じ作法）
    private fun observeComposingState() {
        serviceScope.launch {
            snapshotFlow {
                inputManager.hiraganaBuffer.isNotEmpty() || inputManager.isConverting
            }
                .distinctUntilChanged()
                .collect { nowComposing ->
                    if (!nowComposing && composingText.isNotEmpty()) {
                        vrChatAccumulated += composingText
                        composingText = ""
                        refreshDraftPreview()
                        sendVrChatDraft()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 8+: startForegroundService で起動された場合、5 秒以内に
        // startForeground を呼ぶ必要がある。早期 return する分岐でも先に呼んでおく。
        startForegroundWithNotification()
        when (intent?.action) {
            ACTION_STOP -> {
                stopBubble()
                return START_NOT_STICKY
            }
            else -> startBubble()
        }
        return START_STICKY
    }

    private fun startBubble() {
        if (!hasOverlayPermission(this)) {
            Log.w(TAG, "overlay permission not granted; stopping")
            stopBubble()
            return
        }
        showOverlay()
        refreshVrChatOutput()
        checkConnectedGamepads()
    }

    fun stopBubble() {
        removeOverlay()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Throwable) {}
        stopSelf()
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(NOTIF_CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        NOTIF_CHANNEL_ID,
                        getString(R.string.bubble_notification_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.bubble_notification_title))
            .setContentText(getString(R.string.bubble_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun showOverlay() {
        if (bubbleView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 初期は focusable（ゲームパッドキー取得のため）+ 他アプリ操作を妨げない
            // NOT_TOUCH_MODAL + 外タップを ACTION_OUTSIDE として拾う WATCH_OUTSIDE_TOUCH。
            // BubbleView.setActive() で外タップ時に NOT_FOCUSABLE を動的に足す。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }
        layoutParams = params

        val view = BubbleView(this, this, params, wm)
        // ComposeView が ViewTree owner を辿れるよう view ルートに owner を設定。
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)

        try {
            wm.addView(view, params)
            bubbleView = view
            view.requestFocus()
        } catch (t: Throwable) {
            Log.e(TAG, "addView failed", t)
            stopSelf()
        }
    }

    private fun removeOverlay() {
        val v = bubbleView ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try { wm.removeView(v) } catch (_: Throwable) {}
        bubbleView = null
        layoutParams = null
    }

    override fun onDestroy() {
        try {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        } catch (_: Throwable) {}
        try { vrChatOutput?.close() } catch (_: Throwable) {}
        vrChatOutput = null
        serviceScope.cancel()
        try { viewModelStoreInstance.clear() } catch (_: Throwable) {}
        removeOverlay()
        super.onDestroy()
    }

    // MARK: - ゲームパッドイベント受信（BubbleView から委譲）

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!GamepadSnapshot.isGamepad(event.device)) return false
        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        ensureConnected(event.device)
        currentSnapshot = GamepadSnapshot.updateFromKeyEvent(event, pressed, currentSnapshot)
        inputManager.updateSnapshot(currentSnapshot)
        return true
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!GamepadSnapshot.isGamepad(event.device)) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        ensureConnected(event.device)
        currentSnapshot = GamepadSnapshot.fromMotionEvent(event, currentSnapshot)
        inputManager.updateSnapshot(currentSnapshot)
        return true
    }

    // MARK: - VRChat OSC 配線

    private fun refreshVrChatOutput() {
        val s = vrChatSettings ?: return
        if (s.enabled) {
            val existing = vrChatOutput
            if (existing != null) {
                existing.updateTarget(s.host, s.port)
                existing.commitOnly = s.commitOnlyMode
                existing.sendTypingIndicator = s.typingIndicatorEnabled
            } else {
                try {
                    val sender = OscSender(s.host, s.port)
                    vrChatOutput = VrChatOscOutput(sender, serviceScope).apply {
                        commitOnly = s.commitOnlyMode
                        sendTypingIndicator = s.typingIndicatorEnabled
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

    private fun sendVrChatDraft() {
        val out = vrChatOutput ?: return
        out.sendComposingText(vrChatAccumulated + composingText)
    }

    private fun wireCallbacks() {
        inputManager.onDirectInsert = { text, replaceCount ->
            val nowComposing = inputManager.hiraganaBuffer.isNotEmpty() ||
                inputManager.isConverting
            if (nowComposing) {
                composingText = if (replaceCount >= composingText.length) text
                                else composingText.dropLast(replaceCount) + text
            } else {
                // 韓国語の「即時適用+巻き戻し」や英語 T9 の rollback など、
                // 非 composing モードでも replaceCount > 0 で「直前 N 文字を差し替え」を
                // 要求してくる。これを accumulated に反映しないと前の字が残ってしまう。
                composingText = ""
                if (replaceCount > 0 && vrChatAccumulated.isNotEmpty()) {
                    val drop = replaceCount.coerceAtMost(vrChatAccumulated.length)
                    vrChatAccumulated = vrChatAccumulated.dropLast(drop)
                }
                if (text.isNotEmpty()) {
                    vrChatAccumulated += text
                }
            }
            refreshDraftPreview()
            sendVrChatDraft()
        }

        inputManager.onFinalizeComposing = {
            if (composingText.isNotEmpty()) {
                vrChatAccumulated += composingText
                composingText = ""
            }
            refreshDraftPreview()
            sendVrChatDraft()
        }

        inputManager.onDeleteBackward = {
            if (composingText.isNotEmpty()) {
                composingText = inputManager.hiraganaBuffer
                if (composingText.isEmpty() && !inputManager.isConverting) {
                    composingText = ""
                }
            } else if (vrChatAccumulated.isNotEmpty()) {
                vrChatAccumulated = vrChatAccumulated.dropLast(1)
            }
            refreshDraftPreview()
            sendVrChatDraft()
        }

        inputManager.onCursorMove = { /* バブル単体ではカーソル概念なし */ }
        inputManager.onCursorMoveVertical = { /* 同上 */ }
        inputManager.onGetLastCharacter = {
            (vrChatAccumulated + composingText).lastOrNull()
        }

        inputManager.onConfirmOrNewline = {
            val out = vrChatOutput
            if (out != null && vrChatAccumulated.isNotEmpty() && composingText.isEmpty()) {
                out.commit(vrChatAccumulated)
                vrChatAccumulated = ""
                refreshDraftPreview()
                // 送信後に自動でフォーカスを解放する設定なら、バブルを非アクティブ化
                // してゲームパッド入力を下の VRChat に戻す。
                if (vrChatSettings?.autoReleaseAfterSend == true) {
                    bubbleView?.setActive(false)
                }
            }
            // OSC 無効時は no-op（バブル単体には編集先が無い）
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

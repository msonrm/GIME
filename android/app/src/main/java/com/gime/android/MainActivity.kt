package com.gime.android

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.gime.android.engine.JapaneseConverter
import com.gime.android.input.GamepadInputManager
import com.gime.android.input.GamepadSnapshot
import com.gime.android.learn.DatabaseProvider
import com.gime.android.settings.GimeModeSettings
import com.gime.android.ui.GimeApp
import com.gime.android.ui.GimeTheme
import com.kazumaproject.markdownhelperkeyboard.repository.LearnRepository
import com.kazumaproject.markdownhelperkeyboard.repository.UserDictionaryRepository

/// GIME Android メインアクティビティ
/// ゲームパッドの KeyEvent / MotionEvent を横取りして GamepadInputManager に渡す
class MainActivity : ComponentActivity() {

    private val inputManager = GamepadInputManager()
    private var currentSnapshot = GamepadSnapshot()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // PinyinEngine のロードは初回モード切替時に遅延ロードでも良いが、ここで先にロードする
        val pinyinEngine = com.gime.android.engine.PinyinEngine()
        pinyinEngine.load(this)
        inputManager.pinyinEngine = pinyinEngine

        // ローカル DB（ユーザー辞書・学習）を用意し、変換エンジンに注入する
        val db = DatabaseProvider.get(this)
        val userDict = UserDictionaryRepository(db.userWordDao())
        val learnRepo = LearnRepository(db.learnDao())

        // 日本語かな漢字変換エンジンを非同期で初期化（バンドル辞書を assets から読み込む）
        val japaneseConverter = JapaneseConverter().apply {
            this.userDict = userDict
            this.learnRepo = learnRepo
        }
        japaneseConverter.initializeAsync(this, lifecycleScope)
        inputManager.japaneseConverter = japaneseConverter
        // 変換は非同期で行うためスコープを渡す
        inputManager.coroutineScope = lifecycleScope

        // 永続化された言語モード設定を起動時に反映
        val modeSettings = GimeModeSettings(this)
        inputManager.updateEnabledModes(modeSettings.enabledModes)

        setContent {
            GimeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GimeApp(
                        inputManager = inputManager,
                        pinyinEngine = pinyinEngine,
                        userDict = userDict,
                        learnRepo = learnRepo,
                    )
                }
            }
        }

        // 接続済みコントローラーをチェック
        checkConnectedGamepads()
    }

    override fun onResume() {
        super.onResume()
        checkConnectedGamepads()
    }

    // MARK: - ゲームパッド入力の横取り

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && GamepadSnapshot.isGamepad(event.device)) {
            ensureConnected(event.device)
            currentSnapshot = GamepadSnapshot.updateFromKeyEvent(event, pressed = true, currentSnapshot)
            inputManager.updateSnapshot(currentSnapshot)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && GamepadSnapshot.isGamepad(event.device)) {
            currentSnapshot = GamepadSnapshot.updateFromKeyEvent(event, pressed = false, currentSnapshot)
            inputManager.updateSnapshot(currentSnapshot)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event != null && GamepadSnapshot.isGamepad(event.device) &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            ensureConnected(event.device)
            currentSnapshot = GamepadSnapshot.fromMotionEvent(event, currentSnapshot)
            inputManager.updateSnapshot(currentSnapshot)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // MARK: - ヘルパー

    private fun ensureConnected(device: InputDevice?) {
        if (!inputManager.isConnected && device != null) {
            val name = device.name ?: "Gamepad"
            inputManager.onGamepadConnected(name)
        }
    }

    private fun checkConnectedGamepads() {
        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            if (GamepadSnapshot.isGamepad(device)) {
                inputManager.onGamepadConnected(device.name ?: "Gamepad")
                return
            }
        }
    }
}

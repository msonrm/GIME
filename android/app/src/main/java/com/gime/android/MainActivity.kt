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
import com.gime.android.engine.MozcUserDictionary
import com.gime.android.input.GamepadInputManager
import com.gime.android.input.GamepadSnapshot
import com.gime.android.settings.GimeModeSettings
import com.gime.android.ui.GimeApp
import com.gime.android.ui.GimeTheme

/// GIME Android メインアクティビティ
/// ゲームパッドの KeyEvent / MotionEvent を横取りして GamepadInputManager に渡す
class MainActivity : ComponentActivity() {

    private val inputManager = GamepadInputManager()
    private var currentSnapshot = GamepadSnapshot()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        // 日本語かな漢字変換エンジン（Mozc）を非同期で初期化。
        // 立ち上がったら、旧エンジン時代に Room へ登録されたユーザー辞書を一度だけ移す。
        val japaneseConverter = JapaneseConverter()
        japaneseConverter.initializeAsync(this, lifecycleScope) {
            MozcUserDictionary.migrateFromLegacyRoom(applicationContext)
        }
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
                        converter = japaneseConverter,
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

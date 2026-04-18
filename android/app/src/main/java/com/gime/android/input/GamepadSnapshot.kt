package com.gime.android.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/// ゲームパッドのボタン・スティック状態のスナップショット
/// Android の KeyEvent/MotionEvent から構築する
data class GamepadSnapshot(
    // D-pad は KeyEvent 由来と HAT 軸(MotionEvent)由来を別フィールドで追跡し、
    // 参照時に OR で結合する。これにより、どちらの報告方式のゲームパッドでも
    // 正しく press/release を検出できる
    var dpadUpKey: Boolean = false,
    var dpadDownKey: Boolean = false,
    var dpadLeftKey: Boolean = false,
    var dpadRightKey: Boolean = false,
    var dpadUpHat: Boolean = false,
    var dpadDownHat: Boolean = false,
    var dpadLeftHat: Boolean = false,
    var dpadRightHat: Boolean = false,
    var buttonA: Boolean = false,
    var buttonB: Boolean = false,
    var buttonX: Boolean = false,
    var buttonY: Boolean = false,
    var lb: Boolean = false,
    var rb: Boolean = false,
    // LT/RT も KeyEvent 由来と MotionEvent(軸)由来を別フィールドで追跡。
    // 両者が非同期に上書きし合うのを防ぎ、参照時に max で結合する
    var ltValueKey: Float = 0f,
    var rtValueKey: Float = 0f,
    var ltValueAxis: Float = 0f,
    var rtValueAxis: Float = 0f,
    var start: Boolean = false,
    var back: Boolean = false,
    var lsClick: Boolean = false,
    var rsClick: Boolean = false,
    var leftStickX: Float = 0f,
    var leftStickY: Float = 0f,
    var rightStickX: Float = 0f,
    var rightStickY: Float = 0f,
) {
    val dpadUp: Boolean get() = dpadUpKey || dpadUpHat
    val dpadDown: Boolean get() = dpadDownKey || dpadDownHat
    val dpadLeft: Boolean get() = dpadLeftKey || dpadLeftHat
    val dpadRight: Boolean get() = dpadRightKey || dpadRightHat
    val ltValue: Float get() = maxOf(ltValueKey, ltValueAxis)
    val rtValue: Float get() = maxOf(rtValueKey, rtValueAxis)

    companion object {
        /// MotionEvent からアナログ軸を更新する
        fun fromMotionEvent(event: MotionEvent, current: GamepadSnapshot): GamepadSnapshot {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            return current.copy(
                // D-pad（HAT 軸由来は毎回値で上書き。KeyEvent 由来は別フィールドで保持）
                dpadLeftHat = hatX < -0.5f,
                dpadRightHat = hatX > 0.5f,
                dpadUpHat = hatY < -0.5f,
                dpadDownHat = hatY > 0.5f,
                // 左スティック
                leftStickX = event.getAxisValue(MotionEvent.AXIS_X),
                leftStickY = event.getAxisValue(MotionEvent.AXIS_Y),
                // 右スティック
                rightStickX = event.getAxisValue(MotionEvent.AXIS_Z),
                rightStickY = event.getAxisValue(MotionEvent.AXIS_RZ),
                // トリガー（軸由来は別フィールド）
                ltValueAxis = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
                    .coerceAtLeast(event.getAxisValue(MotionEvent.AXIS_BRAKE)),
                rtValueAxis = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
                    .coerceAtLeast(event.getAxisValue(MotionEvent.AXIS_GAS)),
            )
        }

        /// KeyEvent でボタン状態を更新する
        fun updateFromKeyEvent(event: KeyEvent, pressed: Boolean, current: GamepadSnapshot): GamepadSnapshot {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> current.copy(buttonA = pressed)
                KeyEvent.KEYCODE_BUTTON_B -> current.copy(buttonB = pressed)
                KeyEvent.KEYCODE_BUTTON_X -> current.copy(buttonX = pressed)
                KeyEvent.KEYCODE_BUTTON_Y -> current.copy(buttonY = pressed)
                KeyEvent.KEYCODE_BUTTON_L1 -> current.copy(lb = pressed)
                KeyEvent.KEYCODE_BUTTON_R1 -> current.copy(rb = pressed)
                KeyEvent.KEYCODE_BUTTON_L2 -> current.copy(ltValueKey = if (pressed) 1f else 0f)
                KeyEvent.KEYCODE_BUTTON_R2 -> current.copy(rtValueKey = if (pressed) 1f else 0f)
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_MODE -> current.copy(start = pressed)
                KeyEvent.KEYCODE_BUTTON_SELECT -> current.copy(back = pressed)
                KeyEvent.KEYCODE_BUTTON_THUMBL -> current.copy(lsClick = pressed)
                KeyEvent.KEYCODE_BUTTON_THUMBR -> current.copy(rsClick = pressed)
                KeyEvent.KEYCODE_DPAD_UP -> current.copy(dpadUpKey = pressed)
                KeyEvent.KEYCODE_DPAD_DOWN -> current.copy(dpadDownKey = pressed)
                KeyEvent.KEYCODE_DPAD_LEFT -> current.copy(dpadLeftKey = pressed)
                KeyEvent.KEYCODE_DPAD_RIGHT -> current.copy(dpadRightKey = pressed)
                else -> current
            }
        }

        /// InputDevice がゲームパッドかどうか判定する
        fun isGamepad(device: InputDevice?): Boolean {
            if (device == null) return false
            val sources = device.sources
            return (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }
    }
}

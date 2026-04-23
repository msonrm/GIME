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
        /// MotionEvent からアナログ軸を更新する。
        /// D-pad の HAT 軸は対角入力（hatX, hatY が両方 >0.5 や 0.707, 0.707 など）
        /// を報告することがある。iOS 版 (PR #502, commit 58fad94) と同じく、
        /// 絶対値優位な軸だけを採用して「D-pad 同士は排他」を担保する。
        /// これにより LB+↓+Y で「？」狙いが → が混入して row 8 (ら行「る」)
        /// に化ける現象を防ぐ。
        fun fromMotionEvent(event: MotionEvent, current: GamepadSnapshot): GamepadSnapshot {
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val absX = kotlin.math.abs(hatX)
            val absY = kotlin.math.abs(hatY)
            // 同値（0.707, 0.707 等の対角）は Y 軸勝ち。iOS と同じ規約。
            val xDominant = absX > absY
            val yDominant = absY >= absX
            return current.copy(
                // D-pad（HAT 軸由来）: 排他的な単一方向のみを true にする。
                dpadLeftHat = xDominant && hatX < -0.5f,
                dpadRightHat = xDominant && hatX > 0.5f,
                dpadUpHat = yDominant && hatY < -0.5f,
                dpadDownHat = yDominant && hatY > 0.5f,
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

        /// KeyEvent でボタン状態を更新する。
        /// D-pad は物理的に排他（4 方向から 1 つだけ）なので、press edge で
        /// 他 3 方向を強制的にクリアする。BT 経由でイベント順序が崩れ、
        /// 例えば「↓ release より → press が先に届く」ようなケースで
        /// dpadDownKey と dpadRightKey が同時 true になり、
        /// resolveConsonantRow の優先順位で row=8 (→ ら行「る」) が
        /// row=9 (↓ わ行「？」) を乗っ取る事故を防ぐ。
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
                KeyEvent.KEYCODE_DPAD_UP ->
                    if (pressed) current.copy(dpadUpKey = true, dpadDownKey = false, dpadLeftKey = false, dpadRightKey = false)
                    else current.copy(dpadUpKey = false)
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (pressed) current.copy(dpadDownKey = true, dpadUpKey = false, dpadLeftKey = false, dpadRightKey = false)
                    else current.copy(dpadDownKey = false)
                KeyEvent.KEYCODE_DPAD_LEFT ->
                    if (pressed) current.copy(dpadLeftKey = true, dpadRightKey = false, dpadUpKey = false, dpadDownKey = false)
                    else current.copy(dpadLeftKey = false)
                KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (pressed) current.copy(dpadRightKey = true, dpadLeftKey = false, dpadUpKey = false, dpadDownKey = false)
                    else current.copy(dpadRightKey = false)
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

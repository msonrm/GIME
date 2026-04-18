package com.gime.android.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gime.android.engine.GamepadInputMode

/**
 * 言語モードの有効化および切替順序を SharedPreferences で永続化するストア。
 *
 * - Start ボタンでサイクルする言語モードを取捨選択
 * - サイクル順を並び替え
 * - 最低 1 モードは有効（全 OFF を防ぐガード）
 */
class GimeModeSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /// 有効なモードを切替順序どおりに並べたリスト。Compose observable。
    var enabledModes: List<GamepadInputMode> by mutableStateOf(loadFromPrefs())
        private set

    /// モードを有効化/無効化する。最後の 1 つは無効化できない。
    fun setEnabled(mode: GamepadInputMode, enabled: Boolean) {
        val current = enabledModes
        val updated = if (enabled) {
            if (current.contains(mode)) current
            else current + mode
        } else {
            if (current.size <= 1) current  // safety
            else current.filterNot { it == mode }
        }
        if (updated != current) persist(updated)
    }

    /// 並び順を 1 つ上に移動
    fun moveUp(index: Int) {
        if (index <= 0 || index >= enabledModes.size) return
        val updated = enabledModes.toMutableList().also {
            val tmp = it[index]
            it[index] = it[index - 1]
            it[index - 1] = tmp
        }
        persist(updated)
    }

    /// 並び順を 1 つ下に移動
    fun moveDown(index: Int) {
        if (index < 0 || index >= enabledModes.size - 1) return
        val updated = enabledModes.toMutableList().also {
            val tmp = it[index]
            it[index] = it[index + 1]
            it[index + 1] = tmp
        }
        persist(updated)
    }

    /// 永続化 + in-memory state 反映
    private fun persist(new: List<GamepadInputMode>) {
        enabledModes = new
        prefs.edit()
            .putString(KEY_ENABLED, new.joinToString(SEP) { it.name })
            .apply()
    }

    /// 保存済みリストを読み出す。未保存ならデフォルト（全モード）を返す。
    private fun loadFromPrefs(): List<GamepadInputMode> {
        val raw = prefs.getString(KEY_ENABLED, null)
        if (raw.isNullOrBlank()) return GamepadInputMode.entries.toList()
        val modes = raw.split(SEP).mapNotNull { name ->
            runCatching { GamepadInputMode.valueOf(name.trim()) }.getOrNull()
        }
        return if (modes.isEmpty()) GamepadInputMode.entries.toList() else modes
    }

    companion object {
        private const val PREFS_NAME = "gime_mode_settings"
        private const val KEY_ENABLED = "enabled_modes_ordered"
        private const val SEP = ","
    }
}

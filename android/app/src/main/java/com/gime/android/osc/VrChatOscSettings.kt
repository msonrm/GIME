package com.gime.android.osc

import android.content.Context
import android.content.SharedPreferences

/**
 * カスタム typing アクションの値の型。SharedPreferences に String rawValue で永続化する。
 */
enum class CustomOscValueType(val raw: String) {
    INT("int"),
    FLOAT("float"),
    BOOL("bool");

    companion object {
        fun fromRaw(raw: String?): CustomOscValueType? = values().firstOrNull { it.raw == raw }
    }
}

/**
 * VRChat OSC 連携の設定。SharedPreferences バックエンド。
 *
 * デフォルトは全て OFF。ユーザーが明示的に有効化するまで
 * ソケット open も送信もしない方針（プライバシー透明性のため）。
 */
class VrChatOscSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        set(v) { prefs.edit().putBoolean(KEY_ENABLED, v).apply() }

    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(v) { prefs.edit().putString(KEY_HOST, v).apply() }

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) { prefs.edit().putInt(KEY_PORT, v).apply() }

    /** デバッグ受信を有効化するか。 */
    var receiverEnabled: Boolean
        get() = prefs.getBoolean(KEY_RECEIVER_ENABLED, DEFAULT_RECEIVER_ENABLED)
        set(v) { prefs.edit().putBoolean(KEY_RECEIVER_ENABLED, v).apply() }

    /** デバッグ受信側のリッスンポート。VRChat からの応答は 9001 が慣例。 */
    var receiverPort: Int
        get() = prefs.getInt(KEY_RECEIVER_PORT, DEFAULT_RECEIVER_PORT)
        set(v) { prefs.edit().putInt(KEY_RECEIVER_PORT, v).apply() }

    /**
     * 確定時のみ OSC 送信するモード（下書き/typing indicator を送らない）。
     *
     * VRChat Mobile は `/chatbox/input ... sendMessage=false`（下書き）を受け取ると
     * chatbox の入力 UI を開いてしまい、Android システム IME と衝突する。
     * このモードを ON にすると、composing 中は一切送らず、LS 確定時のみ
     * `sendMessage=true` で送信する。
     */
    var commitOnlyMode: Boolean
        get() = prefs.getBoolean(KEY_COMMIT_ONLY, DEFAULT_COMMIT_ONLY)
        set(v) { prefs.edit().putBoolean(KEY_COMMIT_ONLY, v).apply() }

    /**
     * バブル表示で LS 送信後に自動でフォーカスを解放するか。
     *
     * ON: 送信と同時にバブルを非アクティブ化（半透明・NOT_FOCUSABLE）にして、
     *   ゲームパッド入力を下の VRChat に戻す。一言ずつ送る運用向け。
     * OFF: 送信後もバブルはフォーカスを保持。連続して話し続ける運用向け。
     */
    var autoReleaseAfterSend: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RELEASE, DEFAULT_AUTO_RELEASE)
        set(v) { prefs.edit().putBoolean(KEY_AUTO_RELEASE, v).apply() }

    /**
     * composing 中に /chatbox/typing を送るか。
     *
     * ON: 文字を打ち始めたタイミングで typing=true、確定/クリアで typing=false。
     *   アバター頭上に 3 点 typing インジケータが表示される。
     * OFF: typing 系パケットは一切送らない（commit 時の typing=false も送らない。
     *   相手側で typing 表示が出ないだけで機能的影響はなし）。
     *
     * VRChat Mobile では /chatbox/input は入力 UI を開くが、
     * /chatbox/typing は UI を開かない（はず）という運用上のオプション。
     */
    var typingIndicatorEnabled: Boolean
        get() = prefs.getBoolean(KEY_TYPING, DEFAULT_TYPING)
        set(v) { prefs.edit().putBoolean(KEY_TYPING, v).apply() }

    /**
     * typing の開始/終了エッジで任意の avatar parameter を叩くモード。
     *
     * 例: VRCEmote=7（悲しみ）を "考え中ポーズ" として使い、composing 開始で 7、
     * 終了で 0 に戻す。アバター側に対応するアニメーションが組まれている必要あり。
     */
    var customTypingEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_TYPING_ENABLED, DEFAULT_CUSTOM_TYPING_ENABLED)
        set(v) { prefs.edit().putBoolean(KEY_CUSTOM_TYPING_ENABLED, v).apply() }

    /** カスタム typing 時に叩く OSC アドレス（例: `/avatar/parameters/VRCEmote`）。 */
    var customTypingAddress: String
        get() = prefs.getString(KEY_CUSTOM_TYPING_ADDRESS, DEFAULT_CUSTOM_TYPING_ADDRESS)
            ?: DEFAULT_CUSTOM_TYPING_ADDRESS
        set(v) { prefs.edit().putString(KEY_CUSTOM_TYPING_ADDRESS, v).apply() }

    /** 値の型。`customTypingStartValue` / `customTypingEndValue` の解釈に使う。 */
    var customTypingValueType: CustomOscValueType
        get() = CustomOscValueType.fromRaw(prefs.getString(KEY_CUSTOM_TYPING_VALUE_TYPE, null))
            ?: DEFAULT_CUSTOM_TYPING_VALUE_TYPE
        set(v) { prefs.edit().putString(KEY_CUSTOM_TYPING_VALUE_TYPE, v.raw).apply() }

    /** typing 開始時に送る値（文字列。type に従って parse）。 */
    var customTypingStartValue: String
        get() = prefs.getString(KEY_CUSTOM_TYPING_START_VALUE, DEFAULT_CUSTOM_TYPING_START_VALUE)
            ?: DEFAULT_CUSTOM_TYPING_START_VALUE
        set(v) { prefs.edit().putString(KEY_CUSTOM_TYPING_START_VALUE, v).apply() }

    /** typing 終了時に送る値（文字列。type に従って parse）。 */
    var customTypingEndValue: String
        get() = prefs.getString(KEY_CUSTOM_TYPING_END_VALUE, DEFAULT_CUSTOM_TYPING_END_VALUE)
            ?: DEFAULT_CUSTOM_TYPING_END_VALUE
        set(v) { prefs.edit().putString(KEY_CUSTOM_TYPING_END_VALUE, v).apply() }

    /**
     * start / end 値を OSC 送信用の `Any` に解決した Pair を返す。parse 失敗時や
     * `customTypingEnabled == false`、アドレスが不正な場合は `null`。
     */
    fun resolvedCustomTypingMessages(): Pair<Pair<String, Any>, Pair<String, Any>>? {
        if (!customTypingEnabled) return null
        val trimmed = customTypingAddress.trim()
        if (!trimmed.startsWith("/")) return null
        val startArg = parseArgument(customTypingStartValue, customTypingValueType) ?: return null
        val endArg = parseArgument(customTypingEndValue, customTypingValueType) ?: return null
        return Pair(trimmed to startArg, trimmed to endArg)
    }

    private fun parseArgument(raw: String, type: CustomOscValueType): Any? {
        val t = raw.trim()
        return when (type) {
            CustomOscValueType.INT -> t.toIntOrNull()
            CustomOscValueType.FLOAT -> t.toFloatOrNull()
            CustomOscValueType.BOOL -> when (t.lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "vrchat_osc"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_RECEIVER_ENABLED = "receiverEnabled"
        private const val KEY_RECEIVER_PORT = "receiverPort"
        private const val KEY_COMMIT_ONLY = "commitOnlyMode"
        private const val KEY_AUTO_RELEASE = "autoReleaseAfterSend"
        private const val KEY_TYPING = "typingIndicatorEnabled"
        private const val KEY_CUSTOM_TYPING_ENABLED = "customTypingEnabled"
        private const val KEY_CUSTOM_TYPING_ADDRESS = "customTypingAddress"
        private const val KEY_CUSTOM_TYPING_VALUE_TYPE = "customTypingValueType"
        private const val KEY_CUSTOM_TYPING_START_VALUE = "customTypingStartValue"
        private const val KEY_CUSTOM_TYPING_END_VALUE = "customTypingEndValue"

        const val DEFAULT_ENABLED = false
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 9000
        const val DEFAULT_RECEIVER_ENABLED = false
        const val DEFAULT_RECEIVER_PORT = 9001
        const val DEFAULT_COMMIT_ONLY = false
        const val DEFAULT_AUTO_RELEASE = true
        const val DEFAULT_TYPING = true
        const val DEFAULT_CUSTOM_TYPING_ENABLED = false
        const val DEFAULT_CUSTOM_TYPING_ADDRESS = "/avatar/parameters/VRCEmote"
        val DEFAULT_CUSTOM_TYPING_VALUE_TYPE = CustomOscValueType.INT
        const val DEFAULT_CUSTOM_TYPING_START_VALUE = "7"
        const val DEFAULT_CUSTOM_TYPING_END_VALUE = "0"
    }
}

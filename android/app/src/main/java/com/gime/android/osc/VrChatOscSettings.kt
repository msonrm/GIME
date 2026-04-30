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
 * 二段送信翻訳のターゲット言語。`mlKitCode` は ML Kit Translate の言語コードに対応。
 * `OFF` 以外を選ぶと、commit 後に翻訳結果を notification=false で chatbox に
 * 上書き送信する（VRChat 側では音無しで日本語が翻訳に置き換わる）。
 *
 * 中文は ML Kit が「簡体のみ」しかモデルを持たないため、繁體（台湾）は
 * `toTraditionalTaiwan = true` で OpenCC4j の s2twp 後処理を通して語彙レベルで
 * 「電腦 / 軟體 / 網路」等の台湾風表現に寄せる。
 */
enum class TranslationTarget(
    val raw: String,
    val mlKitCode: String?,
    val display: String,
    val toTraditionalTaiwan: Boolean = false,
) {
    OFF("off", null, "OFF"),
    EN("en", "en", "English"),
    KO("ko", "ko", "한국어"),
    ZH_CN("zh", "zh", "中文(简体)"),
    ZH_TW("zh-TW", "zh", "中文(繁體・台湾)", toTraditionalTaiwan = true);

    companion object {
        fun fromRaw(raw: String?): TranslationTarget = values().firstOrNull { it.raw == raw } ?: OFF
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
     * 二段送信翻訳のターゲット言語。`OFF` で機能無効。
     *
     * `OFF` 以外: LS commit で日本語を /chatbox/input に確定送信したあと、
     * 裏で ML Kit Translate にかけて、結果を notification=false で
     * 同じ /chatbox/input に上書き送信する。VRChat 側では「日本語送信音 →
     * 静かに英訳が表示」の挙動になる。翻訳に失敗したら日本語のまま残る。
     */
    var translationTarget: TranslationTarget
        get() = TranslationTarget.fromRaw(prefs.getString(KEY_TRANSLATION_TARGET, null))
        set(v) { prefs.edit().putString(KEY_TRANSLATION_TARGET, v.raw).apply() }

    /**
     * 翻訳モデルのダウンロードを WiFi 接続時のみ許可するか。
     * ML Kit のモデルは ~30MB なので、デフォルト ON でモバイル回線消費を防ぐ。
     */
    var translationWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATION_WIFI_ONLY, DEFAULT_TRANSLATION_WIFI_ONLY)
        set(v) { prefs.edit().putBoolean(KEY_TRANSLATION_WIFI_ONLY, v).apply() }

    /**
     * 翻訳上書きまでの最低遅延 (ms)。`commit()` で日本語を送信した時刻から
     * この時間が経過するまで上書き送信を待つ。翻訳自体は並行で進めるので、
     * 翻訳が `delayMs` より時間がかかった場合はその時点で即上書き、
     * 短かった場合は残り時間を待ってから上書きする。
     *
     * 0 にすると即時上書き（最小レイテンシ）。1500ms くらいで「原文を読む間が
     * 取れる」体験になる。配信・デモ動画でキャプチャするときに視認性を確保する用途も。
     */
    var translationOverwriteDelayMs: Int
        get() = prefs.getInt(KEY_TRANSLATION_OVERWRITE_DELAY, DEFAULT_TRANSLATION_OVERWRITE_DELAY)
        set(v) { prefs.edit().putInt(KEY_TRANSLATION_OVERWRITE_DELAY, v).apply() }

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
        private const val KEY_TRANSLATION_TARGET = "translationTarget"
        private const val KEY_TRANSLATION_WIFI_ONLY = "translationWifiOnly"
        private const val KEY_TRANSLATION_OVERWRITE_DELAY = "translationOverwriteDelayMs"

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
        const val DEFAULT_TRANSLATION_WIFI_ONLY = true
        const val DEFAULT_TRANSLATION_OVERWRITE_DELAY = 1500

        /** UI の選択肢として並べる遅延候補 (ms)。 */
        val TRANSLATION_OVERWRITE_DELAY_OPTIONS = listOf(0, 1000, 1500, 2000, 3000)
    }
}

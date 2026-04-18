package com.gime.android.engine

/// ゲームパッドひらがな入力 — かな解決エンジン
/// iOS GIME の GamepadResolver.swift から移植

// MARK: - 入力モード

/// ゲームパッド入力モード（Start ボタンでサイクル）
enum class GamepadInputMode {
    JAPANESE,
    ENGLISH,
    KOREAN,
    CHINESE_SIMPLIFIED,
    CHINESE_TRADITIONAL;

    val label: String
        get() = when (this) {
            JAPANESE -> "日本語"
            ENGLISH -> "EN"
            KOREAN -> "한국어"
            CHINESE_SIMPLIFIED -> "简体"
            CHINESE_TRADITIONAL -> "繁體"
        }

    /// サイクル順（有効なモードのみ）
    fun next(enabledModes: List<GamepadInputMode>): GamepadInputMode {
        val currentIndex = enabledModes.indexOf(this)
        if (currentIndex < 0) return enabledModes.firstOrNull() ?: this
        return enabledModes[(currentIndex + 1) % enabledModes.size]
    }
}

// MARK: - 操作モード

/// ゲームパッド操作モード（Back ボタンでサイクル）
enum class GamepadOperationMode {
    NORMAL,
    TEXT_OPERATION,
}

// MARK: - かなテーブル

/// 10行 × 5段のかなテーブル [row][vowel]
val KANA_TABLE: Array<Array<String>> = arrayOf(
    // あ行
    arrayOf("あ", "い", "う", "え", "お"),
    // か行
    arrayOf("か", "き", "く", "け", "こ"),
    // さ行
    arrayOf("さ", "し", "す", "せ", "そ"),
    // た行
    arrayOf("た", "ち", "つ", "て", "と"),
    // な行
    arrayOf("な", "に", "ぬ", "ね", "の"),
    // は行
    arrayOf("は", "ひ", "ふ", "へ", "ほ"),
    // ま行
    arrayOf("ま", "み", "む", "め", "も"),
    // や行 (い=「, え=」)
    arrayOf("や", "「", "ゆ", "」", "よ"),
    // ら行
    arrayOf("ら", "り", "る", "れ", "ろ"),
    // わ行 (わ, ゐ, ？, ゑ, を)
    arrayOf("わ", "ゐ", "？", "ゑ", "を"),
)

/// 行名（ビジュアライザ用）
val ROW_NAMES = arrayOf("あ行", "か行", "さ行", "た行", "な行", "は行", "ま行", "や行", "ら行", "わ行")

/// 母音名（ビジュアライザ用）
val VOWEL_NAMES = arrayOf("あ", "い", "う", "え", "お")

// MARK: - LT 後置シフトマップ

/// 子音かな→拗音、母音→小書き。対象外→っ追加
val YOUON_POSTSHIFT_MAP: Map<Char, String> = mapOf(
    // 母音 → 小書き
    'あ' to "ぁ", 'い' to "ぃ", 'う' to "ぅ", 'え' to "ぇ", 'お' to "ぉ",
    // や行・わ行 → 小書き
    'や' to "ゃ", 'ゆ' to "ゅ", 'よ' to "ょ", 'わ' to "ゎ",
    // か行
    'か' to "きゃ", 'く' to "きゅ", 'こ' to "きょ",
    // さ行
    'さ' to "しゃ", 'す' to "しゅ", 'そ' to "しょ",
    // た行
    'た' to "ちゃ", 'つ' to "ちゅ", 'と' to "ちょ",
    // な行
    'な' to "にゃ", 'ぬ' to "にゅ", 'の' to "にょ",
    // は行
    'は' to "ひゃ", 'ふ' to "ひゅ", 'ほ' to "ひょ",
    // ま行
    'ま' to "みゃ", 'む' to "みゅ", 'も' to "みょ",
    // ら行
    'ら' to "りゃ", 'る' to "りゅ", 'ろ' to "りょ",
    // 濁音
    'が' to "ぎゃ", 'ぐ' to "ぎゅ", 'ご' to "ぎょ",
    'ざ' to "じゃ", 'ず' to "じゅ", 'ぞ' to "じょ",
    'だ' to "ぢゃ", 'づ' to "ぢゅ", 'ど' to "ぢょ",
    'ば' to "びゃ", 'ぶ' to "びゅ", 'ぼ' to "びょ",
    // 半濁音
    'ぱ' to "ぴゃ", 'ぷ' to "ぴゅ", 'ぽ' to "ぴょ",
)

// MARK: - 濁点・半濁点

/// 清音→濁音
val DAKUTEN_MAP: Map<Char, Char> = mapOf(
    'か' to 'が', 'き' to 'ぎ', 'く' to 'ぐ', 'け' to 'げ', 'こ' to 'ご',
    'さ' to 'ざ', 'し' to 'じ', 'す' to 'ず', 'せ' to 'ぜ', 'そ' to 'ぞ',
    'た' to 'だ', 'ち' to 'ぢ', 'つ' to 'づ', 'て' to 'で', 'と' to 'ど',
    'は' to 'ば', 'ひ' to 'び', 'ふ' to 'ぶ', 'へ' to 'べ', 'ほ' to 'ぼ',
    'う' to 'ゔ',
)

/// 清音→半濁音
val HANDAKUTEN_MAP: Map<Char, Char> = mapOf(
    'は' to 'ぱ', 'ひ' to 'ぴ', 'ふ' to 'ぷ', 'へ' to 'ぺ', 'ほ' to 'ぽ',
)

/// 濁音→清音（逆引き）
val DAKUTEN_REVERSE: Map<Char, Char> = DAKUTEN_MAP.entries.associate { (k, v) -> v to k }

/// 半濁音→清音（逆引き）
val HANDAKUTEN_REVERSE: Map<Char, Char> = HANDAKUTEN_MAP.entries.associate { (k, v) -> v to k }

// MARK: - ゲームパッドアクション

/// ゲームパッド入力から解決されたアクション
sealed class GamepadAction {
    /// かな文字入力（replaceCount > 0 で巻き戻し）
    data class Kana(val text: String, val replaceCount: Int = 0) : GamepadAction()
    /// LT 後置シフト（拗音/小書き/っ追加）
    data object Youon : GamepadAction()
    /// 濁点/半濁点トグル
    data object ToggleDakuten : GamepadAction()
    /// 1文字削除
    data object DeleteBack : GamepadAction()
    /// スペース挿入
    data object Space : GamepadAction()
    /// キャンセル（composing 破棄）
    data object Cancel : GamepadAction()
    /// 確定（composing 中）/ 改行（idle 時）
    data object ConfirmOrNewline : GamepadAction()
    /// 長音「ー」
    data object LongVowel : GamepadAction()
    /// 変換開始 / 次の候補群
    data object Convert : GamepadAction()
    /// 次の候補
    data object NextCandidate : GamepadAction()
    /// 前の候補
    data object PrevCandidate : GamepadAction()
    /// 文節区切りを拡張
    data object ExpandSegment : GamepadAction()
    /// 文節区切りを縮小
    data object ShrinkSegment : GamepadAction()
    /// カーソル左移動
    data object CursorLeft : GamepadAction()
    /// カーソル右移動
    data object CursorRight : GamepadAction()
}

// MARK: - 子音・母音解決

/// D-pad + LB のボタン状態
data class ConsonantState(
    val dpadUp: Boolean = false,
    val dpadDown: Boolean = false,
    val dpadLeft: Boolean = false,
    val dpadRight: Boolean = false,
    val lb: Boolean = false,
)

/// 子音行インデックスを解決（フリック配列準拠）
fun resolveConsonantRow(state: ConsonantState): Int {
    // LB + D-pad（は〜わ行）
    if (state.lb && state.dpadLeft) return 6   // ま行
    if (state.lb && state.dpadUp) return 7     // や行
    if (state.lb && state.dpadRight) return 8  // ら行
    if (state.lb && state.dpadDown) return 9   // わ行
    // LB 単独
    if (state.lb) return 5                     // は行
    // D-pad 単独（あ〜な行）
    if (state.dpadLeft) return 1               // か行
    if (state.dpadUp) return 2                 // さ行
    if (state.dpadRight) return 3              // た行
    if (state.dpadDown) return 4               // な行
    return 0                                    // あ行
}

/// 母音インデックス
enum class VowelButton(val index: Int) {
    A(0),  // RB
    I(1),  // 左 (X)
    U(2),  // 上 (Y)
    E(3),  // 右 (B)
    O(4),  // 下 (A)
}

// MARK: - D-pad ラベル

data class DpadLabels(
    val center: String,
    val left: String,
    val up: String,
    val right: String,
    val down: String,
)

val DPAD_LABELS_BASE = DpadLabels("あ行", "か行", "さ行", "た行", "な行")
val DPAD_LABELS_LB = DpadLabels("は行", "ま行", "や行", "ら行", "わ行")

// MARK: - 英語 T9 テーブル

/// 英語 T9 テーブル（10行×5列）
val ENGLISH_TABLE: Array<Array<String>> = arrayOf(
    arrayOf("1", "(", "?", ")", "!"),
    arrayOf("2", "a", "b", "c", ""),
    arrayOf("3", "d", "e", "f", ""),
    arrayOf("4", "g", "h", "i", ""),
    arrayOf("5", "j", "k", "l", ""),
    arrayOf("6", "m", "n", "o", ""),
    arrayOf("7", "p", "q", "r", "s"),
    arrayOf("8", "t", "u", "v", ""),
    arrayOf("9", "w", "x", "y", "z"),
    arrayOf("0", "@", "#", "-", "_"),
)

val ENGLISH_ROW_NAMES = arrayOf("(?)", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", "@#-")
val ENGLISH_DPAD_LABELS_BASE = DpadLabels("(?)", "abc", "def", "ghi", "jkl")
val ENGLISH_DPAD_LABELS_LB = DpadLabels("mno", "pqrs", "tuv", "wxyz", "@#-")

// MARK: - 中国語（繁體）注音テーブル

val ZHUYIN_TABLE: Array<Array<String>> = arrayOf(
    arrayOf("1", "ㄅ", "ㄆ", "ㄇ", "ㄈ"),
    arrayOf("2", "ㄉ", "ㄊ", "ㄋ", "ㄌ"),
    arrayOf("3", "ㄍ", "ㄎ", "ㄏ", ""),
    arrayOf("4", "ㄐ", "ㄑ", "ㄒ", ""),
    arrayOf("5", "ㄓ", "ㄔ", "ㄕ", "ㄖ"),
    arrayOf("6", "ㄗ", "ㄘ", "ㄙ", ""),
    arrayOf("7", "ㄚ", "ㄛ", "ㄜ", "ㄝ"),
    arrayOf("8", "ㄞ", "ㄟ", "ㄠ", "ㄡ"),
    arrayOf("9", "ㄢ", "ㄣ", "ㄤ", "ㄥ"),
    arrayOf("0", "ㄧ", "ㄨ", "ㄩ", ""),
)

val ZHUYIN_ROW_NAMES = arrayOf("ㄅㄆㄇㄈ", "ㄉㄊㄋㄌ", "ㄍㄎㄏ", "ㄐㄑㄒ", "ㄓㄔㄕㄖ", "ㄗㄘㄙ", "ㄚㄛㄜㄝ", "ㄞㄟㄠㄡ", "ㄢㄣㄤㄥ", "ㄧㄨㄩ")

/// 注音声母 → abbreviated pinyin 頭文字変換マップ
val ZHUYIN_TO_PINYIN_INITIAL: Map<Char, Char> = mapOf(
    'ㄅ' to 'b', 'ㄆ' to 'p', 'ㄇ' to 'm', 'ㄈ' to 'f',
    'ㄉ' to 'd', 'ㄊ' to 't', 'ㄋ' to 'n', 'ㄌ' to 'l',
    'ㄍ' to 'g', 'ㄎ' to 'k', 'ㄏ' to 'h',
    'ㄐ' to 'j', 'ㄑ' to 'q', 'ㄒ' to 'x',
    'ㄓ' to 'z', 'ㄔ' to 'c', 'ㄕ' to 's', 'ㄖ' to 'r',
    'ㄗ' to 'z', 'ㄘ' to 'c', 'ㄙ' to 's',
    'ㄚ' to 'a', 'ㄛ' to 'o', 'ㄜ' to 'e', 'ㄝ' to 'e',
    'ㄞ' to 'a', 'ㄟ' to 'e', 'ㄠ' to 'a', 'ㄡ' to 'o',
    'ㄢ' to 'a', 'ㄣ' to 'e', 'ㄤ' to 'a', 'ㄥ' to 'e',
    'ㄦ' to 'e',
    'ㄧ' to 'y', 'ㄨ' to 'w', 'ㄩ' to 'y',
)

// MARK: - 韓国語テーブル

/// 行(row) → 초성 index のマッピング
val KOREAN_ONSET_FOR_ROW: IntArray = intArrayOf(
    11,  // Row 0 neutral: ㅇ
    0,   // Row 1 ←: ㄱ
    2,   // Row 2 ↑: ㄴ
    3,   // Row 3 →: ㄷ
    5,   // Row 4 ↓: ㄹ
    6,   // Row 5 LB: ㅁ
    7,   // Row 6 LB+←: ㅂ
    9,   // Row 7 LB+↑: ㅅ
    12,  // Row 8 LB+→: ㅈ
    18,  // Row 9 LB+↓: ㅎ
)

/// 행(row) → 종성(coda) index のマッピング（받침用）
val KOREAN_CODA_FOR_ROW: IntArray = intArrayOf(
    21,  // Row 0: ㅇ
    1,   // Row 1: ㄱ
    4,   // Row 2: ㄴ
    7,   // Row 3: ㄷ
    8,   // Row 4: ㄹ
    16,  // Row 5: ㅁ
    17,  // Row 6: ㅂ
    19,  // Row 7: ㅅ
    22,  // Row 8: ㅈ
    27,  // Row 9: ㅎ
)

val KOREAN_ROW_NAMES = arrayOf("ㅇ", "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅈ", "ㅎ")

val KOREAN_DPAD_LABELS_BASE = DpadLabels("ㅇ", "ㄱ", "ㄴ", "ㄷ", "ㄹ")
val KOREAN_DPAD_LABELS_LB = DpadLabels("ㅁ", "ㅂ", "ㅅ", "ㅈ", "ㅎ")

/// フェイスボタン → 중성 index（基本層）
/// [RB, X(左=ㅓ), Y(上=ㅗ), B(右=ㅏ), A(下=ㅜ)]
val KOREAN_NUCLEUS_BASE: IntArray = intArrayOf(
    18,  // RB: ㅡ
    4,   // X: ㅓ
    8,   // Y: ㅗ
    0,   // B: ㅏ
    13,  // A: ㅜ
)

/// フェイスボタン → 중성 index（RT シフト層: y系母音 + ㅣ）
val KOREAN_NUCLEUS_SHIFTED: IntArray = intArrayOf(
    20,  // RB: ㅣ
    6,   // X: ㅕ
    12,  // Y: ㅛ
    2,   // B: ㅑ
    17,  // A: ㅠ
)

val KOREAN_VOWEL_CHARS_BASE = arrayOf("ㅡ", "ㅓ", "ㅗ", "ㅏ", "ㅜ")
val KOREAN_VOWEL_CHARS_SHIFTED = arrayOf("ㅣ", "ㅕ", "ㅛ", "ㅑ", "ㅠ")

/// 초성子音変換サイクル: 平音→激音→濃音→平音
val KOREAN_ONSET_CYCLE: Map<Int, Int> = mapOf(
    0 to 15, 15 to 1, 1 to 0,       // ㄱ→ㅋ→ㄲ→ㄱ
    3 to 16, 16 to 4, 4 to 3,       // ㄷ→ㅌ→ㄸ→ㄷ
    7 to 17, 17 to 8, 8 to 7,       // ㅂ→ㅍ→ㅃ→ㅂ
    9 to 10, 10 to 9,               // ㅅ→ㅆ→ㅅ
    12 to 14, 14 to 13, 13 to 12,   // ㅈ→ㅊ→ㅉ→ㅈ
)

/// RT（ㅣ付加）による複合母音変換
val KOREAN_NUCLEUS_ADD_I: Map<Int, Int> = mapOf(
    0 to 1, 4 to 5, 2 to 3, 6 to 7,
    8 to 11, 9 to 10, 13 to 16, 14 to 15, 18 to 19,
)

/// 右スティック→（ㅏ/ㅓ付加）による複合母音変換
val KOREAN_NUCLEUS_ADD_AEO: Map<Int, Int> = mapOf(
    8 to 9, 11 to 10, 13 to 14, 16 to 15,
)

/// 겹받침テーブル: [第1종성][第2종성] → 겹받침종성
val KOREAN_DOUBLE_CODA: Map<Int, Map<Int, Int>> = mapOf(
    1 to mapOf(19 to 3),
    4 to mapOf(22 to 5, 27 to 6),
    8 to mapOf(1 to 9, 16 to 10, 17 to 11, 19 to 12, 25 to 13, 26 to 14, 27 to 15),
    17 to mapOf(19 to 18),
)

/// 종성받침サイクル
val KOREAN_CODA_CYCLE: Map<Int, Int> = mapOf(
    1 to 24, 24 to 2, 2 to 1,
    7 to 25, 25 to 7,
    17 to 26, 26 to 17,
    19 to 20, 20 to 19,
    22 to 23, 23 to 22,
)

// MARK: - 자모 모드（子音・母音単体入力）用 互換 Jamo テーブル

/// 初声 (onset) index (0-18) → Unicode Compatibility Jamo コードポイント
/// Unicode の Compatibility Jamo ブロック（U+3131..U+314E）は겹자음を挟むため
/// 連続していない。table で明示的にマップする。
/// これは合成用の Hangul Jamo (U+1100..) ではなく、ユーザーが
/// 「ㅋㅋㅋ」のように単独で読める互換字母コードポイント。
val KOREAN_COMPAT_JAMO_ONSET: IntArray = intArrayOf(
    0x3131, // 0: ㄱ
    0x3132, // 1: ㄲ
    0x3134, // 2: ㄴ
    0x3137, // 3: ㄷ
    0x3138, // 4: ㄸ
    0x3139, // 5: ㄹ
    0x3141, // 6: ㅁ
    0x3142, // 7: ㅂ
    0x3143, // 8: ㅃ
    0x3145, // 9: ㅅ
    0x3146, // 10: ㅆ
    0x3147, // 11: ㅇ
    0x3148, // 12: ㅈ
    0x3149, // 13: ㅉ
    0x314A, // 14: ㅊ
    0x314B, // 15: ㅋ
    0x314C, // 16: ㅌ
    0x314D, // 17: ㅍ
    0x314E, // 18: ㅎ
)

/// 초성 index → 互換 Jamo 文字
fun koreanCompatJamoOnset(onsetIndex: Int): Char =
    KOREAN_COMPAT_JAMO_ONSET.getOrNull(onsetIndex)?.toChar() ?: '?'

/// 중성 (nucleus) index (0-20) → 互換 Jamo 文字
/// U+314F (ㅏ) から U+3163 (ㅣ) まで 21 文字が連続しているので単純加算。
fun koreanCompatJamoNucleus(nucleusIndex: Int): Char = (0x314F + nucleusIndex).toChar()

/// ゲームパッドひらがな入力 — かな解決エンジン
/// Web 版 gamepad-kana-table.ts の Swift 移植
///
/// フリック入力の方向規則を左右の手に分離:
/// - 左手（D-pad + LB）= 子音行
/// - 右手（フェイスボタン + RB）= 母音
/// - 同時押しで1文字入力

// MARK: - 入力モード

/// ゲームパッド入力モード（Start ボタンでサイクル）
enum GamepadInputMode: CaseIterable {
    case japanese
    case english
    case korean
    case chineseSimplified
    case chineseTraditional

    var label: String {
        switch self {
        case .japanese: return "日本語"
        case .english: return "EN"
        case .korean: return "한국어"
        case .chineseSimplified: return "简体"
        case .chineseTraditional: return "繁體"
        }
    }

    /// サイクル順（有効なモードのみ）
    func next(enabledModes: Set<GamepadInputMode> = Set(GamepadInputMode.allCases)) -> GamepadInputMode {
        let order: [GamepadInputMode] = [.japanese, .korean, .english, .chineseSimplified, .chineseTraditional]
        guard let currentIndex = order.firstIndex(of: self) else { return self }
        for offset in 1...order.count {
            let candidate = order[(currentIndex + offset) % order.count]
            if enabledModes.contains(candidate) {
                return candidate
            }
        }
        return self
    }
}

// MARK: - かなテーブル

/// 10行 × 5段のかなテーブル [row][vowel]
let kanaTable: [[String]] = [
    // あ行
    ["あ", "い", "う", "え", "お"],
    // か行
    ["か", "き", "く", "け", "こ"],
    // さ行
    ["さ", "し", "す", "せ", "そ"],
    // た行
    ["た", "ち", "つ", "て", "と"],
    // な行
    ["な", "に", "ぬ", "ね", "の"],
    // は行
    ["は", "ひ", "ふ", "へ", "ほ"],
    // ま行
    ["ま", "み", "む", "め", "も"],
    // や行 (い=「, え=」)
    ["や", "「", "ゆ", "」", "よ"],
    // ら行
    ["ら", "り", "る", "れ", "ろ"],
    // わ行 (わ, ゐ, ？, ゑ, を)
    ["わ", "ゐ", "？", "ゑ", "を"],
]

/// 行名（ビジュアライザ用）
let rowNames = ["あ行", "か行", "さ行", "た行", "な行", "は行", "ま行", "や行", "ら行", "わ行"]

/// 母音名（ビジュアライザ用）
let vowelNames = ["あ", "い", "う", "え", "お"]

// MARK: - LT 後置シフトマップ

/// 子音かな→拗音、母音→小書き。対象外→っ追加
let youonPostshiftMap: [Character: String] = [
    // 母音 → 小書き
    "あ": "ぁ", "い": "ぃ", "う": "ぅ", "え": "ぇ", "お": "ぉ",
    // か行
    "か": "きゃ", "く": "きゅ", "こ": "きょ",
    // さ行
    "さ": "しゃ", "す": "しゅ", "そ": "しょ",
    // た行
    "た": "ちゃ", "つ": "ちゅ", "と": "ちょ",
    // な行
    "な": "にゃ", "ぬ": "にゅ", "の": "にょ",
    // は行
    "は": "ひゃ", "ふ": "ひゅ", "ほ": "ひょ",
    // ま行
    "ま": "みゃ", "む": "みゅ", "も": "みょ",
    // ら行
    "ら": "りゃ", "る": "りゅ", "ろ": "りょ",
    // 濁音
    "が": "ぎゃ", "ぐ": "ぎゅ", "ご": "ぎょ",
    "ざ": "じゃ", "ず": "じゅ", "ぞ": "じょ",
    "だ": "ぢゃ", "づ": "ぢゅ", "ど": "ぢょ",
    "ば": "びゃ", "ぶ": "びゅ", "ぼ": "びょ",
    // 半濁音
    "ぱ": "ぴゃ", "ぷ": "ぴゅ", "ぽ": "ぴょ",
]

// MARK: - 濁点・半濁点

/// 清音→濁音
let dakutenMap: [Character: Character] = [
    "か": "が", "き": "ぎ", "く": "ぐ", "け": "げ", "こ": "ご",
    "さ": "ざ", "し": "じ", "す": "ず", "せ": "ぜ", "そ": "ぞ",
    "た": "だ", "ち": "ぢ", "つ": "づ", "て": "で", "と": "ど",
    "は": "ば", "ひ": "び", "ふ": "ぶ", "へ": "べ", "ほ": "ぼ",
    "う": "ゔ",
]

/// 清音→半濁音
let handakutenMap: [Character: Character] = [
    "は": "ぱ", "ひ": "ぴ", "ふ": "ぷ", "へ": "ぺ", "ほ": "ぽ",
]

/// 濁音→清音（逆引き）
let dakutenReverse: [Character: Character] = {
    var map: [Character: Character] = [:]
    for (k, v) in dakutenMap { map[v] = k }
    return map
}()

/// 半濁音→清音（逆引き）
let handakutenReverse: [Character: Character] = {
    var map: [Character: Character] = [:]
    for (k, v) in handakutenMap { map[v] = k }
    return map
}()

// MARK: - ゲームパッドアクション

/// ゲームパッド入力から解決されたアクション
enum GamepadAction {
    /// かな文字入力（replaceCount > 0 で巻き戻し）
    case kana(String, replaceCount: Int = 0)
    /// LT 後置シフト（拗音/小書き/っ追加）
    case youon
    /// 濁点/半濁点トグル
    case toggleDakuten
    /// 1文字削除
    case deleteBack
    /// スペース挿入
    case space
    /// キャンセル（composing 破棄）
    case cancel
    /// 確定（composing 中）/ 改行（idle 時）
    case confirmOrNewline
    /// 長音「ー」
    case longVowel
    /// 句読点（isSecond = true で「。」に差し替え）
    case punctuation(isSecond: Bool)
    /// 変換開始 / 次の候補群（左スティック↓）
    case convert
    /// 次の候補（左スティック→）
    case nextCandidate
    /// 前の候補（左スティック← / ↑）
    case prevCandidate
    /// 文節区切りを拡張（左スティック→）
    case expandSegment
    /// 文節区切りを縮小（左スティック←）
    case shrinkSegment
    /// カーソル左移動（idle 時、左スティック←）
    case cursorLeft
    /// カーソル右移動（idle 時、左スティック→）
    case cursorRight
}

// MARK: - 子音・母音解決

/// D-pad + LB のボタン状態
struct ConsonantState {
    var dpadUp = false
    var dpadDown = false
    var dpadLeft = false
    var dpadRight = false
    var lb = false
}

/// 子音行インデックスを解決（フリック配列準拠）
func resolveConsonantRow(_ state: ConsonantState) -> Int {
    // LB + D-pad（は〜わ行）
    if state.lb && state.dpadLeft { return 6 }   // ま行
    if state.lb && state.dpadUp { return 7 }     // や行
    if state.lb && state.dpadRight { return 8 }   // ら行
    if state.lb && state.dpadDown { return 9 }    // わ行
    // LB 単独
    if state.lb { return 5 }                      // は行
    // D-pad 単独（あ〜な行）
    if state.dpadLeft { return 1 }                // か行
    if state.dpadUp { return 2 }                  // さ行
    if state.dpadRight { return 3 }               // た行
    if state.dpadDown { return 4 }                // な行
    return 0                                       // あ行
}

/// 母音インデックス（nil = 未選択）
enum VowelButton: Int {
    case a = 0  // RB
    case i = 1  // 左 (X)
    case u = 2  // 上 (Y)
    case e = 3  // 右 (B)
    case o = 4  // 下 (A)
}

/// D-pad ラベル（レイヤー別）
struct DpadLabels {
    let center: String
    let left: String
    let up: String
    let right: String
    let down: String
}

let dpadLabelsBase = DpadLabels(center: "あ行", left: "か行", up: "さ行", right: "た行", down: "な行")
let dpadLabelsLB = DpadLabels(center: "は行", left: "ま行", up: "や行", right: "ら行", down: "わ行")

// MARK: - 英語 T9 テーブル

/// 英語 T9 テーブル（10行×5列）
/// 列順: [RB, X(左), Y(上), B(右), A(下)]
/// RB=数字, フェイスボタン=文字/記号
let englishTable: [[String]] = [
    // Row 0 neutral (T1=記号)
    ["1", "(", "?", ")", "!"],
    // Row 1 ← (T2=abc)
    ["2", "a", "b", "c", ""],
    // Row 2 ↑ (T3=def)
    ["3", "d", "e", "f", ""],
    // Row 3 → (T4=ghi)
    ["4", "g", "h", "i", ""],
    // Row 4 ↓ (T5=jkl)
    ["5", "j", "k", "l", ""],
    // Row 5 LB (T6=mno)
    ["6", "m", "n", "o", ""],
    // Row 6 LB+← (T7=pqrs)
    ["7", "p", "q", "r", "s"],
    // Row 7 LB+↑ (T8=tuv)
    ["8", "t", "u", "v", ""],
    // Row 8 LB+→ (T9=wxyz)
    ["9", "w", "x", "y", "z"],
    // Row 9 LB+↓ (T0=記号)
    ["0", "@", "#", "-", "_"],
]

/// 英語行名（ビジュアライザ用）
let englishRowNames = ["(?)", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", "@#-"]

/// 英語 D-pad ラベル
let englishDpadLabelsBase = DpadLabels(center: "(?)", left: "abc", up: "def", right: "ghi", down: "jkl")
let englishDpadLabelsLB = DpadLabels(center: "mno", left: "pqrs", up: "tuv", right: "wxyz", down: "@#-")

// MARK: - 中国語（简体）テーブル

/// 中国語簡体は英語テーブルを再利用（abbreviated pinyin = アルファベット入力）
let chineseDpadLabelsBase = englishDpadLabelsBase
let chineseDpadLabelsLB = englishDpadLabelsLB
let chineseRowNames = englishRowNames

// MARK: - 中国語（繁體）注音テーブル

/// 注音符号テーブル（10行 x 5列、英語テーブルと同じ構造）
/// 列順: [RB(数字), X(左), Y(上), B(右), A(下)]
/// D-pad + LB で行選択、フェイスボタンで注音選択、RB で数字入力
/// ㄦ は abbreviated zhuyin で ㄜ と同じ "e" にマップされるため省略
let zhuyinTable: [[String]] = [
    // Row 0: ニュートラル
    ["1", "ㄅ", "ㄆ", "ㄇ", "ㄈ"],      // 唇音
    // Row 1: D-pad ←
    ["2", "ㄉ", "ㄊ", "ㄋ", "ㄌ"],      // 舌尖音
    // Row 2: D-pad ↑
    ["3", "ㄍ", "ㄎ", "ㄏ", ""],         // 舌根音
    // Row 3: D-pad →
    ["4", "ㄐ", "ㄑ", "ㄒ", ""],         // 舌面音
    // Row 4: D-pad ↓
    ["5", "ㄓ", "ㄔ", "ㄕ", "ㄖ"],      // そり舌音
    // Row 5: LB
    ["6", "ㄗ", "ㄘ", "ㄙ", ""],         // 舌歯音
    // Row 6: LB + ←
    ["7", "ㄚ", "ㄛ", "ㄜ", "ㄝ"],      // 単母音
    // Row 7: LB + ↑
    ["8", "ㄞ", "ㄟ", "ㄠ", "ㄡ"],      // 複母音
    // Row 8: LB + →
    ["9", "ㄢ", "ㄣ", "ㄤ", "ㄥ"],      // 鼻母音
    // Row 9: LB + ↓
    ["0", "ㄧ", "ㄨ", "ㄩ", ""],         // 介母
]

/// 注音 D-pad ラベル（ラベル表示型フォールバック用、現在は十字配置を使用）
let zhuyinDpadLabelsBase = DpadLabels(center: "ㄅㄆㄇㄈ", left: "ㄉㄊㄋㄌ", up: "ㄍㄎㄏ", right: "ㄐㄑㄒ", down: "ㄓㄔㄕㄖ")
let zhuyinDpadLabelsLB = DpadLabels(center: "ㄗㄘㄙ", left: "ㄚㄛㄜㄝ", up: "ㄞㄟㄠㄡ", right: "ㄢㄣㄤㄥ", down: "ㄧㄨㄩ")
let zhuyinRowNames = ["ㄅㄆㄇㄈ", "ㄉㄊㄋㄌ", "ㄍㄎㄏ", "ㄐㄑㄒ", "ㄓㄔㄕㄖ", "ㄗㄘㄙ", "ㄚㄛㄜㄝ", "ㄞㄟㄠㄡ", "ㄢㄣㄤㄥ", "ㄧㄨㄩ"]

/// 注音声母 → abbreviated pinyin 頭文字変換マップ
let zhuyinToPinyinInitial: [Character: Character] = [
    "ㄅ": "b", "ㄆ": "p", "ㄇ": "m", "ㄈ": "f",
    "ㄉ": "d", "ㄊ": "t", "ㄋ": "n", "ㄌ": "l",
    "ㄍ": "g", "ㄎ": "k", "ㄏ": "h",
    "ㄐ": "j", "ㄑ": "q", "ㄒ": "x",
    "ㄓ": "z", "ㄔ": "c", "ㄕ": "s", "ㄖ": "r",
    "ㄗ": "z", "ㄘ": "c", "ㄙ": "s",
    // 韻母（零声母として使用）
    "ㄚ": "a", "ㄛ": "o", "ㄜ": "e", "ㄝ": "e",
    "ㄞ": "a", "ㄟ": "e", "ㄠ": "a", "ㄡ": "o",
    "ㄢ": "a", "ㄣ": "e", "ㄤ": "a", "ㄥ": "e",
    "ㄦ": "e",
    "ㄧ": "y", "ㄨ": "w", "ㄩ": "y",
]

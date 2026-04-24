/// Devanagari akshara 合成エンジン
///
/// Devanagari の音韻論的前提:
///   - 子音は固有の schwa (a) を背負う（क 単独で "ka"）
///   - conjunct は virama (्) で schwa を殺して作る（क + ् + ष = क्ष "kṣa"）
///   - 母音 matra が来ると schwa を置換して akshara 確定（क + ि = कि "ki"）
///   - anusvara (ं) / chandrabindu (ँ) / visarga (ः) は cluster 末尾修飾
///   - nukta (़) は子音に直後置（क → क़）
///
/// 重要な設計判断: **子音連続は auto-conjunct しない**。
/// 例えば `नम` (namaste の最初の 2 字) は न と म が別アクシャラで、
/// 両方 inherent schwa 付き。自動 conjunct すると `न्म` になり誤り。
/// ITRANS / Google Hindi IME 等と同じく、**conjunct は halant (RT) を
/// 明示的に打つ**方式を採用。
///   例: क्ष の入力 = 子音क → RT(halant) → 子音ष

enum DevanagariUnicode {
    static let virama: Character = "\u{094D}"       // ्
    static let nukta: Character = "\u{093C}"        // ़
    static let anusvara: Character = "\u{0902}"     // ं
    static let chandrabindu: Character = "\u{0901}" // ँ
    static let visarga: Character = "\u{0903}"      // ः
}

/// Composer の内部状態
private enum DevaState {
    case empty                // 空 or cluster 未開始
    case consonantOpen        // 末尾が子音（+nukta 可）で inherent schwa を背負っている
    case matraClosed          // matra が付いて akshara 確定済み
    case vowelClosed          // 独立母音で cluster 確定済み
    case halantClosed         // 明示 halant で子音単独（次も halant-conjunct 不可、新 cluster 開始）
    case modifierClosed       // anusvara / chandrabindu / visarga 付与済み
}

/// 短母音 → 長母音（独立形）
let devaVowelShortToLong: [Character: Character] = [
    "अ": "आ",
    "इ": "ई",
    "उ": "ऊ",
    "ऋ": "ॠ",
    "ए": "ऐ",
    "ओ": "औ",
]

/// 短 matra → 長 matra（ा は既に長扱いなので post-shift 対象外）
let devaMatraShortToLong: [Character: Character] = [
    "ि": "ी",
    "ु": "ू",
    "ृ": "ॄ",
    "े": "ै",
    "ो": "ौ",
]

/// Devanagari 合成エンジン
struct DevanagariComposer {
    /// 合成結果
    struct Output {
        let text: String
        let replaceCount: Int
    }

    private var buffer: String = ""
    private var state: DevaState = .empty

    /// 最後の consonant に nukta が付いているか（重ね押し防止）
    private var lastConsonantHasNukta: Bool = false

    /// 合成中か（IME 側で composing 判定に使う）
    var isComposing: Bool { !buffer.isEmpty }

    var currentBuffer: String { buffer }

    // MARK: - 入力 API

    /// 子音を入力する。
    /// 前の状態にかかわらず子音を単純に追加する（auto-conjunct しない）。
    /// 直前が halantClosed（明示 halant 直後）の場合、結果として virama + 子音 の
    /// Unicode シーケンスになり、レンダリング上は conjunct になる。
    mutating func inputConsonant(_ consonant: Character) -> Output {
        buffer.append(consonant)
        state = .consonantOpen
        lastConsonantHasNukta = false
        return Output(text: String(consonant), replaceCount: 0)
    }

    /// matra（従属母音記号）を入力する。子音に対してのみ適用可能。
    /// inherent schwa を置換し akshara を閉じる。
    /// 子音が無い状態で呼ばれた場合は nil（呼び側で独立母音にフォールバック）。
    mutating func inputMatra(_ matra: Character) -> Output? {
        guard state == .consonantOpen else { return nil }
        buffer.append(matra)
        state = .matraClosed
        return Output(text: String(matra), replaceCount: 0)
    }

    /// 独立母音を入力する。常に新 cluster を開始する。
    mutating func inputIndependentVowel(_ vowel: Character) -> Output {
        buffer.append(vowel)
        state = .vowelClosed
        return Output(text: String(vowel), replaceCount: 0)
    }

    /// 明示的 halant (virama) を入力する。子音直後にのみ意味を持つ。
    /// cluster は閉じられ、次の子音は halant-conjunct にならず新 cluster 開始扱い。
    mutating func inputHalant() -> Output? {
        guard state == .consonantOpen else { return nil }
        buffer.append(DevanagariUnicode.virama)
        state = .halantClosed
        return Output(text: String(DevanagariUnicode.virama), replaceCount: 0)
    }

    /// nukta を直前子音に付加する。既に nukta がある or 子音直後でない場合は no-op。
    mutating func inputNukta() -> Output? {
        guard state == .consonantOpen, !lastConsonantHasNukta else { return nil }
        buffer.append(DevanagariUnicode.nukta)
        lastConsonantHasNukta = true
        return Output(text: String(DevanagariUnicode.nukta), replaceCount: 0)
    }

    /// anusvara ↔ chandrabindu をトグル。既に付いていればトグル、なければ anusvara 追加。
    /// cluster 末尾修飾なので、子音直後 / matra 後 / 独立母音後 のいずれでも可。
    mutating func toggleAnusvara() -> Output? {
        guard let last = buffer.last else { return nil }
        if last == DevanagariUnicode.anusvara {
            buffer.removeLast()
            buffer.append(DevanagariUnicode.chandrabindu)
            state = .modifierClosed
            return Output(text: String(DevanagariUnicode.chandrabindu), replaceCount: 1)
        } else if last == DevanagariUnicode.chandrabindu {
            buffer.removeLast()
            // toggle off: chandrabindu 除去 → 前の状態（matra/vowel/consonant）に戻る
            state = recomputeState()
            return Output(text: "", replaceCount: 1)
        } else {
            // 直接付与できる状態か: consonantOpen, matraClosed, vowelClosed
            switch state {
            case .consonantOpen, .matraClosed, .vowelClosed:
                buffer.append(DevanagariUnicode.anusvara)
                state = .modifierClosed
                return Output(text: String(DevanagariUnicode.anusvara), replaceCount: 0)
            default:
                return nil
            }
        }
    }

    /// visarga を付与する。consonantOpen / matraClosed / vowelClosed 状態で可。
    mutating func inputVisarga() -> Output? {
        switch state {
        case .consonantOpen, .matraClosed, .vowelClosed:
            buffer.append(DevanagariUnicode.visarga)
            state = .modifierClosed
            return Output(text: String(DevanagariUnicode.visarga), replaceCount: 0)
        default:
            return nil
        }
    }

    /// 直前の母音/matra を長形に昇格する（RS→ post-shift）。
    /// - inherent schwa 状態（consonantOpen, 子音単独）: ा を追加して "Cā" に。
    /// - 短 matra: 対応する長 matra に置換。
    /// - 短独立母音: 対応する長独立母音に置換。
    /// - 既に長形 or 対象外: no-op で nil。
    mutating func applyLongShift() -> Output? {
        guard !buffer.isEmpty else { return nil }

        // inherent schwa 状態で long-shift → ा matra を追加
        // (nukta 付いていても append は可能)
        if state == .consonantOpen {
            let longAaMatra: Character = "ा"
            buffer.append(longAaMatra)
            state = .matraClosed
            return Output(text: String(longAaMatra), replaceCount: 0)
        }

        guard let last = buffer.last else { return nil }

        // 短 matra → 長 matra
        if let longMatra = devaMatraShortToLong[last] {
            buffer.removeLast()
            buffer.append(longMatra)
            return Output(text: String(longMatra), replaceCount: 1)
        }
        // 短独立母音 → 長独立母音
        if let longVowel = devaVowelShortToLong[last] {
            buffer.removeLast()
            buffer.append(longVowel)
            return Output(text: String(longVowel), replaceCount: 1)
        }
        return nil
    }

    /// 1 文字 backspace。state を再計算。
    mutating func backspace() -> Output? {
        guard !buffer.isEmpty else { return nil }
        buffer.removeLast()
        lastConsonantHasNukta = buffer.last == DevanagariUnicode.nukta
        state = recomputeState()
        return Output(text: "", replaceCount: 1)
    }

    /// cluster を確定し、内部状態をクリアする。
    /// buffer のテキストはそのまま（IME 側で committed として扱う）。
    mutating func commit() {
        buffer = ""
        state = .empty
        lastConsonantHasNukta = false
    }

    // MARK: - 内部ロジック

    /// buffer の末尾から state を再計算する。
    private func recomputeState() -> DevaState {
        guard let last = buffer.last else { return .empty }
        if last == DevanagariUnicode.virama { return .halantClosed }
        if last == DevanagariUnicode.anusvara ||
            last == DevanagariUnicode.chandrabindu ||
            last == DevanagariUnicode.visarga { return .modifierClosed }
        if last == DevanagariUnicode.nukta { return .consonantOpen }
        if isDevanagariMatra(last) { return .matraClosed }
        if isDevanagariIndependentVowel(last) { return .vowelClosed }
        if isDevanagariConsonant(last) { return .consonantOpen }
        return .empty
    }
}

// MARK: - Devanagari 文字分類（U+0900..U+097F ブロック内）

/// 子音: U+0915 (क) .. U+0939 (ह)
func isDevanagariConsonant(_ c: Character) -> Bool {
    guard let scalar = c.unicodeScalars.first else { return false }
    return (0x0915...0x0939).contains(scalar.value)
}

/// 従属母音記号 (matra): U+093E..U+094C
func isDevanagariMatra(_ c: Character) -> Bool {
    guard let scalar = c.unicodeScalars.first else { return false }
    return (0x093E...0x094C).contains(scalar.value)
}

/// 独立母音: U+0904..U+0914 + U+0960 (ॠ) + U+0961 (ॡ)
func isDevanagariIndependentVowel(_ c: Character) -> Bool {
    guard let scalar = c.unicodeScalars.first else { return false }
    let v = scalar.value
    return (0x0904...0x0914).contains(v) || v == 0x0960 || v == 0x0961
}

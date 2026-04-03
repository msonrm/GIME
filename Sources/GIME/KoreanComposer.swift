/// ハングル音節合成エンジン（2ボル式ベース）
///
/// Unicode Hangul Syllables ブロック（U+AC00–U+D7A3）の合成式:
///   syllable = 0xAC00 + (onset × 21 + nucleus) × 28 + coda
///
/// 設計方針:
///   - D-pad+LB で子音（초성）、フェイスボタンで母音（중성）を同時押しで音節入力
///   - 子音単独（母音なし）で받침（종성）を追加（2ボル式スタイル）
///   - LT = ㅇ子音キー（ㅇ받침を明示入力）
///   - RT + フェイスボタンで y系母音（RT = 母音修飾キー）
///   - RT 単押しリリースで複合母音（ㅣ付加: ㅏ→ㅐ）
///   - 右スティック↑ で子音変換サイクル（平音→激音→濃音）
///   - 右スティック→ で複合母音（ㅏ/ㅓ付加: ㅗ→ㅘ, ㅜ→ㅝ）
///   - 母音ボタン配置は字形の方向に対応（ㅏ=右B, ㅓ=左X, ㅗ=上Y, ㅜ=下A）

// MARK: - 状態管理

/// ハングル音節の合成状態
struct KoreanComposer {

    /// 現在合成中の音節（nil = 合成中でない）
    private var onset: Int?    // 초성 index (0-18)
    private var nucleus: Int?  // 중성 index (0-20)
    private var coda: Int?     // 종성 index (1-27, nil = なし)

    /// 合成結果
    struct Output {
        let text: String
        let replaceCount: Int
    }

    /// 合成中かどうか
    var isComposing: Bool { onset != nil }

    // MARK: - 入力

    /// 子音+母音で新しい音節を入力する
    mutating func inputSyllable(onset: Int, nucleus: Int) -> Output {
        commit()
        self.onset = onset
        self.nucleus = nucleus
        self.coda = nil
        let char = composeSyllable(onset: onset, nucleus: nucleus)
        return Output(text: String(char), replaceCount: 0)
    }

    /// 받침入力の結果
    enum PatchimResult {
        /// 받침を追加（or 겹받침に合成、or 上書き）。replaceCount=1 で現音節を差し替え
        case added(Output)
    }

    /// 받침（종성）を追加する（2ボル式スタイル）
    ///
    /// 既に받침がある場合は겹받침テーブルを参照し、
    /// 有効な組合せなら合成、無効なら無視する。
    mutating func inputPatchim(codaIndex: Int, codaRow: Int) -> PatchimResult? {
        guard let onset, let nucleus else { return nil }

        if let existingCoda = coda {
            // 既に받침がある → 겹받침を試みる
            if let doubleCoda = koreanDoubleCoda[existingCoda]?[codaIndex] {
                self.coda = doubleCoda
                let char = composeSyllable(onset: onset, nucleus: nucleus, coda: doubleCoda)
                return .added(Output(text: String(char), replaceCount: 1))
            } else {
                // 겹받침不成立: 無視（既存の받침を維持）
                return nil
            }
        } else {
            // 받침なし → 단일받침を追加
            self.coda = codaIndex
            let char = composeSyllable(onset: onset, nucleus: nucleus, coda: codaIndex)
            return .added(Output(text: String(char), replaceCount: 1))
        }
    }

    /// 현재 초성을 変更する（激音/濃音サイクル）
    mutating func modifyOnset(to newOnset: Int) -> Output? {
        guard let nucleus else { return nil }
        onset = newOnset
        let char = composeSyllable(onset: newOnset, nucleus: nucleus, coda: coda ?? 0)
        return Output(text: String(char), replaceCount: 1)
    }

    /// 현재 종성を変更する（받침サイクル）
    mutating func modifyCoda(to newCoda: Int) -> Output? {
        guard let onset, let nucleus else { return nil }
        coda = newCoda
        let char = composeSyllable(onset: onset, nucleus: nucleus, coda: newCoda)
        return Output(text: String(char), replaceCount: 1)
    }

    /// 받침を巻き戻す（内部状態のみ変更、出力なし）
    /// 即時適用した받침を取り消し、再適用するために使用
    mutating func revertCoda(to previousCoda: Int?) {
        coda = previousCoda
    }

    /// 현재 중성을 変更する（複合母音）
    mutating func modifyNucleus(to newNucleus: Int) -> Output? {
        guard let onset else { return nil }
        nucleus = newNucleus
        let char = composeSyllable(onset: onset, nucleus: newNucleus, coda: coda ?? 0)
        return Output(text: String(char), replaceCount: 1)
    }

    /// 現在の音節を確定する（状態リセット）
    mutating func commit() {
        onset = nil
        nucleus = nil
        coda = nil
    }

    /// 現在の초성 index を返す
    var currentOnset: Int? { onset }

    /// 現在の중성 index を返す
    var currentNucleus: Int? { nucleus }

    /// 現在の종성 index を返す
    var currentCoda: Int? { coda }

    // MARK: - Unicode 合成

    /// 초성・중성・종성 index から Unicode 音節文字を合成する
    private func composeSyllable(onset: Int, nucleus: Int, coda: Int = 0) -> Character {
        let code = 0xAC00 + (onset * 21 + nucleus) * 28 + coda
        return Character(UnicodeScalar(code)!)
    }
}

// MARK: - 子音テーブル

/// 行(row) → 초성 index のマッピング
let koreanOnsetForRow: [Int] = [
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
]

/// 행(row) → 종성(coda) index のマッピング（받침用）
let koreanCodaForRow: [Int] = [
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
]

/// 子音行名（ビジュアライザ用）
let koreanRowNames = ["ㅇ", "ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅈ", "ㅎ"]

/// D-pad ラベル
let koreanDpadLabelsBase = DpadLabels(center: "ㅇ", left: "ㄱ", up: "ㄴ", right: "ㄷ", down: "ㄹ")
let koreanDpadLabelsLB = DpadLabels(center: "ㅁ", left: "ㅂ", up: "ㅅ", right: "ㅈ", down: "ㅎ")

// MARK: - 母音テーブル

/// フェイスボタン → 중성 index（基本層）
/// [RB, X(左=ㅓ), Y(上=ㅗ), B(右=ㅏ), A(下=ㅜ)] — 字形の方向に対応
let koreanNucleusBase: [Int] = [
    18,  // RB: ㅡ (eu) — 水平線、方向なし
    4,   // X(左): ㅓ (eo) — 左向きの画
    8,   // Y(上): ㅗ (o) — 上向きの画
    0,   // B(右): ㅏ (a) — 右向きの画
    13,  // A(下): ㅜ (u) — 下向きの画
]

/// フェイスボタン → 중성 index（RT シフト層: y系母音 + ㅣ）
let koreanNucleusShifted: [Int] = [
    20,  // RB: ㅣ (i) — 垂直線
    6,   // X(左): ㅕ (yeo)
    12,  // Y(上): ㅛ (yo)
    2,   // B(右): ㅑ (ya)
    17,  // A(下): ㅠ (yu)
]

/// ビジュアライザ用：母音表示文字（基本層）
let koreanVowelCharsBase = ["ㅡ", "ㅓ", "ㅗ", "ㅏ", "ㅜ"]

/// ビジュアライザ用：母音表示文字（RT シフト層）
let koreanVowelCharsShifted = ["ㅣ", "ㅕ", "ㅛ", "ㅑ", "ㅠ"]

// MARK: - 子音サイクル（平音→激音→濃音）

/// 초성 index の子音変換サイクル: 平音→激音→濃音→平音
/// 対応がない場合は変化なし
let koreanOnsetCycle: [Int: Int] = [
    // ㄱ(0) → ㅋ(15) → ㄲ(1) → ㄱ(0)
    0: 15, 15: 1, 1: 0,
    // ㄷ(3) → ㅌ(16) → ㄸ(4) → ㄷ(3)
    3: 16, 16: 4, 4: 3,
    // ㅂ(7) → ㅍ(17) → ㅃ(8) → ㅂ(7)
    7: 17, 17: 8, 8: 7,
    // ㅅ(9) → ㅆ(10) → ㅅ(9)
    9: 10, 10: 9,
    // ㅈ(12) → ㅊ(14) → ㅉ(13) → ㅈ(12)
    12: 14, 14: 13, 13: 12,
]

// MARK: - 複合母音

/// RT（ㅣ付加）による複合母音変換
let koreanNucleusAddI: [Int: Int] = [
    0: 1,    // ㅏ → ㅐ
    4: 5,    // ㅓ → ㅔ
    2: 3,    // ㅑ → ㅒ
    6: 7,    // ㅕ → ㅖ
    8: 11,   // ㅗ → ㅚ
    13: 16,  // ㅜ → ㅟ
    18: 19,  // ㅡ → ㅢ
]

/// 右スティック→（ㅏ/ㅓ付加）による複合母音変換
let koreanNucleusAddAEo: [Int: Int] = [
    8: 9,    // ㅗ → ㅘ (ㅗ+ㅏ)
    11: 10,  // ㅚ → ㅙ (ㅗ+ㅐ)
    13: 14,  // ㅜ → ㅝ (ㅜ+ㅓ)
    16: 15,  // ㅟ → ㅞ (ㅜ+ㅔ)
]

// MARK: - 겹받침（二重終声）

/// 겹받침テーブル: [第1종성][第2종성] → 겹받침종성
/// 有効な11組合せのみ登録
let koreanDoubleCoda: [Int: [Int: Int]] = [
    1: [19: 3],              // ㄱ(1) + ㅅ(19) → ㄳ(3)
    4: [22: 5, 27: 6],      // ㄴ(4) + ㅈ(22) → ㄵ(5), ㄴ(4) + ㅎ(27) → ㄶ(6)
    8: [                     // ㄹ(8) + ...
        1: 9,                //   ㄱ(1)  → ㄺ(9)
        16: 10,              //   ㅁ(16) → ㄻ(10)
        17: 11,              //   ㅂ(17) → ㄼ(11)
        19: 12,              //   ㅅ(19) → ㄽ(12)
        25: 13,              //   ㅌ(25) → ㄾ(13)
        26: 14,              //   ㅍ(26) → ㄿ(14)
        27: 15,              //   ㅎ(27) → ㅀ(15)
    ],
    17: [19: 18],            // ㅂ(17) + ㅅ(19) → ㅄ(18)
]

// MARK: - 받침サイクル（平音→激音→濃音）

/// 종성 index の받침サイクル: 平音→激音→濃音→平音
/// 초성サイクルの받침版。R🕹↑ で받침がある場合に使用
let koreanCodaCycle: [Int: Int] = [
    1: 24, 24: 2, 2: 1,    // ㄱ(1) → ㅋ(24) → ㄲ(2) → ㄱ
    7: 25, 25: 7,           // ㄷ(7) → ㅌ(25) → ㄷ（ㄸ는 종성 없음）
    17: 26, 26: 17,         // ㅂ(17) → ㅍ(26) → ㅂ（ㅃ는 종성 없음）
    19: 20, 20: 19,         // ㅅ(19) → ㅆ(20) → ㅅ
    22: 23, 23: 22,         // ㅈ(22) → ㅊ(23) → ㅈ（ㅉ는 종성 없음）
]

package com.gime.android.engine

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

object DevanagariUnicode {
    const val VIRAMA: Char = '्'       // ्
    const val NUKTA: Char = '़'        // ़
    const val ANUSVARA: Char = 'ं'     // ं
    const val CHANDRABINDU: Char = 'ँ' // ँ
    const val VISARGA: Char = 'ः'      // ः
}

/// Composer の内部状態
private enum class DevaState {
    EMPTY,                // 空 or cluster 未開始
    CONSONANT_OPEN,       // 末尾が子音（+nukta 可）で inherent schwa を背負っている
    MATRA_CLOSED,         // matra が付いて akshara 確定済み
    VOWEL_CLOSED,         // 独立母音で cluster 確定済み
    HALANT_CLOSED,        // 明示 halant で子音単独（次も halant-conjunct 不可、新 cluster 開始）
    MODIFIER_CLOSED,      // anusvara / chandrabindu / visarga 付与済み
}

/// 独立母音 ↔ matra ↔ 長音 変換テーブル
/// 各 pair: (短, 長)
val DEVA_VOWEL_PAIRS: List<Pair<Char, Char>> = listOf(
    'अ' to 'आ',  // अ → आ
    'इ' to 'ई',  // इ → ई
    'उ' to 'ऊ',  // उ → ऊ
    'ऋ' to 'ॠ',  // ऋ → ॠ
    'ए' to 'ऐ',  // ए → ऐ (guna → vrddhi 扱い)
    'ओ' to 'औ',  // ओ → औ (同上)
)

/// matra pair: (短 matra, 長 matra)
/// 独立 अ には matra が無い（inherent schwa）。短 "aa" matra はそれ自体 ा (0x093E)
/// で独立 अ の長音 आ の matra に相当するため、短→長の「長母音 post-shift」は
/// matra (短) → matra (長) の単純置換。ただし「अ→आ」は matra ा として表現される
/// ので、inherent schwa の状態で long-shift した場合は ा を追加する扱い。
val DEVA_MATRA_PAIRS: List<Pair<Char, Char>> = listOf(
    'ा' to 'ा',  // ा → ā (既に長、no-op)
    'ि' to 'ी',  // ि → ी
    'ु' to 'ू',  // ु → ू
    'ृ' to 'ॄ',  // ृ → ॄ
    'े' to 'ै',  // े → ै
    'ो' to 'ौ',  // ो → ौ
)

/// 短独立母音 → 短 matra
val DEVA_VOWEL_TO_MATRA: Map<Char, Char> = mapOf(
    'अ' to 'ा',  // अ → ा (inherent schwa は表現されないが、
                            //          user が「あ独立母音を子音に貼る」操作をしたら
                            //          長形 ा として貼る）
    'इ' to 'ि',  // इ → ि
    'उ' to 'ु',  // उ → ु
    'ऋ' to 'ृ',  // ऋ → ृ
    'ए' to 'े',  // ए → े
    'ओ' to 'ो',  // ओ → ो
    'आ' to 'ा',  // आ → ा
    'ई' to 'ी',  // ई → ी
    'ऊ' to 'ू',  // ऊ → ू
    'ॠ' to 'ॄ',  // ॠ → ॄ
    'ऐ' to 'ै',  // ऐ → ै
    'औ' to 'ौ',  // औ → ौ
)

/// 短母音 → 長母音（独立形）
val DEVA_VOWEL_SHORT_TO_LONG: Map<Char, Char> =
    DEVA_VOWEL_PAIRS.associate { it.first to it.second }

/// 短 matra → 長 matra
val DEVA_MATRA_SHORT_TO_LONG: Map<Char, Char> =
    DEVA_MATRA_PAIRS.associate { it.first to it.second }.filterKeys { it != 'ा' } +
    // ा (short "aa" matra) はそれ自体が長なので post-shift は no-op。
    // ただし inherent schwa 状態での post-shift は「ा を追加」として扱うので
    // 実際の処理は applyLongShift 側で個別対応。
    mapOf<Char, Char>()

/// 独立母音 or matra が長形式か判定
val DEVA_LONG_FORMS: Set<Char> = setOf(
    'आ', 'ई', 'ऊ', 'ॠ', 'ऐ', 'औ',  // 独立長母音
    'ी', 'ू', 'ॄ', 'ै', 'ौ',              // 長 matra
    'ा',                                                       // ा も長扱い
)

/// Devanagari 合成エンジン
class DevanagariComposer {
    private var buffer: String = ""
    private var state: DevaState = DevaState.EMPTY

    /// 最後の consonant に nukta が付いているか（重ね押し防止）
    private var lastConsonantHasNukta: Boolean = false

    /// 合成中か（IME 側で composing 判定に使う）
    val isComposing: Boolean get() = buffer.isNotEmpty()

    val currentBuffer: String get() = buffer

    // MARK: - 入力 API

    /// 子音を入力する。
    /// 前の状態にかかわらず子音を単純に追加する（auto-conjunct しない）。
    /// 直前が HALANT_CLOSED（明示 halant 直後）の場合、結果として virama + 子音 の
    /// Unicode シーケンスになり、レンダリング上は conjunct になる。
    fun inputConsonant(consonant: Char): ComposerOutput {
        buffer += consonant
        state = DevaState.CONSONANT_OPEN
        lastConsonantHasNukta = false
        return ComposerOutput(consonant.toString(), replaceCount = 0)
    }

    /// matra（従属母音記号）を入力する。子音に対してのみ適用可能。
    /// inherent schwa を置換し akshara を閉じる。
    /// 子音が無い状態で呼ばれた場合は「独立母音として解釈」する（呼び側でやるなら不要だが、
    /// ロバストネスのため）。
    fun inputMatra(matra: Char): ComposerOutput? {
        if (state != DevaState.CONSONANT_OPEN) return null
        buffer += matra
        state = DevaState.MATRA_CLOSED
        return ComposerOutput(matra.toString(), replaceCount = 0)
    }

    /// 独立母音を入力する。常に新 cluster を開始する。
    fun inputIndependentVowel(vowel: Char): ComposerOutput {
        buffer += vowel
        state = DevaState.VOWEL_CLOSED
        return ComposerOutput(vowel.toString(), replaceCount = 0)
    }

    /// 明示的 halant (virama) を入力する。子音直後にのみ意味を持つ。
    /// cluster は閉じられ、次の子音は halant-conjunct にならず新 cluster 開始扱い。
    fun inputHalant(): ComposerOutput? {
        if (state != DevaState.CONSONANT_OPEN) return null
        buffer += DevanagariUnicode.VIRAMA
        state = DevaState.HALANT_CLOSED
        return ComposerOutput(DevanagariUnicode.VIRAMA.toString(), replaceCount = 0)
    }

    /// nukta を直前子音に付加する。既に nukta がある or 子音直後でない場合は no-op。
    fun inputNukta(): ComposerOutput? {
        if (state != DevaState.CONSONANT_OPEN || lastConsonantHasNukta) return null
        buffer += DevanagariUnicode.NUKTA
        lastConsonantHasNukta = true
        return ComposerOutput(DevanagariUnicode.NUKTA.toString(), replaceCount = 0)
    }

    /// anusvara ↔ chandrabindu をトグル。既に付いていればトグル、なければ anusvara 追加。
    /// cluster 末尾修飾なので、子音直後 / matra 後 / 独立母音後 のいずれでも可。
    fun toggleAnusvara(): ComposerOutput? {
        if (buffer.isEmpty()) return null
        val last = buffer.last()
        return when (last) {
            DevanagariUnicode.ANUSVARA -> {
                buffer = buffer.dropLast(1) + DevanagariUnicode.CHANDRABINDU
                state = DevaState.MODIFIER_CLOSED
                ComposerOutput(DevanagariUnicode.CHANDRABINDU.toString(), replaceCount = 1)
            }
            DevanagariUnicode.CHANDRABINDU -> {
                buffer = buffer.dropLast(1)
                // toggle off: chandrabindu 除去 → 前の状態（matra/vowel/consonant）に戻る
                state = recomputeState()
                ComposerOutput("", replaceCount = 1)
            }
            else -> {
                // 直接付与できる状態か: CONSONANT_OPEN, MATRA_CLOSED, VOWEL_CLOSED
                if (state == DevaState.CONSONANT_OPEN ||
                    state == DevaState.MATRA_CLOSED ||
                    state == DevaState.VOWEL_CLOSED
                ) {
                    buffer += DevanagariUnicode.ANUSVARA
                    state = DevaState.MODIFIER_CLOSED
                    ComposerOutput(DevanagariUnicode.ANUSVARA.toString(), replaceCount = 0)
                } else null
            }
        }
    }

    /// visarga を付与する。CONSONANT_OPEN / MATRA_CLOSED / VOWEL_CLOSED 状態で可。
    fun inputVisarga(): ComposerOutput? {
        if (state != DevaState.CONSONANT_OPEN &&
            state != DevaState.MATRA_CLOSED &&
            state != DevaState.VOWEL_CLOSED
        ) return null
        buffer += DevanagariUnicode.VISARGA
        state = DevaState.MODIFIER_CLOSED
        return ComposerOutput(DevanagariUnicode.VISARGA.toString(), replaceCount = 0)
    }

    /// 直前の母音/matra を長形に昇格する（RS→ post-shift）。
    /// - inherent schwa 状態（CONSONANT_OPEN, 子音単独）: ा を追加して "Cā" に。
    /// - 短 matra: 対応する長 matra に置換。
    /// - 短独立母音: 対応する長独立母音に置換。
    /// - 既に長形 or 対象外: no-op で null。
    fun applyLongShift(): ComposerOutput? {
        if (buffer.isEmpty()) return null
        val last = buffer.last()

        // inherent schwa 状態で long-shift → ा matra を追加
        if (state == DevaState.CONSONANT_OPEN && !lastConsonantHasNukta) {
            buffer += 'ा'
            state = DevaState.MATRA_CLOSED
            return ComposerOutput("ा", replaceCount = 0)
        }
        // 既に nukta が付いていても append は可能
        if (state == DevaState.CONSONANT_OPEN && lastConsonantHasNukta) {
            buffer += 'ा'
            state = DevaState.MATRA_CLOSED
            return ComposerOutput("ा", replaceCount = 0)
        }

        // 短 matra → 長 matra
        DEVA_MATRA_SHORT_TO_LONG[last]?.let { longMatra ->
            buffer = buffer.dropLast(1) + longMatra
            return ComposerOutput(longMatra.toString(), replaceCount = 1)
        }
        // 短独立母音 → 長独立母音
        DEVA_VOWEL_SHORT_TO_LONG[last]?.let { longVowel ->
            buffer = buffer.dropLast(1) + longVowel
            return ComposerOutput(longVowel.toString(), replaceCount = 1)
        }
        return null
    }

    /// 1 文字 backspace。state を再計算。
    fun backspace(): ComposerOutput? {
        if (buffer.isEmpty()) return null
        buffer = buffer.dropLast(1)
        lastConsonantHasNukta = buffer.isNotEmpty() && buffer.last() == DevanagariUnicode.NUKTA
        state = recomputeState()
        return ComposerOutput("", replaceCount = 1)
    }

    /// cluster を確定し、内部状態をクリアする。
    /// buffer のテキストはそのまま（IME 側で committed として扱う）。
    fun commit() {
        buffer = ""
        state = DevaState.EMPTY
        lastConsonantHasNukta = false
    }

    // MARK: - 内部ロジック

    /// buffer の末尾から state を再計算する。
    private fun recomputeState(): DevaState {
        if (buffer.isEmpty()) return DevaState.EMPTY
        val last = buffer.last()
        return when {
            last == DevanagariUnicode.VIRAMA -> DevaState.HALANT_CLOSED
            last == DevanagariUnicode.ANUSVARA ||
                last == DevanagariUnicode.CHANDRABINDU ||
                last == DevanagariUnicode.VISARGA -> DevaState.MODIFIER_CLOSED
            last == DevanagariUnicode.NUKTA -> DevaState.CONSONANT_OPEN
            isDevanagariMatra(last) -> DevaState.MATRA_CLOSED
            isDevanagariIndependentVowel(last) -> DevaState.VOWEL_CLOSED
            isDevanagariConsonant(last) -> DevaState.CONSONANT_OPEN
            else -> DevaState.EMPTY
        }
    }
}

/// Devanagari 文字分類（U+0900..U+097F ブロック内）

/// 子音: U+0915 (क) .. U+0939 (ह)
fun isDevanagariConsonant(c: Char): Boolean = c.code in 0x0915..0x0939

/// 従属母音記号 (matra): U+093A..U+094C (except U+093C nukta, U+094D virama)
/// 実用的には U+093E..U+094C が matra。
fun isDevanagariMatra(c: Char): Boolean = c.code in 0x093E..0x094C

/// 独立母音: U+0904..U+0914 + U+0960 (ॠ) + U+0961 (ॡ)
fun isDevanagariIndependentVowel(c: Char): Boolean =
    c.code in 0x0904..0x0914 || c.code == 0x0960 || c.code == 0x0961

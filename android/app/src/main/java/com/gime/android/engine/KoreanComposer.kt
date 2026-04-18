package com.gime.android.engine

/// ハングル音節合成エンジン（2ボル式ベース）
/// iOS GIME の KoreanComposer.swift から移植
///
/// Unicode Hangul Syllables ブロック（U+AC00–U+D7A3）の合成式:
///   syllable = 0xAC00 + (onset × 21 + nucleus) × 28 + coda

/// 合成結果
data class ComposerOutput(
    val text: String,
    val replaceCount: Int,
)

/// 받침入力の結果
sealed class PatchimResult {
    data class Added(val output: ComposerOutput) : PatchimResult()
}

/// ハングル音節の合成状態
class KoreanComposer {
    private var onset: Int? = null     // 초성 index (0-18)
    private var nucleus: Int? = null   // 중성 index (0-20)
    private var coda: Int? = null      // 종성 index (1-27, null = なし)

    /// 合成中かどうか
    val isComposing: Boolean get() = onset != null

    /// 現在の초성 index
    val currentOnset: Int? get() = onset

    /// 現在の중성 index
    val currentNucleus: Int? get() = nucleus

    /// 現在の종성 index
    val currentCoda: Int? get() = coda

    // MARK: - 入力

    /// 子音+母音で新しい音節を入力する
    fun inputSyllable(onset: Int, nucleus: Int): ComposerOutput {
        commit()
        this.onset = onset
        this.nucleus = nucleus
        this.coda = null
        val char = composeSyllable(onset, nucleus)
        return ComposerOutput(char.toString(), replaceCount = 0)
    }

    /// 받침（종성）を追加する（2ボル式スタイル）
    fun inputPatchim(codaIndex: Int, codaRow: Int): PatchimResult? {
        val onset = this.onset ?: return null
        val nucleus = this.nucleus ?: return null

        val existingCoda = this.coda
        if (existingCoda != null) {
            // 既に받침がある → 겹받침を試みる
            val doubleCoda = KOREAN_DOUBLE_CODA[existingCoda]?.get(codaIndex)
            if (doubleCoda != null) {
                this.coda = doubleCoda
                val char = composeSyllable(onset, nucleus, doubleCoda)
                return PatchimResult.Added(ComposerOutput(char.toString(), replaceCount = 1))
            } else {
                // 겹받침不成立: 無視
                return null
            }
        } else {
            // 받침なし → 단일받침を追加
            this.coda = codaIndex
            val char = composeSyllable(onset, nucleus, codaIndex)
            return PatchimResult.Added(ComposerOutput(char.toString(), replaceCount = 1))
        }
    }

    /// 현재 초성を変更する（激音/濃音サイクル）
    fun modifyOnset(newOnset: Int): ComposerOutput? {
        val nucleus = this.nucleus ?: return null
        this.onset = newOnset
        val char = composeSyllable(newOnset, nucleus, coda ?: 0)
        return ComposerOutput(char.toString(), replaceCount = 1)
    }

    /// 현재 종성を変更する（받침サイクル）
    fun modifyCoda(newCoda: Int): ComposerOutput? {
        val onset = this.onset ?: return null
        val nucleus = this.nucleus ?: return null
        this.coda = newCoda
        val char = composeSyllable(onset, nucleus, newCoda)
        return ComposerOutput(char.toString(), replaceCount = 1)
    }

    /// 받침を巻き戻す（内部状態のみ変更、出力なし）
    fun revertCoda(previousCoda: Int?) {
        this.coda = previousCoda
    }

    /// 현재 중성を変更する（複合母音）
    fun modifyNucleus(newNucleus: Int): ComposerOutput? {
        val onset = this.onset ?: return null
        this.nucleus = newNucleus
        val char = composeSyllable(onset, newNucleus, coda ?: 0)
        return ComposerOutput(char.toString(), replaceCount = 1)
    }

    /// 現在の音節を確定する（状態リセット）
    fun commit() {
        onset = null
        nucleus = null
        coda = null
    }

    // MARK: - Unicode 合成

    /// 초성・중성・종성 index から Unicode 音節文字を合成する
    private fun composeSyllable(onset: Int, nucleus: Int, coda: Int = 0): Char {
        val code = 0xAC00 + (onset * 21 + nucleus) * 28 + coda
        return code.toChar()
    }
}

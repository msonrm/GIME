package com.gime.android.translate

import android.util.Log
import com.github.houbb.opencc4j.support.datamap.impl.DataMaps
import com.github.houbb.opencc4j.util.ZhConverterUtil

/**
 * ML Kit Translate の中文出力（簡体）を後処理して繁體（台湾風）に変換するヘルパー。
 *
 * opencc4j の `ZhConvertBootstrap.toTraditional()` は `DataMaps.taiwan()` を渡しても
 * 内部で stPhrase() の lookup を行わない（debug 確認済み: `软件 → 軟件` で止まる）。
 * 一方 `DataMaps.taiwan().stPhrase()` には 49,000+ の S→T フレーズマッピングが
 * 入っており、`软件 → 軟體` `视频 → 影片` `网络 → 網路` 等の台湾語彙置換を含む。
 *
 * 自前で greedy longest-match を回してフレーズ置換を済ませてから、残った単漢字を
 * `ZhConverterUtil.toTraditional()` で基本 S→T 変換する 2 段構成にする。
 *
 * メモリは 49,000 エントリの HashMap で ~数 MB、初回 lazy init で ~数百 ms かかる
 * 程度。VRChat 用の二段送信なら translate API のレイテンシに紛れて問題にならない。
 */
object ChineseConverter {

    private const val TAG = "ChineseConverter"

    /// Taiwan 用 S→T フレーズマップ。`DataMaps.taiwan().stPhrase()` から構築。
    /// 値は List だが、最初のエントリのみ使う（OpenCC の慣例で先頭が代表変換）。
    private val taiwanPhraseMap: Map<String, String> by lazy {
        try {
            DataMaps.taiwan().stPhrase()
                .mapNotNull { (k, v) -> v.firstOrNull()?.let { k to it } }
                .toMap()
                .also { Log.d(TAG, "loaded ${it.size} Taiwan phrases") }
        } catch (t: Throwable) {
            Log.w(TAG, "load Taiwan phrase map failed", t)
            emptyMap()
        }
    }

    /// 長さ別に分けたフレーズマップ。greedy longest-match の高速化用。
    private val phrasesByLength: Map<Int, Map<String, String>> by lazy {
        taiwanPhraseMap.entries
            .groupBy({ it.key.length }, { it.toPair() })
            .mapValues { (_, entries) -> entries.toMap() }
    }

    private val maxPhraseLength: Int by lazy {
        phrasesByLength.keys.maxOrNull() ?: 0
    }

    /// 簡体中文 → 繁體中文（台湾風）。失敗時は入力をそのまま返す。
    fun simplifiedToTraditionalTaiwan(text: String): String {
        if (text.isEmpty()) return text
        return try {
            val withTaiwanVocab = applyTaiwanPhrases(text)
            ZhConverterUtil.toTraditional(withTaiwanVocab)
        } catch (t: Throwable) {
            Log.w(TAG, "conversion failed", t)
            text
        }
    }

    /// 台湾フレーズの greedy longest-match 適用。長さ 2 以上のみ扱い、
    /// 単漢字は後段の `toTraditional()` に任せる。
    private fun applyTaiwanPhrases(text: String): String {
        if (taiwanPhraseMap.isEmpty()) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val remaining = text.length - i
            val tryLen = minOf(maxPhraseLength, remaining)
            var matched: String? = null
            var matchedLen = 0
            for (len in tryLen downTo 2) {
                val table = phrasesByLength[len] ?: continue
                val candidate = text.substring(i, i + len)
                val replacement = table[candidate]
                if (replacement != null) {
                    matched = replacement
                    matchedLen = len
                    break
                }
            }
            if (matched != null) {
                sb.append(matched)
                i += matchedLen
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }
}

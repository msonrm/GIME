package com.gime.android.engine

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/// CJK 入力候補検索エンジン
/// iOS GIME の PinyinEngine.swift から移植
///
/// Abbreviated pinyin (简拼) / abbreviated zhuyin (注音首) で候補を検索する。

// MARK: - 中国語変異体

enum class ChineseVariant {
    SIMPLIFIED,
    TRADITIONAL,
}

// MARK: - 候補データ（JSON デシリアライズ用）

@Serializable
private data class SimplifiedEntry(
    val w: String,  // 簡体字
    val t: String,  // 繁体字
    val p: String,  // ピンイン
)

@Serializable
private data class TraditionalEntry(
    val w: String,  // 繁体字
    val z: String,  // 注音
    val p: String,  // abbreviated pinyin key
)

/// 統一候補型（外部に公開）
data class PinyinCandidate(
    val word: String,
    val reading: String,
)

// MARK: - 検索エンジン

/// CJK 候補検索エンジン
class PinyinEngine {
    var variant: ChineseVariant = ChineseVariant.SIMPLIFIED

    var isSimplifiedLoaded: Boolean = false
        private set
    var isTraditionalLoaded: Boolean = false
        private set

    private var simplifiedIndex: Map<String, List<PinyinCandidate>> = emptyMap()
    private var traditionalIndex: Map<String, List<PinyinCandidate>> = emptyMap()

    private val json = Json { ignoreUnknownKeys = true }

    /// 簡体字辞書をロードする
    fun loadSimplified(context: Context) {
        if (isSimplifiedLoaded) return
        try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("pinyin_abbrev", "raw", context.packageName)
            )
            val text = inputStream.bufferedReader().use { it.readText() }
            val raw = json.decodeFromString<Map<String, List<SimplifiedEntry>>>(text)
            simplifiedIndex = raw.mapValues { (_, entries) ->
                entries.map { PinyinCandidate(word = it.w, reading = it.p) }
            }
            isSimplifiedLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /// 繁体字辞書をロードする
    fun loadTraditional(context: Context) {
        if (isTraditionalLoaded) return
        try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("zhuyin_abbrev", "raw", context.packageName)
            )
            val text = inputStream.bufferedReader().use { it.readText() }
            val raw = json.decodeFromString<Map<String, List<TraditionalEntry>>>(text)
            traditionalIndex = raw.mapValues { (_, entries) ->
                entries.map { PinyinCandidate(word = it.w, reading = it.z) }
            }
            isTraditionalLoaded = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /// 両方の辞書をロードする
    fun load(context: Context) {
        loadSimplified(context)
        loadTraditional(context)
    }

    /// abbreviated key で候補を検索する
    fun lookup(abbreviation: String, limit: Int = 30): List<PinyinCandidate> {
        if (abbreviation.isEmpty()) return emptyList()
        val key = abbreviation.lowercase()
        val index = if (variant == ChineseVariant.SIMPLIFIED) simplifiedIndex else traditionalIndex
        return index[key]?.take(limit) ?: emptyList()
    }

    /// 候補の表示テキストを返す
    fun displayText(candidate: PinyinCandidate): String = candidate.word
}

package com.gime.android.engine

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kazumaproject.Louds.LOUDS
import com.kazumaproject.Louds.with_term_id.LOUDSWithTermId
import com.kazumaproject.connection_id.ConnectionIdBuilder
import com.kazumaproject.dictionary.TokenArray
import com.kazumaproject.markdownhelperkeyboard.converter.bitset.SuccinctBitVector
import com.kazumaproject.markdownhelperkeyboard.converter.engine.EnglishEngine
import com.kazumaproject.markdownhelperkeyboard.converter.engine.KanaKanjiEngine
import com.kazumaproject.markdownhelperkeyboard.converter.english.louds.LOUDS as EnglishLOUDS
import com.kazumaproject.markdownhelperkeyboard.converter.english.louds.louds_with_term_id.LOUDSWithTermId as EnglishLOUDSWithTermId
import com.kazumaproject.markdownhelperkeyboard.converter.english.tokenArray.TokenArray as EnglishTokenArray
import com.kazumaproject.markdownhelperkeyboard.converter.graph.GraphBuilder
import com.kazumaproject.markdownhelperkeyboard.converter.path_algorithm.FindPath
import com.kazumaproject.markdownhelperkeyboard.repository.LearnRepository
import com.kazumaproject.markdownhelperkeyboard.repository.UserDictionaryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.ObjectInputStream
import java.util.zip.ZipInputStream

/// 日本語かな漢字変換エンジン（Phase A2.3: KazumaProject/JapaneseKeyboard 流用版）
///
/// MIT ライセンスで公開されている KazumaProject/JapaneseKeyboard の変換エンジン
/// （LOUDS trie + N-gram 言語モデル）を vendor して利用する。
/// 我々は KanaKanjiEngine をロードして `getCandidates` を呼ぶだけのファサード。
class JapaneseConverter {

    /// 変換候補
    data class Candidate(
        val surface: String,
        val reading: String,
        val cost: Int,
    )

    /// 文節分割された変換結果
    /// - readings: 各文節のひらがな読み（全部連結すると元の入力に一致）
    /// - candidates: 各文節の n-best 候補
    data class BunsetsuResult(
        val readings: List<String>,
        val candidates: List<List<Candidate>>,
    ) {
        val size: Int get() = readings.size
    }

    @Volatile
    private var engine: KanaKanjiEngine? = null
    /// ユーザー辞書と学習辞書。Phase A4a で Room 実装を外部から注入可能にした。
    /// 未注入の場合は空リストを返すフェイルセーフのため、コンストラクタ時点では
    /// DAO 無しのデフォルトに落とす。`MainActivity` 側で DB 注入版に差し替える。
    @Volatile
    var userDict: UserDictionaryRepository = UserDictionaryRepository()
    @Volatile
    var learnRepo: LearnRepository? = null

    @Volatile
    private var ready: Boolean = false

    private val initLock = Mutex()

    val isReady: Boolean get() = ready

    /// 直近のエラー（UI 表示用）。logcat が取れない環境での診断用。
    var lastError: String? by mutableStateOf(null)
        private set

    /// 辞書を非同期でロードする。
    fun initializeAsync(context: Context, scope: CoroutineScope) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            initLock.withLock {
                if (ready) return@withLock
                try {
                    engine = buildEngineFromAssets(appContext)
                    ready = true
                    android.util.Log.i("JapaneseConverter", "dictionary loaded successfully")
                } catch (t: Throwable) {
                    android.util.Log.e("JapaneseConverter", "dictionary load failed", t)
                    lastError = "init: ${t::class.simpleName}: ${t.message ?: "?"} @ ${t.firstAppFrame()}"
                }
            }
        }
    }

    /// ひらがな読みを変換して候補リストを返す（n-best）。
    suspend fun convert(reading: String, maxCandidates: Int = 30): List<Candidate> {
        if (!ready || reading.isEmpty()) return emptyList()
        val e = engine ?: return emptyList()

        val kkCandidates = try {
            e.getCandidatesWithoutPrediction(
                input = reading,
                n = maxCandidates,
                mozcUtPersonName = null,
                mozcUTPlaces = null,
                mozcUTWiki = null,
                mozcUTNeologd = null,
                mozcUTWeb = null,
                userDictionaryRepository = userDict,
                learnRepository = learnRepo,
                typoCorrectionOffsetScore = 0,
                omissionSearchOffsetScore = 0,
            )
        } catch (t: Throwable) {
            android.util.Log.e("JapaneseConverter", "conversion failed for '$reading'", t)
            lastError = "convert('$reading'): ${t::class.simpleName}: ${t.message ?: "?"} @ ${t.firstAppFrame()}"
            return emptyList()
        }

        val seen = LinkedHashMap<String, Candidate>(kkCandidates.size + 1)
        for (c in kkCandidates) {
            val surface = c.string
            if (surface.isBlank()) continue
            seen.putIfAbsent(
                surface,
                Candidate(surface = surface, reading = reading, cost = c.score),
            )
        }
        // 必ずひらがなそのままを候補に入れる（なければ）
        seen.putIfAbsent(reading, Candidate(reading, reading, Int.MAX_VALUE / 2))
        return seen.values.toList()
    }

    /// 文節分割された変換結果を返す。
    /// エンジンの bunsetsu API で読み位置での分割点を取得し、
    /// 各文節については通常の convert() で n-best を取る。
    suspend fun convertBunsetsu(reading: String, nPerBunsetsu: Int = 20): BunsetsuResult? {
        if (!ready || reading.isEmpty()) return null
        val e = engine ?: return null

        val splits: List<Int> = try {
            val r = e.getCandidatesWithoutPredictionWithBunsetsu(
                input = reading,
                n = 1,
                mozcUtPersonName = null,
                mozcUTPlaces = null,
                mozcUTWiki = null,
                mozcUTNeologd = null,
                mozcUTWeb = null,
                userDictionaryRepository = userDict,
                learnRepository = learnRepo,
                typoCorrectionOffsetScore = 0,
                omissionSearchOffsetScore = 0,
            )
            r.primarySplitPositions.filter { it in 1 until reading.length }
        } catch (t: Throwable) {
            android.util.Log.e("JapaneseConverter", "bunsetsu split failed for '$reading'", t)
            lastError = "bunsetsuSplit('$reading'): ${t::class.simpleName}: ${t.message ?: "?"} @ ${t.firstAppFrame()}"
            // fallback: 全体を 1 文節として扱う
            emptyList()
        }

        // 読みを分割
        val readings = mutableListOf<String>()
        var last = 0
        for (s in splits) {
            if (s > last) {
                readings += reading.substring(last, s)
                last = s
            }
        }
        if (last < reading.length) readings += reading.substring(last)

        // 各文節を個別に convert
        val allCandidates = readings.map { r ->
            val cands = convert(r, maxCandidates = nPerBunsetsu)
            if (cands.isEmpty()) listOf(Candidate(r, r, 0)) else cands
        }

        return BunsetsuResult(readings, allCandidates)
    }

    /// 変換確定時に読み→surface を学習データへ記録する。
    /// DAO が未接続なら no-op。呼び出し側は起動直後の確定でも安全に呼べる。
    suspend fun recordLearning(reading: String, surface: String) {
        learnRepo?.record(reading, surface)
    }

    // MARK: - 辞書ロード

    private fun buildEngineFromAssets(context: Context): KanaKanjiEngine {
        val kanaKanjiEngine = KanaKanjiEngine()
        val graphBuilder = GraphBuilder()
        val findPath = FindPath()  // デフォルトの N-gram スコアラ

        val connectionIds = loadConnectionIds(context)

        val system = loadDictionarySet(context, "system", "tango.dat.zip", "yomi.dat.zip", "token.dat.zip", zipped = true)
        val singleKanji = loadDictionarySet(context, "single_kanji", "tango_singleKanji.dat", "yomi_singleKanji.dat", "token_singleKanji.dat", zipped = false)
        val emoji = loadDictionarySet(context, "emoji", "tango_emoji.dat", "yomi_emoji.dat", "token_emoji.dat", zipped = false)
        val emoticon = loadDictionarySet(context, "emoticon", "tango_emoticon.dat", "yomi_emoticon.dat", "token_emoticon.dat", zipped = false)
        val symbol = loadDictionarySet(context, "symbol", "tango_symbol.dat", "yomi_symbol.dat", "token_symbol.dat", zipped = false)
        val readingCorrection = loadDictionarySet(context, "reading_correction", "tango_reading_correction.dat", "yomi_reading_correction.dat", "token_reading_correction.dat", zipped = false)
        val kotowaza = loadDictionarySet(context, "kotowaza", "tango_kotowaza.dat", "yomi_kotowaza.dat", "token_kotowaza.dat", zipped = false)

        val englishEngine = buildEnglishEngine(context)

        kanaKanjiEngine.buildEngine(
            graphBuilder = graphBuilder,
            findPath = findPath,
            connectionIdList = connectionIds,

            systemTangoTrie = system.tango,
            systemYomiTrie = system.yomi,
            systemTokenArray = system.token,
            systemSuccinctBitVectorLBSYomi = SuccinctBitVector(system.yomi.LBS),
            systemSuccinctBitVectorIsLeafYomi = SuccinctBitVector(system.yomi.isLeaf),
            systemSuccinctBitVectorTokenArray = SuccinctBitVector(system.token.bitvector),
            systemSuccinctBitVectorTangoLBS = SuccinctBitVector(system.tango.LBS),

            singleKanjiTangoTrie = singleKanji.tango,
            singleKanjiYomiTrie = singleKanji.yomi,
            singleKanjiTokenArray = singleKanji.token,
            singleKanjiSuccinctBitVectorLBSYomi = SuccinctBitVector(singleKanji.yomi.LBS),
            singleKanjiSuccinctBitVectorIsLeafYomi = SuccinctBitVector(singleKanji.yomi.isLeaf),
            singleKanjiSuccinctBitVectorTokenArray = SuccinctBitVector(singleKanji.token.bitvector),
            singleKanjiSuccinctBitVectorTangoLBS = SuccinctBitVector(singleKanji.tango.LBS),

            emojiTangoTrie = emoji.tango,
            emojiYomiTrie = emoji.yomi,
            emojiTokenArray = emoji.token,
            emojiSuccinctBitVectorLBSYomi = SuccinctBitVector(emoji.yomi.LBS),
            emojiSuccinctBitVectorIsLeafYomi = SuccinctBitVector(emoji.yomi.isLeaf),
            emojiSuccinctBitVectorTokenArray = SuccinctBitVector(emoji.token.bitvector),
            emojiSuccinctBitVectorTangoLBS = SuccinctBitVector(emoji.tango.LBS),

            emoticonTangoTrie = emoticon.tango,
            emoticonYomiTrie = emoticon.yomi,
            emoticonTokenArray = emoticon.token,
            emoticonSuccinctBitVectorLBSYomi = SuccinctBitVector(emoticon.yomi.LBS),
            emoticonSuccinctBitVectorIsLeafYomi = SuccinctBitVector(emoticon.yomi.isLeaf),
            emoticonSuccinctBitVectorTokenArray = SuccinctBitVector(emoticon.token.bitvector),
            emoticonSuccinctBitVectorTangoLBS = SuccinctBitVector(emoticon.tango.LBS),

            symbolTangoTrie = symbol.tango,
            symbolYomiTrie = symbol.yomi,
            symbolTokenArray = symbol.token,
            symbolSuccinctBitVectorLBSYomi = SuccinctBitVector(symbol.yomi.LBS),
            symbolSuccinctBitVectorIsLeafYomi = SuccinctBitVector(symbol.yomi.isLeaf),
            symbolSuccinctBitVectorTokenArray = SuccinctBitVector(symbol.token.bitvector),
            symbolSuccinctBitVectorTangoLBS = SuccinctBitVector(symbol.tango.LBS),

            readingCorrectionTangoTrie = readingCorrection.tango,
            readingCorrectionYomiTrie = readingCorrection.yomi,
            readingCorrectionTokenArray = readingCorrection.token,
            readingCorrectionSuccinctBitVectorLBSYomi = SuccinctBitVector(readingCorrection.yomi.LBS),
            readingCorrectionSuccinctBitVectorIsLeafYomi = SuccinctBitVector(readingCorrection.yomi.isLeaf),
            readingCorrectionSuccinctBitVectorTokenArray = SuccinctBitVector(readingCorrection.token.bitvector),
            readingCorrectionSuccinctBitVectorTangoLBS = SuccinctBitVector(readingCorrection.tango.LBS),

            kotowazaTangoTrie = kotowaza.tango,
            kotowazaYomiTrie = kotowaza.yomi,
            kotowazaTokenArray = kotowaza.token,
            kotowazaSuccinctBitVectorLBSYomi = SuccinctBitVector(kotowaza.yomi.LBS),
            kotowazaSuccinctBitVectorIsLeafYomi = SuccinctBitVector(kotowaza.yomi.isLeaf),
            kotowazaSuccinctBitVectorTokenArray = SuccinctBitVector(kotowaza.token.bitvector),
            kotowazaSuccinctBitVectorTangoLBS = SuccinctBitVector(kotowaza.tango.LBS),

            engineEngine = englishEngine,
        )
        return kanaKanjiEngine
    }

    private data class DictSet(val tango: LOUDS, val yomi: LOUDSWithTermId, val token: TokenArray)

    private fun loadDictionarySet(
        context: Context,
        dir: String,
        tangoFile: String,
        yomiFile: String,
        tokenFile: String,
        zipped: Boolean,
    ): DictSet {
        val tango = LOUDS().let { trie ->
            readObject(context, "$dir/$tangoFile", zipped) { trie.readExternalNotCompress(it) }
            trie
        }
        val yomi = LOUDSWithTermId().let { trie ->
            readObject(context, "$dir/$yomiFile", zipped) { trie.readExternalNotCompress(it) }
            trie
        }
        val token = TokenArray().let { ta ->
            readObject(context, "$dir/$tokenFile", zipped) { ta.readExternal(it) }
            // POS テーブルはどの辞書でも必要
            context.assets.open("pos_table.dat").use { pt ->
                ObjectInputStream(BufferedInputStream(pt)).use { obj -> ta.readPOSTable(obj) }
            }
            ta
        }
        return DictSet(tango, yomi, token)
    }

    /// 汎用 ObjectInputStream リーダ
    private inline fun readObject(
        context: Context,
        assetPath: String,
        zipped: Boolean,
        block: (ObjectInputStream) -> Unit,
    ) {
        if (zipped) {
            ZipInputStream(context.assets.open(assetPath)).use { zis ->
                zis.nextEntry
                ObjectInputStream(BufferedInputStream(zis)).use(block)
            }
        } else {
            context.assets.open(assetPath).use { input ->
                ObjectInputStream(BufferedInputStream(input)).use(block)
            }
        }
    }

    private fun loadConnectionIds(context: Context): ShortArray {
        ZipInputStream(context.assets.open("connectionId.dat.zip")).use { zipStream ->
            var entry = zipStream.nextEntry
            while (entry != null) {
                if (entry.name == "connectionId.dat") {
                    BufferedInputStream(zipStream).use { input ->
                        return ConnectionIdBuilder().readShortArrayFromBytes(input)
                    }
                }
                entry = zipStream.nextEntry
            }
        }
        throw IllegalStateException("connectionId.dat not found")
    }

    private fun buildEnglishEngine(context: Context): EnglishEngine {
        val reading = EnglishLOUDSWithTermId().let {
            ZipInputStream(context.assets.open("english/reading.dat.zip")).use { zis ->
                zis.nextEntry
                ObjectInputStream(BufferedInputStream(zis)).use { obj -> it.readExternalNotCompress(obj) }
            }
            it
        }
        val word = EnglishLOUDS().let {
            ObjectInputStream(BufferedInputStream(context.assets.open("english/word.dat"))).use { obj ->
                it.readExternalNotCompress(obj)
            }
            it
        }
        val token = EnglishTokenArray().let {
            ZipInputStream(context.assets.open("english/token.dat.zip")).use { zis ->
                zis.nextEntry
                ObjectInputStream(BufferedInputStream(zis)).use { obj -> it.readExternal(obj) }
            }
            it
        }
        val engine = EnglishEngine()
        engine.buildEngine(
            englishReadingLOUDS = reading,
            englishWordLOUDS = word,
            englishTokenArray = token,
            englishSuccinctBitVectorLBSReading = SuccinctBitVector(reading.LBS),
            englishSuccinctBitVectorLBSWord = SuccinctBitVector(word.LBS),
            englishSuccinctBitVectorTokenArray = SuccinctBitVector(token.bitvector),
            englishSuccinctBitVectorReadingIsLeaf = SuccinctBitVector(reading.isLeaf),
        )
        return engine
    }
}

/// スタックトレースから最初のアプリ/vendor コードのフレームを抜き出す（診断用）
private fun Throwable.firstAppFrame(): String {
    val st = stackTrace
    for (e in st) {
        val cn = e.className
        if (cn.startsWith("com.gime.") || cn.startsWith("com.kazumaproject.")) {
            // com.kazumaproject のうちエンジン本体を優先
            val short = cn.substringAfterLast('.')
            return "$short.${e.methodName}:${e.lineNumber}"
        }
    }
    val first = st.firstOrNull() ?: return "?"
    return "${first.className.substringAfterLast('.')}.${first.methodName}:${first.lineNumber}"
}


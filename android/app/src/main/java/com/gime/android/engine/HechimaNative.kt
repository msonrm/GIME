package com.gime.android.engine

import android.content.res.AssetManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/// libhechima.so（Mozc の変換エンジン）への JNI 束ね。
///
/// 実体は labo の `hechima-wasm/hechima_wasm.cc` —— **hechima（web / Obsidian）が使っている
/// ラッパーと同じ 1 本**で、違うのはツールチェインだけ。web の wasm と Android の .so で
/// 変換の中身が食い違わないのが利点（実装が 2 本あると必ず片方だけ直る）。
///
/// ★**ラッパーは結果を static バッファで返す**（JSON も直近の Segments も 1 つだけ）。
/// wasm は単スレッド前提なのでそれで足りているが、Android では変換・学習・辞書編集が
/// 別々のコルーチンから飛んでくる。**この object が唯一の入口で、全部を直列化する**。
/// external を直接呼ばないこと。
///
/// ビルドは labo の `.github/workflows/mozc-android.yml`。
/// `libhechima.so` と `mozc.data` は git に入れず、`android/scripts/fetch-hechima-native.sh`
/// が Release から取ってくる（成果物をリポジトリにコミットしない方針は hechima-wasm と共通）。
object HechimaNative {

    /// .so がロードできたか。
    @Volatile
    var available: Boolean = false
        private set

    /// ロードに失敗した理由（logcat が取れない環境での診断用）。
    @Volatile
    var loadError: String? = null
        private set

    /// 全ネイティブ呼び出しの直列化。上のコメントを参照。
    private val lock = Mutex()

    init {
        try {
            System.loadLibrary("hechima")
            available = true
        } catch (t: Throwable) {
            loadError = "${t::class.simpleName}: ${t.message ?: "?"}"
        }
    }

    // MARK: - 初期化（並行呼び出しが始まる前に 1 回だけ。ロック不要）

    /// 学習・設定・ユーザー辞書の置き場。**init より前に呼ぶこと**。
    external fun nativeSetProfile(dir: String): Int

    /// 辞書を APK の assets から**コピーせずに** mmap して読む（noCompress 前提）。
    external fun nativeInitFromAsset(assets: AssetManager, name: String): Int

    /// 辞書をファイルから読む（デバッグ用）。
    external fun nativeInitFromFile(path: String): Int

    // MARK: - native（直接呼ばない。下の suspend ラッパー経由で使う）

    private external fun nativeConvert(kana: String, maxCands: Int): String
    private external fun nativeConvert2(kana: String, sizesCsv: String, maxCands: Int): String
    private external fun nativeReconvert(surface: String, maxCands: Int): String
    private external fun nativeLearn(kana: String, sizesCsv: String, valuesTsv: String): Int
    private external fun nativeRevert(): Int
    private external fun nativeSync(): Int
    private external fun nativeClearHistory(): Int
    private external fun nativeDictList(): String
    private external fun nativeDictAdd(reading: String, word: String, pos: Int): Int
    private external fun nativeDictRemove(index: Int): Int

    // MARK: - 公開 API（全部 suspend + 直列化）

    /// かな → 文節分割 + 候補。
    suspend fun convert(kana: String, maxCands: Int): List<Segment> =
        lock.withLock { parseSegments(nativeConvert(kana, maxCands)) }

    /// 文節境界を指定した変換。sizesCsv = 各文節のよみ文字数をカンマ区切り。
    /// 制約が満たせないときは空リスト（呼び元は現状維持）。
    suspend fun convertConstrained(kana: String, sizesCsv: String, maxCands: Int): List<Segment> =
        lock.withLock { parseSegments(nativeConvert2(kana, sizesCsv, maxCands)) }

    /// 確定済み表記 → よみを逆算して再変換。
    suspend fun reconvert(surface: String, maxCands: Int): List<Segment> =
        lock.withLock { parseSegments(nativeReconvert(surface, maxCands)) }

    /// 確定内容の学習（+ ファイルへの flush）。valuesTsv = 各文節の確定表示値をタブ区切り。
    /// 変換を再現して**表示値の一致**で確定するので all-or-nothing（誤学習しない）。
    suspend fun learn(kana: String, sizesCsv: String, valuesTsv: String): Int =
        lock.withLock {
            val rc = nativeLearn(kana, sizesCsv, valuesTsv)
            if (rc == 0) nativeSync()
            rc
        }

    /// 直近の learn の取り消し。
    suspend fun revert(): Int = lock.withLock { nativeRevert() }

    /// 学習を全部消す。
    ///
    /// ★**ファイル（segment.db 等）を消すだけでは効かない**。エンジンが在メモリの状態を
    /// 持っていて次の Sync で書き戻すため、必ずこの API を通す。
    suspend fun clearHistory(): Boolean = lock.withLock {
        try {
            nativeClearHistory() == 0
        } catch (t: UnsatisfiedLinkError) {
            // 古い .so（clear_history が入る前）を掴んでいる場合
            android.util.Log.w("HechimaNative", "clearHistory: .so が古い", t)
            false
        }
    }

    // MARK: - ユーザー辞書

    /// ユーザー辞書の 1 語。[index] は [dictList] が返した並び順の位置で、[dictRemove] に渡す。
    data class DictEntry(val reading: String, val word: String, val pos: Int, val index: Int)

    suspend fun dictList(): List<DictEntry> = lock.withLock {
        val json = nativeDictList()
        if (json.isEmpty()) return@withLock emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("entries") ?: return@withLock emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        DictEntry(
                            reading = o.optString("reading"),
                            word = o.optString("word"),
                            pos = o.optInt("pos"),
                            index = i,
                        ),
                    )
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("HechimaNative", "dictList の JSON が読めない", t)
            emptyList()
        }
    }

    /// よみは Mozc 純正の正規化・検証を通る（かな + 英数字は可、漢字等は拒否）。
    suspend fun dictAdd(reading: String, word: String, pos: Int): Boolean =
        lock.withLock { nativeDictAdd(reading, word, pos) == 0 }

    suspend fun dictRemove(index: Int): Boolean =
        lock.withLock { nativeDictRemove(index) == 0 }

    // MARK: - JSON の読み解き

    /// 1 文節ぶんの変換結果。
    data class Segment(
        val key: String,
        val candidates: List<String>,
        val costs: List<Int>,
    )

    /// `{"segments":[{"key":..,"candidates":[..],"costs":[..]},..]}` を読む。
    /// 空文字・壊れた JSON は空リスト（呼び元は現状維持）。
    private fun parseSegments(json: String): List<Segment> {
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONObject(json).optJSONArray("segments") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = o.optString("key")
                    val cands = o.optJSONArray("candidates")
                    val costs = o.optJSONArray("costs")
                    val cs = buildList {
                        for (j in 0 until (cands?.length() ?: 0)) {
                            val s = cands?.optString(j) ?: continue
                            if (s.isNotEmpty()) add(s)
                        }
                    }
                    val ks = buildList {
                        for (j in 0 until (costs?.length() ?: 0)) add(costs?.optInt(j) ?: 0)
                    }
                    if (key.isNotEmpty()) add(Segment(key, cs, ks))
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("HechimaNative", "convert の JSON が読めない", t)
            emptyList()
        }
    }
}

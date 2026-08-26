package com.gime.android.engine

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/// 日本語かな漢字変換エンジンのファサード。
///
/// 実体は自前ビルドの **Mozc**（`libhechima.so` + `mozc.data`）。
/// hechima（web / Obsidian）とラッパーが同じ 1 本なので、変換の中身が
/// プラットフォーム間で食い違わない。
///
/// この層は状態機械を持たない —— 文節と候補を返すだけで、
/// 何を選んでいるか・いつ確定するかは `GamepadInputManager` 側が持つ。
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
    private var ready: Boolean = false

    private val initLock = Mutex()

    val isReady: Boolean get() = ready

    /// 直近のエラー（UI 表示用）。logcat が取れない環境での診断用。
    var lastError: String? by mutableStateOf(null)
        private set

    private companion object {
        const val MOZC_DATA_ASSET = "mozc.data"

        /// 学習・設定・ユーザー辞書の置き場（filesDir からの相対）。
        const val PROFILE_DIR = "hechima"
    }

    /// エンジンを非同期で初期化する。
    ///
    /// [onReady] は初期化に成功した直後（まだ IO ディスパッチャ上）で一度だけ呼ばれる。
    /// ユーザー辞書の移行など、**エンジンが立っていないと実行できない後始末**に使う。
    fun initializeAsync(
        context: Context,
        scope: CoroutineScope,
        onReady: (suspend () -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            val justReady = initLock.withLock {
                if (ready) return@withLock false
                if (initializeMozc(appContext)) {
                    ready = true
                    android.util.Log.i("JapaneseConverter", "mozc engine loaded")
                    true
                } else {
                    android.util.Log.e("JapaneseConverter", "mozc init failed: $lastError")
                    false
                }
            }
            if (justReady) onReady?.invoke()
        }
    }

    /// Mozc を初期化する。成功したら true。失敗理由は [lastError] に残す。
    private suspend fun initializeMozc(context: Context): Boolean {
        if (!HechimaNative.available) {
            lastError = "libhechima.so をロードできない: ${HechimaNative.loadError ?: "?"}"
            return false
        }
        // ★Mozc は Android で user profile の既定値を持たない（上流が「Java 層から注入」
        //   する前提で空文字を返す）ので、init より前に必ず入れる。
        val profile = File(context.filesDir, PROFILE_DIR)
        if (!profile.exists() && !profile.mkdirs()) {
            lastError = "profile ディレクトリを作れない: ${profile.absolutePath}"
            return false
        }
        val rcProfile = HechimaNative.nativeSetProfile(profile.absolutePath)
        if (rcProfile != 0) {
            lastError = "mozc setProfile rc=$rcProfile"
            return false
        }
        // 辞書は APK の assets から**コピーせず** mmap したまま読む（noCompress 前提）
        val rc = HechimaNative.nativeInitFromAsset(context.assets, MOZC_DATA_ASSET)
        if (rc != 0) {
            lastError = "mozc init rc=$rc (assets/$MOZC_DATA_ASSET)"
            return false
        }
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            smokeTest()
        }
        return true
    }

    /// デバッグビルドだけの煙テスト。
    ///
    /// ★「エンジンが load できた」を緑にしない。JNI の文字列往復と JSON の読み解きは
    /// **実際に 1 回変換してみるまで検証されない**（ゲームパッドが要るので通常の操作では
    /// ここまで到達しない）。文節が割れたか・第1候補がよみのエコーでないかまで見る。
    private suspend fun smokeTest() {
        val kana = "きょうはいいてんきですね"
        val segs = HechimaNative.convert(kana, 5)
        if (segs.isEmpty()) {
            android.util.Log.e("JapaneseConverter", "smoke: 変換結果が空")
            return
        }
        val top = segs.first().candidates.firstOrNull()
        val ok = segs.size >= 2 && top != null && top != segs.first().key
        val preview = segs.joinToString("|") { it.candidates.firstOrNull() ?: it.key }
        android.util.Log.i(
            "JapaneseConverter",
            "smoke: ${if (ok) "PASS" else "FAIL"} $kana -> $preview (${segs.size} 文節)",
        )
    }

    // MARK: - 変換

    /// 候補列に「ひらがなそのまま」を必ず含める。
    private fun withKanaFallback(seg: HechimaNative.Segment): List<Candidate> {
        val list = seg.candidates.mapIndexed { i, surface ->
            Candidate(surface = surface, reading = seg.key, cost = seg.costs.getOrElse(i) { 0 })
        }
        return if (list.none { it.surface == seg.key }) {
            list + Candidate(seg.key, seg.key, Int.MAX_VALUE / 2)
        } else {
            list
        }
    }

    /// ひらがな読みを変換して候補リストを返す（n-best）。全体を 1 文節に固定して取る。
    suspend fun convert(reading: String, maxCandidates: Int = 30): List<Candidate> {
        if (!ready || reading.isEmpty()) return emptyList()
        val n = reading.codePointCount(0, reading.length)
        val seg = HechimaNative.convertConstrained(reading, n.toString(), maxCandidates)
            .firstOrNull()
            ?: return listOf(Candidate(reading, reading, 0))
        return withKanaFallback(seg)
    }

    /// 文節分割された変換結果を返す。
    suspend fun convertBunsetsu(reading: String, nPerBunsetsu: Int = 20): BunsetsuResult? {
        if (!ready || reading.isEmpty()) return null
        val segs = HechimaNative.convert(reading, nPerBunsetsu)
        if (segs.isEmpty()) return null
        return BunsetsuResult(
            readings = segs.map { it.key },
            candidates = segs.map { withKanaFallback(it) },
        )
    }

    // MARK: - 学習

    /// 確定した文節列をまるごと学習する。
    ///
    /// ★**文全体を 1 回で渡すのが正しい**。Mozc は変換を再現して各文節を表示値の一致で
    /// 確定する（= Mozc 自身の候補選択学習 + 文節境界学習）ので、1 ペアずつ渡すと
    /// 文節境界が学習されない。all-or-nothing なので誤学習もしない。
    suspend fun recordLearning(readings: List<String>, surfaces: List<String>) {
        if (!ready || readings.isEmpty() || readings.size != surfaces.size) return
        val kana = readings.joinToString("")
        val sizes = readings.joinToString(",") { it.codePointCount(0, it.length).toString() }
        val values = surfaces.joinToString("\t")
        val rc = HechimaNative.learn(kana, sizes, values)
        if (rc != 0) {
            android.util.Log.w("JapaneseConverter", "mozc learn rc=$rc for '$kana'")
        }
    }

    /// 学習を全部消す。
    suspend fun clearLearning(): Boolean = ready && HechimaNative.clearHistory()
}

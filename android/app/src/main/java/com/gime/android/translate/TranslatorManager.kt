package com.gime.android.translate

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit On-Device Translation のラッパー。
 *
 * - 翻訳元は固定で日本語（GIME の入力テキストはひらがな or 日本語）。
 * - ターゲット言語ごとに `Translator` インスタンスをキャッシュする。
 *   ML Kit は同一 (src,dst) ペアで何度生成しても内部で共有するが、
 *   このクラス側でも 1 つだけ持つことで close 漏れを避ける。
 * - 初回呼び出し時にモデル（~30MB / ペア）を on-demand ダウンロードする。
 *   ダウンロード未完了でも `translate()` は内部で待機してから翻訳するため、
 *   外側からは「ちょっと遅い 1 回目」「2 回目以降は数百 ms」に見える。
 *
 * VRChat OSC 用なので、失敗時は例外を投げず `null` を返して呼び出し側に
 * 「翻訳しない」フォールバックを取らせる方針。
 */
class TranslatorManager {

    private var current: Translator? = null
    private var currentTargetCode: String? = null
    private var modelReady: Boolean = false

    /// ターゲット言語コード（ISO 639-1 系。`"en"`, `"ko"`, `"zh"` 等）を切替える。
    /// 同じコードなら no-op。違うコードに切り替えると古い Translator を close する。
    @Synchronized
    fun setTarget(targetCode: String?) {
        if (targetCode == currentTargetCode) return
        try { current?.close() } catch (_: Throwable) {}
        current = null
        modelReady = false
        currentTargetCode = targetCode
        if (targetCode == null) return

        val mlKitTarget = mlKitLanguageCode(targetCode) ?: run {
            Log.w(TAG, "Unsupported target language: $targetCode")
            currentTargetCode = null
            return
        }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.JAPANESE)
            .setTargetLanguage(mlKitTarget)
            .build()
        current = Translation.getClient(options)
    }

    /// モデルダウンロードを明示トリガー。設定画面の「事前ダウンロード」ボタン用。
    /// `wifiOnly = true` なら NetworkType.WIFI 制約付き。
    suspend fun ensureModelDownloaded(wifiOnly: Boolean): Boolean {
        val translator = current ?: return false
        return runCatching { downloadModelIfNeeded(translator, wifiOnly) }
            .onFailure { Log.w(TAG, "model download failed", it) }
            .getOrDefault(false)
    }

    /// 翻訳実行。失敗時は `null`。空文字なら空文字を返す（モデル DL を起こさない）。
    suspend fun translate(text: String, wifiOnly: Boolean): String? {
        if (text.isBlank()) return text
        val translator = current ?: return null
        return try {
            if (!modelReady) {
                if (!downloadModelIfNeeded(translator, wifiOnly)) return null
            }
            awaitTask(translator.translate(text))
        } catch (t: Throwable) {
            Log.w(TAG, "translate failed", t)
            null
        }
    }

    fun close() {
        try { current?.close() } catch (_: Throwable) {}
        current = null
        currentTargetCode = null
        modelReady = false
    }

    private suspend fun downloadModelIfNeeded(translator: Translator, wifiOnly: Boolean): Boolean {
        val conditions = DownloadConditions.Builder().apply {
            if (wifiOnly) requireWifi()
        }.build()
        return try {
            awaitTask(translator.downloadModelIfNeeded(conditions))
            modelReady = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "downloadModelIfNeeded failed (wifiOnly=$wifiOnly)", t)
            false
        }
    }

    private suspend fun <T> awaitTask(task: Task<T>): T = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { result -> cont.resume(result) }
        task.addOnFailureListener { e -> cont.resumeWithException(e) }
        task.addOnCanceledListener { cont.cancel() }
    }

    /// 設定画面で扱う raw コードを ML Kit 定数にマップ。
    /// ML Kit の中国語は `zh` だが、`TranslateLanguage` 定数は `CHINESE` を持つ。
    private fun mlKitLanguageCode(raw: String): String? = when (raw) {
        "en" -> TranslateLanguage.ENGLISH
        "ko" -> TranslateLanguage.KOREAN
        "zh" -> TranslateLanguage.CHINESE
        else -> null
    }

    companion object {
        private const val TAG = "TranslatorManager"
    }
}

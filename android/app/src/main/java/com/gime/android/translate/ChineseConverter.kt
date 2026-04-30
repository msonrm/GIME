package com.gime.android.translate

import android.util.Log
import com.github.houbb.opencc4j.util.ZhConverterUtil

/**
 * ML Kit Translate の中文出力（簡体）を後処理して繁體（台湾風）に変換するヘルパー。
 *
 * OpenCC4j (`com.github.houbb:opencc4j`) は純 JVM 実装で JNI 不要、Apache 2.0。
 * `ZhConverterUtil.toTraditional()` は単純な s2t（文字単位の簡→繁）に加えて、
 * フレーズ辞書（`STPhrases`）も適用するので「计算机 → 計算機」だけでなく
 * 「软件 → 軟體」レベルの語彙置換が走る（完全な s2twp ではないが台湾風に近い）。
 *
 * 失敗時は元の文字列をそのまま返す（無音フォールバック）。
 */
object ChineseConverter {

    private const val TAG = "ChineseConverter"

    /// 簡体中文 → 繁體中文（台湾風）。失敗時は入力をそのまま返す。
    fun simplifiedToTraditionalTaiwan(text: String): String {
        if (text.isEmpty()) return text
        return try {
            ZhConverterUtil.toTraditional(text)
        } catch (t: Throwable) {
            Log.w(TAG, "toTraditional failed", t)
            text
        }
    }
}

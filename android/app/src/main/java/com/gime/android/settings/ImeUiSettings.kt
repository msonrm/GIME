package com.gime.android.settings

import android.content.Context

/**
 * IME 本体 UI の設定（compact モード等）。
 *
 * BubbleSettings と同じ作法の SharedPreferences ラッパー。
 * キーは bubble_ui とは別ファイルにしてバブル/IME を独立に切り替え可能に。
 */
class ImeUiSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /// compact モード: D-pad ビジュアライザを隠し、候補オーバーレイ + タイトルバー
    /// だけで運用する。候補は透過オーバーレイとして IME window 外（contentTopInsets
    /// の上）に描画されるので、アプリの入力欄は押し上げられない。
    var compactMode: Boolean
        get() = prefs.getBoolean(KEY_COMPACT, DEFAULT_COMPACT)
        set(v) { prefs.edit().putBoolean(KEY_COMPACT, v).apply() }

    companion object {
        private const val PREFS_NAME = "ime_ui"
        private const val KEY_COMPACT = "compactMode"
        const val DEFAULT_COMPACT = false
    }
}

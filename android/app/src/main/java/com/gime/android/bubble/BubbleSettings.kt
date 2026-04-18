package com.gime.android.bubble

import android.content.Context

/**
 * バブル表示 UI 固有の設定（OSC とは別系統）。
 */
class BubbleSettings(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /// compact モード: ビジュアライザの D-pad / 配列情報を隠し、
    /// プレビュー + composing 中の候補行だけを表示する。
    /// 慣れたユーザー向け。永続化して次回起動時も維持する。
    var compactMode: Boolean
        get() = prefs.getBoolean(KEY_COMPACT, DEFAULT_COMPACT)
        set(v) { prefs.edit().putBoolean(KEY_COMPACT, v).apply() }

    companion object {
        private const val PREFS_NAME = "bubble_ui"
        private const val KEY_COMPACT = "compactMode"
        const val DEFAULT_COMPACT = false
    }
}

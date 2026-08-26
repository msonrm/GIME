package com.gime.android.engine

import android.content.Context
import com.gime.android.learn.DatabaseProvider

/// Mozc のユーザー辞書。実体は `libhechima.so` の `user_dictionary.db`。
///
/// 一覧・追加・削除は [HechimaNative] の dict API をそのまま使う（直列化もあちら側）。
/// この object が持っているのは **品詞の選択肢**と、**旧 Room からの一度きりの移行**だけ。
object MozcUserDictionary {

    /// UI に並べる品詞。値は Mozc の `UserDictionary.PosType`
    /// （`protocol/user_dictionary_storage.proto`）。
    val POS_CHOICES: List<Pair<String, Int>> = listOf(
        "名詞" to 1,
        "短縮よみ" to 2,
        "固有名詞" to 4,
        "人名" to 5,
        "姓" to 6,
        "名" to 7,
        "組織" to 8,
        "地名" to 9,
        "名詞サ変" to 10,
        "数" to 12,
        "アルファベット" to 13,
        "記号" to 14,
        "顔文字" to 15,
        "副詞" to 16,
        "連体詞" to 17,
        "接続詞" to 18,
        "感動詞" to 19,
        "接頭語" to 20,
    )

    fun posLabel(pos: Int): String =
        POS_CHOICES.firstOrNull { it.second == pos }?.first ?: "名詞"

    private const val PREFS = "hechima_migration"
    private const val KEY_DONE = "user_dict_migrated"

    /// 旧エンジン（KazumaProject）時代に Room へ登録されたユーザー辞書を、
    /// **起動時に一度だけ** Mozc 側へ流し込む。
    ///
    /// 移行後は Room 側を空にする（二重管理を残さない）。
    /// 失敗した語は飛ばして続ける —— Mozc はよみを純正の規則で検証するので、
    /// 旧実装で通っていた語が弾かれることがある（漢字を含むよみなど）。
    ///
    /// ★品詞は素直には移らない。旧実装の `posIndex` は KazumaProject 独自の並びで、
    /// Mozc の PosType とは体系が違う。対応が明らかなものだけ写し、
    /// 残りは名詞に倒す（ユーザー辞書の語はほとんど名詞なので実害は小さい）。
    suspend fun migrateFromLegacyRoom(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return 0

        val moved = try {
            val db = DatabaseProvider.get(context)
            val words = db.userWordDao().all()
            var n = 0
            for (w in words) {
                if (HechimaNative.dictAdd(w.reading, w.word, legacyPosToMozc(w.posIndex))) {
                    n++
                } else {
                    android.util.Log.w(
                        "MozcUserDictionary",
                        "移行できなかった語: ${w.reading} → ${w.word}",
                    )
                }
            }
            if (words.isNotEmpty()) {
                db.userWordDao().deleteAll()
                db.learnDao().deleteAll()
            }
            n
        } catch (t: Throwable) {
            android.util.Log.w("MozcUserDictionary", "ユーザー辞書の移行に失敗", t)
            return 0
        }

        prefs.edit().putBoolean(KEY_DONE, true).apply()
        if (moved > 0) {
            android.util.Log.i("MozcUserDictionary", "旧ユーザー辞書から $moved 語を移行した")
        }
        return moved
    }

    /// 旧 `posIndex`（名詞/動詞/形容詞/副詞/助動詞/助詞/感動詞/接続詞/接頭詞/記号/連体詞/その他）
    /// → Mozc の PosType。対応が明らかなものだけ。
    private fun legacyPosToMozc(posIndex: Int): Int = when (posIndex) {
        3 -> 16   // 副詞   → ADVERB
        6 -> 19   // 感動詞 → INTERJECTION
        7 -> 18   // 接続詞 → CONJUNCTION
        8 -> 20   // 接頭詞 → PREFIX
        9 -> 14   // 記号   → SYMBOL
        10 -> 17  // 連体詞 → PRENOUN_ADJECTIVAL
        else -> 1 // 名詞（動詞・形容詞等は Mozc 側が活用型まで要求するので倒す）
    }
}

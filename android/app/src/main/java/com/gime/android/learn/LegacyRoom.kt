package com.gime.android.learn

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query

/// 旧エンジン（KazumaProject）時代の Room 定義。
///
/// **移行のためだけに残している。** 変換も学習も Mozc 側（`libhechima.so` の
/// segment.db / user_dictionary.db）に移ったので、ここに書き込むコードはもう無い。
/// 起動時に一度だけ `user_word` を Mozc のユーザー辞書へ流し込む
/// （`MozcUserDictionary.migrateFromLegacyRoom`）ためだけに読む。
///
/// ★スキーマは version 1 のまま**一字も変えない**。`GimeDatabase` は
/// `fallbackToDestructiveMigration()` なので、テーブル定義を触ると
/// **移行する前にユーザーの登録語を消してしまう**。
/// 移行が行き渡ったら、この 1 ファイルと Room 依存ごと削除する。

@Entity(
    tableName = "learn",
    indices = [Index(value = ["input"]), Index(value = ["input", "out"], unique = true)],
)
data class LearnEntity(
    val input: String,
    val out: String,
    val leftId: Short? = null,
    val rightId: Short? = null,
    val score: Short = 0,
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = 0L,
)

@Dao
interface LearnDao {
    @Query("DELETE FROM learn")
    suspend fun deleteAll()
}

@Entity(
    tableName = "user_word",
    indices = [Index(value = ["reading"]), Index(value = ["reading", "word"], unique = true)],
)
data class UserWord(
    val reading: String,
    val word: String,
    val posIndex: Int,
    val posScore: Int,
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)

@Dao
interface UserWordDao {
    @Query("SELECT * FROM user_word ORDER BY created_at DESC")
    suspend fun all(): List<UserWord>

    @Query("DELETE FROM user_word")
    suspend fun deleteAll()
}

package com.kazumaproject.markdownhelperkeyboard.repository

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query

/// ユーザー辞書エントリ。
///
/// Room の Entity としても使う。GraphBuilder は `reading` / `word` /
/// `posIndex` / `posScore` の 4 フィールドを読むので、この 4 つの
/// プロパティ名は変更しない。
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

/// UserWord への DAO。
@Dao
interface UserWordDao {
    /// 読みがクエリ文字列の接頭辞に一致するエントリを返す。
    /// エンジン側は `reading.length` 分を切り取って Node を生成するので、
    /// 単一の LIKE クエリで共通接頭辞検索を実現する。
    @Query(
        "SELECT * FROM user_word " +
            "WHERE :query LIKE reading || '%' " +
            "ORDER BY LENGTH(reading) DESC " +
            "LIMIT 64"
    )
    suspend fun commonPrefix(query: String): List<UserWord>

    @Query("SELECT * FROM user_word ORDER BY created_at DESC")
    suspend fun all(): List<UserWord>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserWord): Long

    @androidx.room.Delete
    suspend fun delete(entity: UserWord)

    @Query("DELETE FROM user_word")
    suspend fun deleteAll()
}

/// ユーザー辞書リポジトリ（Room 実装）。
///
/// Phase A0 では `commonPrefixSearchInUserDict` が常に空リストを返すスタブ
/// だったが、Phase A4a で Room 実装に差し替え。
/// KazumaProject 変換エンジン (`GraphBuilder`) が参照するクラス名・メソッド
/// シグネチャは維持する。
class UserDictionaryRepository(private val dao: UserWordDao? = null) {
    /// 指定された読み接頭辞に一致するユーザー登録語を返す。
    /// DAO が未注入の場合は空リスト（テスト / 初期化中のフェイルセーフ）。
    suspend fun commonPrefixSearchInUserDict(prefix: String): List<UserWord> {
        if (prefix.isEmpty()) return emptyList()
        val d = dao ?: return emptyList()
        return d.commonPrefix(prefix)
    }

    suspend fun all(): List<UserWord> = dao?.all() ?: emptyList()

    suspend fun upsert(reading: String, word: String, posIndex: Int, posScore: Int, id: Long = 0L): Long {
        val d = dao ?: return 0L
        return d.upsert(
            UserWord(
                reading = reading,
                word = word,
                posIndex = posIndex,
                posScore = posScore,
                id = id,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun delete(entity: UserWord) {
        dao?.delete(entity)
    }

    suspend fun deleteAll() {
        dao?.deleteAll()
    }
}

package com.kazumaproject.markdownhelperkeyboard.repository

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query

/// 学習データの 1 エントリ。Room の Entity としても使う。
/// GraphBuilder は `input` / `out` / `leftId` / `rightId` / `score` の 5
/// フィールドを読むので、プロパティ名と型（Short を含む）は維持する。
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

/// LearnEntity への DAO。
@Dao
interface LearnDao {
    /// 読みがクエリ文字列の接頭辞に一致するエントリを返す。
    /// エンジン側は `input.length` を切り取って Node を生成する。
    @Query(
        "SELECT * FROM learn " +
            "WHERE :query LIKE input || '%' " +
            "ORDER BY LENGTH(input) DESC, score ASC " +
            "LIMIT 64"
    )
    suspend fun findCommonPrefixes(query: String): List<LearnEntity>

    /// (input, out) がユニーク制約なので、存在すれば置換、無ければ新規追加。
    /// スコアは呼び出し側で減衰計算済みの値を渡す。
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LearnEntity): Long

    @Query("SELECT * FROM learn WHERE input = :input AND out = :out LIMIT 1")
    suspend fun findExact(input: String, out: String): LearnEntity?

    @Query("DELETE FROM learn")
    suspend fun deleteAll()
}

/// 学習リポジトリ（Room 実装）。
///
/// Phase A0 では常に空リストを返すスタブだったが、Phase A4a で Room
/// 実装に差し替え。KazumaProject 変換エンジン (`GraphBuilder`) が参照する
/// `findCommonPrefixes(searchTerm)` シグネチャは維持する。
class LearnRepository(private val dao: LearnDao? = null) {

    /// 入力の共通接頭辞に一致する学習データを返す。
    /// DAO が未注入の場合は空リスト（学習 OFF / 初期化中のフェイルセーフ）。
    suspend fun findCommonPrefixes(searchTerm: String): List<LearnEntity> {
        if (searchTerm.isEmpty()) return emptyList()
        val d = dao ?: return emptyList()
        return d.findCommonPrefixes(searchTerm)
    }

    /// 読み→確定語ペアを記録する。既存エントリはスコアを減衰させて優遇。
    suspend fun record(reading: String, surface: String) {
        val d = dao ?: return
        if (reading.isBlank() || surface.isBlank()) return
        // ひらがなそのままは学習しない（変換しなかったケース）
        if (reading == surface) return

        val existing = d.findExact(reading, surface)
        val newScore: Short = if (existing != null) {
            val decayed = (existing.score - SCORE_DECAY_STEP).coerceAtLeast(SCORE_FLOOR)
            decayed.toShort()
        } else {
            SCORE_INITIAL.toShort()
        }

        d.upsert(
            LearnEntity(
                input = reading,
                out = surface,
                score = newScore,
                id = existing?.id ?: 0L,
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteAll() {
        dao?.deleteAll()
    }

    companion object {
        /// 初回確定時のスコア。システム辞書の通常候補（4000 付近）より
        /// 優位に立ちつつ、確定回数で徐々に強化される余地を残す。
        const val SCORE_INITIAL: Int = 3000
        /// 同一エントリを再確定するごとにスコアを引く（=優遇度を上げる）。
        const val SCORE_DECAY_STEP: Int = 50
        /// スコア下限（これ以上は優遇しない）。
        const val SCORE_FLOOR: Int = 500
    }
}

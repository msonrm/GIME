package com.gime.android.learn

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.kazumaproject.markdownhelperkeyboard.repository.LearnDao
import com.kazumaproject.markdownhelperkeyboard.repository.LearnEntity
import com.kazumaproject.markdownhelperkeyboard.repository.UserWord
import com.kazumaproject.markdownhelperkeyboard.repository.UserWordDao

/// GIME Android のローカル DB。
/// - `learn`: 変換確定時の読み→surface 学習
/// - `user_word`: ユーザー登録辞書
///
/// version=1 のみ。将来マイグレーションが必要になったら `Migration` を追加する。
@Database(
    entities = [LearnEntity::class, UserWord::class],
    version = 1,
    exportSchema = true,
)
abstract class GimeDatabase : RoomDatabase() {
    abstract fun learnDao(): LearnDao
    abstract fun userWordDao(): UserWordDao
}

/// GimeDatabase の手動シングルトン（Hilt を入れないための簡便実装）。
object DatabaseProvider {
    @Volatile
    private var instance: GimeDatabase? = null

    fun get(context: Context): GimeDatabase {
        return instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): GimeDatabase {
        return Room.databaseBuilder(
            appContext,
            GimeDatabase::class.java,
            "gime.db",
        )
            // 学習データを壊すより起動優先。Phase A4 時点では許容。
            .fallbackToDestructiveMigration()
            .build()
    }
}

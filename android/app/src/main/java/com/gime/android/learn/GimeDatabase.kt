package com.gime.android.learn

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/// GIME Android のローカル DB —— **旧エンジン時代の遺物で、移行のためだけに残っている**。
///
/// 変換・学習・ユーザー辞書はすべて Mozc 側（`libhechima.so` の profile ディレクトリ）に
/// 移った。ここは起動時に一度だけ `user_word` を Mozc へ流し込むために読むだけで、
/// 書き込むコードはもう無い（`MozcUserDictionary.migrateFromLegacyRoom`）。
///
/// ★スキーマは version=1 のまま触らない。`fallbackToDestructiveMigration()` なので、
/// テーブル定義を変えると**移行する前にユーザーの登録語を消してしまう**。
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

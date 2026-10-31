package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TestRunEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun testRunDao(): TestRunDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE test_runs ADD COLUMN promptTokenizeDurationMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE test_runs ADD COLUMN firstTokenDurationMs INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE test_runs ADD COLUMN promptDecodeCalls INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE test_runs ADD COLUMN generationDecodeCalls INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "llmlab_database.db"
                ).addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

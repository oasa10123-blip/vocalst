package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WsCommandRuleEntity::class, VocalPresetEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wsCommandRuleDao(): WsCommandRuleDao
    abstract fun vocalPresetDao(): VocalPresetDao

    companion object {
        fun build(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "vocalstage_db"
            ).build()
        }
    }
}

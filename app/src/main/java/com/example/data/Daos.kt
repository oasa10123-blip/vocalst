package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WsCommandRuleDao {
    @Query("SELECT * FROM ws_command_rules ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<WsCommandRuleEntity>>

    @Query("SELECT COUNT(*) FROM ws_command_rules")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: WsCommandRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<WsCommandRuleEntity>)

    @Update
    suspend fun update(rule: WsCommandRuleEntity)

    @Delete
    suspend fun delete(rule: WsCommandRuleEntity)

    @Query("DELETE FROM ws_command_rules")
    suspend fun deleteAll()
}

@Dao
interface VocalPresetDao {
    @Query("SELECT * FROM vocal_presets ORDER BY slotNumber ASC, id ASC")
    fun observeAll(): Flow<List<VocalPresetEntity>>

    @Query("SELECT COUNT(*) FROM vocal_presets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: VocalPresetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(presets: List<VocalPresetEntity>)

    @Update
    suspend fun update(preset: VocalPresetEntity)

    @Delete
    suspend fun delete(preset: VocalPresetEntity)
}

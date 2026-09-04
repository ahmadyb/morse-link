package com.morselink.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Query("SELECT * FROM transfers ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE direction = :direction ORDER BY timestamp DESC")
    fun observeByDirection(direction: String): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun findById(id: Long): TransferEntity?

    @Insert
    suspend fun insert(entity: TransferEntity): Long

    @Update
    suspend fun update(entity: TransferEntity)

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transfers")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM transfers WHERE direction = 'RECEIVED'")
    fun observeReceivedCount(): Flow<Int>
}

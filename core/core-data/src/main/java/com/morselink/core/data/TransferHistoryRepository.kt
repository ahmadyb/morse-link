package com.morselink.core.data

import com.morselink.core.data.db.TransferDao
import com.morselink.core.data.db.TransferEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransferHistoryRepository @Inject constructor(
    private val dao: TransferDao,
) {
    fun observeAll(): Flow<List<TransferEntity>> = dao.observeAll()

    fun observeSent(): Flow<List<TransferEntity>> =
        dao.observeByDirection(TransferEntity.DIRECTION_SENT)

    fun observeReceived(): Flow<List<TransferEntity>> =
        dao.observeByDirection(TransferEntity.DIRECTION_RECEIVED)

    fun observeReceivedCount(): Flow<Int> = dao.observeReceivedCount()

    suspend fun record(entity: TransferEntity): Long = dao.insert(entity)

    suspend fun update(entity: TransferEntity) = dao.update(entity)

    suspend fun find(id: Long): TransferEntity? = dao.findById(id)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clear() = dao.clear()
}

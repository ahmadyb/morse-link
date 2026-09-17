package com.morselink.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [TransferEntity::class], version = 1, exportSchema = false)
abstract class MorselinkDatabase : RoomDatabase() {
    abstract fun transferDao(): TransferDao
}

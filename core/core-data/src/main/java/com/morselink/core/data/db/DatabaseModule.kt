package com.morselink.core.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MorselinkDatabase =
        Room.databaseBuilder(context, MorselinkDatabase::class.java, "morselink.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTransferDao(database: MorselinkDatabase): TransferDao = database.transferDao()
}

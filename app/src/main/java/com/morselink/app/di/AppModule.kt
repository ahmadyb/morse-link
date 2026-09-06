package com.morselink.app.di

import android.content.Context
import com.morselink.app.service.ConnectionService
import com.morselink.core.network.SessionServiceController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun bindSessionServiceController(
        impl: MorselinkSessionServiceController,
    ): SessionServiceController
}

@Singleton
class MorselinkSessionServiceController @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionServiceController {

    override fun start() = ConnectionService.start(context)

    override fun stop() = ConnectionService.stop(context)
}

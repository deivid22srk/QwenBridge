package com.deivid22srk.qwenbridge.di

import android.content.Context
import com.deivid22srk.qwenbridge.database.AccountDao
import com.deivid22srk.qwenbridge.database.LogDao
import com.deivid22srk.qwenbridge.database.QwenBridgeDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): QwenBridgeDatabase {
        return QwenBridgeDatabase.getDatabase(context)
    }

    @Provides
    fun provideAccountDao(database: QwenBridgeDatabase): AccountDao {
        return database.accountDao()
    }

    @Provides
    fun provideLogDao(database: QwenBridgeDatabase): LogDao {
        return database.logDao()
    }

    @Provides
    fun provideSessionMappingDao(database: QwenBridgeDatabase): SessionMappingDao {
        return database.sessionMappingDao()
    }
}

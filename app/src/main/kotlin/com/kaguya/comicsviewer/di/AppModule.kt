package com.kaguya.comicsviewer.di

import android.content.Context
import androidx.room.Room
import com.kaguya.comicsviewer.data.local.KaguyaDatabase
import com.kaguya.comicsviewer.data.local.dao.ComicDao
import com.kaguya.comicsviewer.data.local.dao.ComicSourceDao
import com.kaguya.comicsviewer.data.repository.ComicRepository
import com.kaguya.comicsviewer.data.repository.ComicRepositoryImpl
import dagger.Binds
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
    fun provideDatabase(@ApplicationContext context: Context): KaguyaDatabase =
        Room.databaseBuilder(context, KaguyaDatabase::class.java, "kaguya.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideComicSourceDao(db: KaguyaDatabase): ComicSourceDao = db.sourceDao()

    @Provides
    fun provideComicDao(db: KaguyaDatabase): ComicDao = db.comicDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindComicRepository(impl: ComicRepositoryImpl): ComicRepository
}

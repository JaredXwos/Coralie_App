package com.jaredxwos.coralie.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jaredxwos.coralie.storage.database.AppDao
import com.jaredxwos.coralie.storage.database.Entry
import com.jaredxwos.coralie.storage.database.Html
import com.jaredxwos.coralie.storage.database.Space

// SCHEMA
// ──────────────────────────────────────────────────────
// Spaces  ( spaceId PK AI NN, name NN )
// Html    ( spaceId PK FK→Spaces.spaceId NN, contentLink PK NN )
// Store   ( spaceId PK FK→Spaces.spaceId NN, name PK NN, value NN, tag )
// ──────────────────────────────────────────────────────
// FK cascade: deleting a Space deletes its Html and Store rows
// PK: primary key, AI: auto increment, FK: foreign key, NN: not null

@Database(
    entities = [Space::class, Html::class, Entry::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app-db"
                ).fallbackToDestructiveMigration(true).build().also { instance = it }
            }
    }
}
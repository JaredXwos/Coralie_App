package com.jaredxwos.coralie.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jaredxwos.coralie.storage.database.AppDao
import com.jaredxwos.coralie.storage.database.Entry
import com.jaredxwos.coralie.storage.database.Html
import com.jaredxwos.coralie.storage.database.Space
import com.jaredxwos.coralie.storage.database.UriEntry

@Database(
    entities = [Space::class, Html::class, Entry::class, UriEntry::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app-db",
                )
                    // Development-only policy. Because the app has not shipped,
                    // schema changes reset local test data instead of maintaining
                    // migration code for obsolete development schemas.
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
    }
}

package com.jaredxwos.coralie.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jaredxwos.coralie.data.database.dao.LibraryDao
import com.jaredxwos.coralie.data.database.dao.PageStorageDao
import com.jaredxwos.coralie.data.database.dao.PermissionDao
import com.jaredxwos.coralie.data.database.entity.AllowedDomainEntity
import com.jaredxwos.coralie.data.database.entity.HtmlPageEntity
import com.jaredxwos.coralie.data.database.entity.SpaceEntity
import com.jaredxwos.coralie.data.database.entity.SpaceEntryEntity

@Database(
    entities = [
        SpaceEntity::class,
        HtmlPageEntity::class,
        SpaceEntryEntity::class,
        AllowedDomainEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
internal abstract class CoralieDatabase : RoomDatabase() {
    internal abstract fun libraryDao(): LibraryDao

    internal abstract fun pageStorageDao(): PageStorageDao

    internal abstract fun permissionDao(): PermissionDao

    companion object {
        @Volatile
        private var instance: CoralieDatabase? = null

        fun getInstance(context: Context): CoralieDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CoralieDatabase::class.java,
                    "app-db",
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { instance = it }
            }
    }
}

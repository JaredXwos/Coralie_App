package com.jaredxwos.coralie.app

import android.content.Context
import com.jaredxwos.coralie.data.database.CoralieDatabase
import com.jaredxwos.coralie.data.library.PageCache
import com.jaredxwos.coralie.data.library.PageLibrary
import com.jaredxwos.coralie.data.library.PersistentUriStore
import com.jaredxwos.coralie.data.permission.DomainPermissionStore
import com.jaredxwos.coralie.data.space.SpaceKeyValueStoreFactory
import com.jaredxwos.coralie.feature.viewer.runtime.ViewerSessionFactory
import java.io.File

class AppContainer(
    context: Context,
) {
    private val database =
        CoralieDatabase.getInstance(context)

    private val pageCache =
        PageCache(
            cacheDirectory =
                File(context.filesDir, "html"),
            contentResolver =
                context.contentResolver,
        )

    private val persistentUriStore =
        PersistentUriStore(
            contentResolver =
                context.contentResolver,
        )

    val pageLibrary =
        PageLibrary(
            dao = database.libraryDao(),
            cache = pageCache,
            uriStore = persistentUriStore,
        )

    val domainPermissionStore =
        DomainPermissionStore(
            dao = database.permissionDao(),
        )

    val spaceKeyValueStoreFactory =
        SpaceKeyValueStoreFactory(
            dao = database.pageStorageDao(),
        )

    val viewerSessionFactory =
        ViewerSessionFactory(
            keyValueStoreFactory =
                spaceKeyValueStoreFactory,
            domainPermissionStore =
                domainPermissionStore,
            pageLibrary = pageLibrary,
        )
}

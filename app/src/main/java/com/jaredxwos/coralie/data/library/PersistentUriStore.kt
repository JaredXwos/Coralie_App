package com.jaredxwos.coralie.data.library

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

internal interface UriGrantStore {
    fun hasPersistedReadAccess(uri: Uri): Boolean
    fun persist(uri: Uri): Result<Unit>
    fun release(uri: Uri): Result<Unit>
}

internal class PersistentUriStore(
    private val contentResolver: ContentResolver,
) : UriGrantStore {
    override fun hasPersistedReadAccess(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions
            .any { permission ->
                permission.uri == uri &&
                    permission.isReadPermission
            }

    override fun persist(uri: Uri): Result<Unit> =
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

    override fun release(uri: Uri): Result<Unit> =
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
}

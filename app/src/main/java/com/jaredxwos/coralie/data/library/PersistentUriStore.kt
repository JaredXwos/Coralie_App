package com.jaredxwos.coralie.data.library

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

internal class PersistentUriStore(
    private val contentResolver: ContentResolver,
) {
    fun persist(uri: Uri): Result<Unit> =
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }

    fun release(uri: Uri): Result<Unit> =
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
}

package com.jaredxwos.coralie.utility

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

object PersistentUri{
    private lateinit var contentResolver: ContentResolver

    fun init(contentResolver: ContentResolver) {
        this.contentResolver = contentResolver
    }

    fun persist(uri: Uri): Result<Unit> = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Result.success(Unit)
    } catch (e: SecurityException) {
        Result.failure(e)
    }

    fun release(uri: Uri): Result<Unit> = try {
        contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Result.success(Unit)
    } catch (e: SecurityException) {
        Result.failure(e)
    }

    fun retrieveAll(): List<Uri> =
        contentResolver.persistedUriPermissions.map { it.uri }

    fun isPersisted(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri }

    suspend fun retrieveFrom(uri: Uri, destination: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val input = contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(IOException("No stream for $uri"))
                destination.parentFile?.mkdirs()
                input.use { stream ->
                    destination.outputStream().use { output -> stream.copyTo(output) }
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
package com.jaredxwos.coralie.data.library

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PageCache(
    private val cacheDirectory: File,
    private val contentResolver: ContentResolver,
) {
    fun fileFor(assetId: Long): File =
        File(cacheDirectory, "$assetId.html")

    suspend fun copyFromUri(
        assetId: Long,
        sourceUri: Uri,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                cacheDirectory.mkdirs()

                val destination = fileFor(assetId)
                val temporary =
                    File(
                        cacheDirectory,
                        ".$assetId-${System.nanoTime()}.tmp",
                    )

                val input =
                    contentResolver.openInputStream(sourceUri)
                        ?: throw IOException(
                            "No stream available for $sourceUri",
                        )

                try {
                    input.use { source ->
                        temporary.outputStream().use { target ->
                            source.copyTo(target)
                        }
                    }

                    if (
                        !temporary.renameTo(destination)
                    ) {
                        temporary.copyTo(
                            target = destination,
                            overwrite = true,
                        )
                        temporary.delete()
                    }
                } catch (error: Exception) {
                    temporary.delete()
                    throw error
                }

                Result.success(destination)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }

    fun delete(assetId: Long): Boolean =
        fileFor(assetId).delete()
}

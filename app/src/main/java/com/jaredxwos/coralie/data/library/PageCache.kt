package com.jaredxwos.coralie.data.library

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface HtmlPageCache {
    suspend fun copyFromUri(assetId: Long, sourceUri: Uri): Result<File>
    fun delete(assetId: Long): Boolean
}

/** An error while opening or reading the provider-backed source document. */
internal class SourceDocumentReadException(
    cause: Exception,
) : IOException("Unable to read the selected HTML document", cause)

internal class PageCache(
    private val cacheDirectory: File,
    private val contentResolver: ContentResolver,
) : HtmlPageCache {
    fun fileFor(assetId: Long): File =
        File(cacheDirectory, "$assetId.html")

    override suspend fun copyFromUri(
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
                    try {
                        contentResolver.openInputStream(sourceUri)
                            ?: throw IOException(
                                "No stream available for $sourceUri",
                            )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        throw SourceDocumentReadException(error)
                    }

                try {
                    input.use { source ->
                        temporary.outputStream().use { target ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count =
                                    try {
                                        source.read(buffer)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: Exception) {
                                        throw SourceDocumentReadException(error)
                                    }
                                if (count < 0) break
                                target.write(buffer, 0, count)
                            }
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

    override fun delete(assetId: Long): Boolean =
        fileFor(assetId).delete()
}

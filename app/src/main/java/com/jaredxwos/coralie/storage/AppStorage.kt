package com.jaredxwos.coralie.storage

import android.net.Uri
import androidx.core.net.toUri
import com.jaredxwos.coralie.capability.PageCapabilities
import com.jaredxwos.coralie.storage.database.AppDao
import com.jaredxwos.coralie.storage.database.Html
import com.jaredxwos.coralie.storage.database.Space
import com.jaredxwos.coralie.utility.PersistentUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

object AppStorage {
    private val storageScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var dao: AppDao
    private lateinit var filesDir: File

    fun init(dao: AppDao, filesDir: File) {
        this.dao = dao
        this.filesDir = filesDir
    }

    var current: HtmlStorage? = null
        private set

    private var currentSpaceId: Long? = null

    fun internalPathFor(assetId: Long): File =
        File(filesDir, "html/$assetId.html")

    private suspend fun <T> exceptionLogger(
        block: suspend (AppDao) -> T,
    ): Result<T> =
        try {
            if (!::dao.isInitialized) {
                throw IllegalStateException(
                    "AppStorage.init() was not called before use",
                )
            }
            Result.success(block(dao))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }

    fun closeSpaceSync() =
        storageScope.launch { closeSpace() }

    suspend fun closeSpace(): Result<Unit> =
        exceptionLogger {
            current?.close()
            current = null
            currentSpaceId = null
        }

    suspend fun openSpace(spaceId: Long): Result<Unit> =
        exceptionLogger { dao ->
            if (
                currentSpaceId == spaceId &&
                current != null
            ) {
                return@exceptionLogger
            }

            current?.close()
            current = null
            currentSpaceId = spaceId
            current = HtmlStorage(spaceId, dao)
        }

    suspend fun createSpace(name: String): Result<Long> =
        exceptionLogger { dao -> dao.insertSpace(name) }

    suspend fun retrieveAllSpaces(): Result<List<Space>> =
        exceptionLogger { dao -> dao.retrieveAllSpaces() }

    suspend fun isSpaceLinked(spaceId: Long): Result<Boolean> =
        exceptionLogger { dao -> dao.isSpaceLinked(spaceId) }

    suspend fun deleteSpace(spaceId: Long): Result<Unit> =
        exceptionLogger { dao ->
            if (currentSpaceId == spaceId) {
                closeSpace().getOrThrow()
            }
            dao.deleteSpace(spaceId)
        }

    suspend fun addHtml(
        spaceId: Long,
        name: String,
        sourceUri: Uri,
    ): Result<Long> =
        addHtml(
            spaceId = spaceId,
            name = name,
            sourceUri = sourceUri,
            capabilities = PageCapabilities.NONE,
        )

    suspend fun addHtml(
        spaceId: Long,
        name: String,
        sourceUri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> =
        exceptionLogger { dao ->
            PersistentUri.persist(sourceUri).getOrThrow()
            try {
                dao.createHtml(
                    spaceId = spaceId,
                    name = name,
                    sourceUri = sourceUri.toString(),
                    capabilityMask = capabilities.mask,
                )
            } catch (error: Exception) {
                if (!dao.uriExists(sourceUri.toString())) {
                    PersistentUri.release(sourceUri)
                }
                throw error
            }
        }

    /**
     * Replaces an existing HTML asset with a newly inserted row. The database
     * delete+insert is transactional, so a failed insert restores the old row.
     * The replacement receives a new asset ID.
     */
    suspend fun replaceHtml(
        assetId: Long,
        spaceId: Long,
        name: String,
        sourceUri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> =
        exceptionLogger { dao ->
            val existing =
                dao.retrieveHtml(assetId)

            val newUri = sourceUri.toString()
            val uriChanged =
                existing?.sourceUri != newUri

            if (existing == null || uriChanged) {
                PersistentUri.persist(sourceUri).getOrThrow()
            }

            val replacementId =
                try {
                    dao.replaceHtml(
                        assetId = assetId,
                        spaceId = spaceId,
                        name = name,
                        sourceUri = newUri,
                        capabilityMask = capabilities.mask,
                    )
                } catch (error: Exception) {
                    if (
                        (existing == null || uriChanged) &&
                        !dao.uriExists(newUri)
                    ) {
                        PersistentUri.release(sourceUri)
                    }
                    throw error
                }

            internalPathFor(assetId).delete()

            if (
                existing != null &&
                uriChanged &&
                !dao.uriExists(existing.sourceUri)
            ) {
                // The database replacement already committed. Failure to
                // release an obsolete URI grant must not report the edit as
                // failed after the new asset has been saved.
                PersistentUri
                    .release(existing.sourceUri.toUri())
                    .getOrNull()
            }

            replacementId
        }

    suspend fun updateHtmlCapabilities(
        assetId: Long,
        capabilities: PageCapabilities,
    ): Result<Unit> =
        exceptionLogger { dao ->
            dao.retrieveHtml(assetId)
                ?: throw NoSuchElementException(
                    "No HTML asset with id $assetId",
                )
            dao.updateHtmlCapabilities(
                assetId = assetId,
                capabilityMask = capabilities.mask,
            )
        }

    suspend fun retrieveAllHtml(): Result<List<Html>> =
        exceptionLogger { dao -> dao.retrieveAllHtml() }

    suspend fun retrieveHtml(assetId: Long): Result<Html> =
        exceptionLogger { dao ->
            dao.retrieveHtml(assetId)
                ?: throw NoSuchElementException(
                    "No HTML asset with id $assetId",
                )
        }

    suspend fun cache(assetId: Long): Result<Unit> =
        exceptionLogger { dao ->
            PersistentUri.retrieveFrom(
                dao.retrieveUri(assetId).toUri(),
                internalPathFor(assetId),
            ).getOrThrow()
        }

    suspend fun retrieveSourceUri(assetId: Long): Result<String> =
        exceptionLogger { dao -> dao.retrieveUri(assetId) }

    suspend fun removeHtml(assetId: Long): Result<Unit> =
        exceptionLogger { dao ->
            val sourceUri = dao.retrieveUri(assetId)
            dao.deleteHtml(assetId)
            internalPathFor(assetId).delete()

            if (!dao.uriExists(sourceUri)) {
                PersistentUri
                    .release(sourceUri.toUri())
                    .getOrThrow()
            }
        }

    suspend fun clearSpace(spaceId: Long): Result<Unit> =
        exceptionLogger { dao ->
            if (currentSpaceId == spaceId) {
                closeSpace().getOrThrow()
            }

            val htmls =
                dao.retrieveAllHtml()
                    .filter { it.spaceId == spaceId }

            for (html in htmls) {
                dao.deleteHtml(html.assetId)
                internalPathFor(html.assetId).delete()

                if (!dao.uriExists(html.sourceUri)) {
                    PersistentUri
                        .release(html.sourceUri.toUri())
                        .getOrThrow()
                }
            }
        }

    suspend fun retrieveAllDomains(): Result<List<String>> =
        exceptionLogger { dao -> dao.retrieveAllDomain() }

    suspend fun allowDomain(domainUri: String): Result<Unit> =
        exceptionLogger { dao -> dao.allowDomain(domainUri) }

    suspend fun isDomainAllowed(domainUri: String): Result<Boolean> =
        exceptionLogger { dao -> dao.domainAllowed(domainUri) }

    suspend fun disallowDomain(domainUri: String): Result<Unit> =
        exceptionLogger { dao -> dao.disallowDomain(domainUri) }
}

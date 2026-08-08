package com.jaredxwos.coralie.data.library

import android.net.Uri
import androidx.core.net.toUri
import com.jaredxwos.coralie.data.library.model.PageCapabilities
import com.jaredxwos.coralie.data.database.dao.LibraryDao
import com.jaredxwos.coralie.data.database.entity.HtmlPageEntity
import com.jaredxwos.coralie.data.library.model.PageDetails
import com.jaredxwos.coralie.data.library.model.PageSummary
import com.jaredxwos.coralie.data.library.model.SpaceSummary
import com.jaredxwos.coralie.data.library.model.SpaceUsageSummary
import java.io.File
import kotlinx.coroutines.CancellationException

class PageLibrary internal constructor(
    private val dao: LibraryDao,
    private val cache: HtmlPageCache,
    private val uriStore: UriGrantStore,
) {
    suspend fun getPage(
        assetId: Long,
    ): Result<PageDetails> =
        resultOf {
            dao.retrievePage(assetId)
                ?.toDetails()
                ?: throw NoSuchElementException(
                    "No HTML asset with id $assetId",
                )
        }

    suspend fun getPages():
        Result<List<PageSummary>> =
        resultOf {
            val spaces =
                dao.retrieveAllSpaces()
                    .associateBy { space ->
                        space.spaceId
                    }

            dao.retrieveAllPages()
                .map { page ->
                    PageSummary(
                        assetId = page.assetId,
                        spaceId = page.spaceId,
                        name = page.name,
                        spaceName =
                            spaces[page.spaceId]
                                ?.name
                                .orEmpty(),
                        capabilities =
                            PageCapabilities(
                                page.capabilityMask,
                            ),
                    )
                }
        }

    suspend fun getSpaces():
        Result<List<SpaceSummary>> =
        resultOf {
            dao.retrieveAllSpaces()
                .map { space ->
                    SpaceSummary(
                        spaceId = space.spaceId,
                        name = space.name,
                    )
                }
        }

    suspend fun getSpaceUsage():
        Result<List<SpaceUsageSummary>> =
        resultOf {
            val pagesBySpace =
                dao.retrieveAllPages()
                    .groupingBy { page ->
                        page.spaceId
                    }
                    .eachCount()

            dao.retrieveAllSpaces()
                .map { space ->
                    SpaceUsageSummary(
                        spaceId = space.spaceId,
                        name = space.name,
                        pageCount =
                            pagesBySpace[space.spaceId]
                                ?: 0,
                    )
                }
        }

    suspend fun importPage(
        spaceId: Long,
        name: String,
        sourceUri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> =
        resultOf {
            importPageOrThrow(
                spaceId = spaceId,
                name = name,
                sourceUri = sourceUri,
                capabilities = capabilities,
            )
        }

    suspend fun importPageIntoNewSpace(
        spaceName: String,
        pageName: String,
        sourceUri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> =
        resultOf {
            val spaceId =
                dao.insertSpace(spaceName.trim())

            try {
                importPageOrThrow(
                    spaceId = spaceId,
                    name = pageName,
                    sourceUri = sourceUri,
                    capabilities = capabilities,
                )
            } catch (error: Exception) {
                if (!dao.spaceContainsPages(spaceId)) {
                    dao.deleteSpace(spaceId)
                }
                throw error
            }
        }

    suspend fun replacePage(
        assetId: Long,
        spaceId: Long,
        name: String,
        sourceUri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> =
        resultOf {
            replacePageOrThrow(
                assetId = assetId,
                spaceId = spaceId,
                name = name,
                sourceUri = sourceUri,
                capabilities = capabilities,
            )
        }

    suspend fun replacePageIntoNewSpace(
        assetId: Long,
        spaceName: String,
        pageName: String,
        sourceUri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> =
        resultOf {
            val spaceId =
                dao.insertSpace(spaceName.trim())

            try {
                replacePageOrThrow(
                    assetId = assetId,
                    spaceId = spaceId,
                    name = pageName,
                    sourceUri = sourceUri,
                    capabilities = capabilities,
                )
            } catch (error: Exception) {
                if (!dao.spaceContainsPages(spaceId)) {
                    dao.deleteSpace(spaceId)
                }
                throw error
            }
        }

    suspend fun deletePage(
        assetId: Long,
    ): Result<Unit> =
        resultOf {
            val existing =
                dao.retrievePage(assetId)
                    ?: throw NoSuchElementException(
                        "No HTML asset with id $assetId",
                    )

            dao.deletePage(assetId)
            cache.delete(assetId)
            releaseSourceIfUnused(existing.sourceUri)
        }

    suspend fun clearSpace(
        spaceId: Long,
    ): Result<Unit> =
        resultOf {
            val pages =
                dao.retrievePagesInSpace(spaceId)

            dao.deletePagesInSpace(spaceId)

            pages.forEach { page ->
                cache.delete(page.assetId)
            }

            for (
                sourceUri in pages
                    .map { page -> page.sourceUri }
                    .distinct()
            ) {
                releaseSourceIfUnused(sourceUri)
            }
        }

    suspend fun deleteSpace(
        spaceId: Long,
    ): Result<Unit> =
        resultOf {
            val pages =
                dao.retrievePagesInSpace(spaceId)

            dao.deleteSpace(spaceId)

            pages.forEach { page ->
                cache.delete(page.assetId)
            }

            for (
                sourceUri in pages
                    .map { page -> page.sourceUri }
                    .distinct()
            ) {
                releaseSourceIfUnused(sourceUri)
            }
        }

    suspend fun updatePageCapabilities(
        assetId: Long,
        capabilities: PageCapabilities,
    ): Result<Unit> =
        resultOf {
            dao.retrievePage(assetId)
                ?: throw NoSuchElementException(
                    "No HTML asset with id $assetId",
                )

            dao.updatePageCapabilities(
                assetId = assetId,
                capabilityMask = capabilities.mask,
            )
        }

    /**
     * Rebuilds the internal HTML copy from the page's current source URI.
     *
     * An existing cache file is deliberately not treated as authoritative.
     * Returning it here would silently reopen an older page after the source
     * document had been replaced or edited. [PageCache.copyFromUri] writes
     * through a temporary file, so a failed refresh leaves the previous cache
     * intact for diagnostics but does not return it as a successful load.
     */
    suspend fun refreshCached(
        assetId: Long,
    ): Result<File> =
        getPage(assetId)
            .fold(
                onSuccess = { page ->
                    cache.copyFromUri(
                        assetId = assetId,
                        sourceUri = page.sourceUri,
                    ).fold(
                        onSuccess = { file -> Result.success(file) },
                        onFailure = { error ->
                            val grantPresent =
                                uriStore.hasPersistedReadAccess(page.sourceUri)
                            if (!grantPresent || error is SourceDocumentReadException) {
                                Result.failure(
                                    DocumentAccessException(
                                        sourceUri = page.sourceUri,
                                        grantPresent = grantPresent,
                                        cause = error,
                                    ),
                                )
                            } else {
                                Result.failure(error)
                            }
                        },
                    )
                },
                onFailure = { error ->
                    Result.failure(error)
                },
            )

    /**
     * Reauthorizes a saved page without changing its identity or metadata.
     * The database changes only after the replacement URI is persistable and
     * readable. This also repairs a revoked grant when [sourceUri] is unchanged.
     */
    suspend fun reselectSource(
        assetId: Long,
        sourceUri: Uri,
    ): Result<File> =
        resultOf {
            val existing =
                dao.retrievePage(assetId)
                    ?: throw NoSuchElementException(
                        "No HTML asset with id $assetId",
                    )
            val oldSource = existing.sourceUri
            val newSource = sourceUri.toString()
            val grantExisted =
                uriStore.hasPersistedReadAccess(sourceUri)

            uriStore.persist(sourceUri).getOrThrow()

            val refreshed =
                cache.copyFromUri(assetId, sourceUri)
                    .getOrElse { error ->
                        if (
                            !grantExisted &&
                            oldSource != newSource &&
                            !dao.sourceUriExists(newSource)
                        ) {
                            uriStore.release(sourceUri).getOrNull()
                        }
                        val grantPresent =
                            uriStore.hasPersistedReadAccess(sourceUri)
                        if (!grantPresent || error is SourceDocumentReadException) {
                            throw DocumentAccessException(
                                sourceUri = sourceUri,
                                grantPresent = grantPresent,
                                cause = error,
                            )
                        }
                        throw error
                    }

            try {
                if (oldSource != newSource) {
                    check(
                        dao.updatePageSourceUri(assetId, newSource) == 1,
                    ) {
                        "No HTML asset with id $assetId"
                    }
                    releaseSourceIfUnused(oldSource)
                }
            } catch (error: Exception) {
                if (
                    !grantExisted &&
                    oldSource != newSource &&
                    !dao.sourceUriExists(newSource)
                ) {
                    uriStore.release(sourceUri).getOrNull()
                }
                throw error
            }

            refreshed
        }

    private suspend fun importPageOrThrow(
        spaceId: Long,
        name: String,
        sourceUri: Uri,
        capabilities: PageCapabilities,
    ): Long {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) {
            "Page name must not be blank"
        }

        val grantExisted =
            uriStore.hasPersistedReadAccess(sourceUri)
        uriStore.persist(sourceUri).getOrThrow()

        return try {
            dao.createPage(
                spaceId = spaceId,
                name = normalizedName,
                sourceUri = sourceUri.toString(),
                capabilityMask = capabilities.mask,
            )
        } catch (error: Exception) {
            if (
                !grantExisted &&
                !dao.sourceUriExists(
                    sourceUri.toString(),
                )
            ) {
                uriStore.release(sourceUri)
            }
            throw error
        }
    }

    private suspend fun replacePageOrThrow(
        assetId: Long,
        spaceId: Long,
        name: String,
        sourceUri: Uri,
        capabilities: PageCapabilities,
    ): Long {
        val normalizedName = name.trim()
        require(normalizedName.isNotBlank()) {
            "Page name must not be blank"
        }

        val existing =
            dao.retrievePage(assetId)
        val newSource =
            sourceUri.toString()
        val sourceChanged =
            existing?.sourceUri != newSource
        val grantExisted =
            uriStore.hasPersistedReadAccess(sourceUri)

        if (
            existing == null ||
            sourceChanged ||
            !uriStore.hasPersistedReadAccess(sourceUri)
        ) {
            uriStore.persist(sourceUri).getOrThrow()
        }

        val replacementId =
            try {
                dao.replacePage(
                    assetId = assetId,
                    spaceId = spaceId,
                    name = normalizedName,
                    sourceUri = newSource,
                    capabilityMask = capabilities.mask,
                )
            } catch (error: Exception) {
                if (
                    !grantExisted &&
                    (existing == null || sourceChanged) &&
                    !dao.sourceUriExists(newSource)
                ) {
                    uriStore.release(sourceUri)
                }
                throw error
            }

        cache.delete(assetId)

        if (
            existing != null &&
            sourceChanged
        ) {
            releaseSourceIfUnused(
                existing.sourceUri,
            )
        }

        return replacementId
    }

    private suspend fun releaseSourceIfUnused(
        sourceUri: String,
    ) {
        if (!dao.sourceUriExists(sourceUri)) {
            /*
             * Database work has already committed. A stale external URI grant
             * is preferable to reporting the completed operation as failed.
             */
            uriStore.release(sourceUri.toUri())
                .getOrNull()
        }
    }

    private fun HtmlPageEntity.toDetails():
        PageDetails =
        PageDetails(
            assetId = assetId,
            spaceId = spaceId,
            name = name,
            sourceUri = sourceUri.toUri(),
            capabilities =
                PageCapabilities(capabilityMask),
        )

    private suspend fun <T> resultOf(
        block: suspend () -> T,
    ): Result<T> =
        try {
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
}

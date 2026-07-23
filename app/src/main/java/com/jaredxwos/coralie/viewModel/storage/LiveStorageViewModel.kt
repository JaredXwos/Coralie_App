package com.jaredxwos.coralie.viewModel.storage

import android.net.Uri
import com.jaredxwos.coralie.capability.PageCapabilities
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.ui.composable.component.rows.fileRow.FileRowConfig
import com.jaredxwos.coralie.ui.composable.component.rows.spaceRow.SpaceRowConfig
import com.jaredxwos.coralie.ui.composable.component.rows.spaceUsageRow.SpaceUsage

/**
 * [StorageViewModel] backed by [AppStorage].
 *
 * Capability policies are passed as [PageCapabilities] and persisted by
 * [AppStorage] as the HTML asset's Long capability mask.
 */
object LiveStorageViewModel : StorageViewModel {
    override suspend fun retrieveAllFileConfig(): Result<List<FileRowConfig>> {
        val spaces =
            AppStorage.retrieveAllSpaces()
                .getOrElse { return Result.failure(it) }

        val html =
            AppStorage.retrieveAllHtml()
                .getOrElse { return Result.failure(it) }

        val spaceNameById =
            spaces.associate { space ->
                space.spaceId to space.name
            }

        return Result.success(
            html.map { file ->
                FileRowConfig(
                    assetId = file.assetId,
                    spaceId = file.spaceId,
                    name = file.name,
                    spaceName =
                        spaceNameById[file.spaceId]
                            ?: "(unknown space)",
                    capabilityMask = file.capabilityMask,
                )
            },
        )
    }

    override suspend fun retrieveAllSpaceConfig(): Result<List<SpaceRowConfig>> =
        AppStorage.retrieveAllSpaces().map { spaces ->
            spaces.map { space ->
                SpaceRowConfig(
                    spaceId = space.spaceId,
                    name = space.name,
                )
            }
        }

    override suspend fun retrieveAllSpaceUsage(): Result<List<SpaceUsage>> {
        val spaces =
            AppStorage.retrieveAllSpaces()
                .getOrElse { return Result.failure(it) }

        val html =
            AppStorage.retrieveAllHtml()
                .getOrElse { return Result.failure(it) }

        val fileCountBySpaceId =
            html.groupingBy { file ->
                file.spaceId
            }.eachCount()

        return Result.success(
            spaces.map { space ->
                SpaceUsage(
                    spaceId = space.spaceId,
                    name = space.name,
                    htmlCount =
                        fileCountBySpaceId[space.spaceId]
                            ?: 0,
                )
            },
        )
    }

    override suspend fun clearSpace(
        spaceId: Long,
    ): Result<Unit> =
        AppStorage.clearSpace(spaceId)

    override suspend fun deleteSpace(
        spaceId: Long,
    ): Result<Unit> =
        AppStorage.deleteSpace(spaceId)

    override suspend fun saveNewFileToExistingSpace(
        spaceId: Long,
        name: String,
        uri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> =
        AppStorage.addHtml(
            spaceId = spaceId,
            name = name.trim(),
            sourceUri = uri,
            capabilities = capabilities,
        )

    override suspend fun saveNewFileToNewSpace(
        spaceName: String,
        name: String,
        uri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> {
        val spaceId =
            AppStorage.createSpace(spaceName.trim())
                .getOrElse { return Result.failure(it) }

        val result =
            AppStorage.addHtml(
                spaceId = spaceId,
                name = name.trim(),
                sourceUri = uri,
                capabilities = capabilities,
            )

        if (
            result.isFailure &&
            !AppStorage.isSpaceLinked(spaceId)
                .getOrDefault(true)
        ) {
            AppStorage.deleteSpace(spaceId)
        }

        return result
    }

    override suspend fun updateFileInExistingSpace(
        assetId: Long,
        spaceId: Long,
        name: String,
        uri: Uri,
        capabilities: PageCapabilities,
    ): Result<Unit> =
        AppStorage.updateHtml(
            assetId = assetId,
            spaceId = spaceId,
            name = name.trim(),
            sourceUri = uri,
            capabilities = capabilities,
        )

    override suspend fun updateFileInNewSpace(
        assetId: Long,
        spaceName: String,
        name: String,
        uri: Uri,
        capabilities: PageCapabilities,
    ): Result<Unit> {
        val spaceId =
            AppStorage.createSpace(spaceName.trim())
                .getOrElse { return Result.failure(it) }

        val result =
            AppStorage.updateHtml(
                assetId = assetId,
                spaceId = spaceId,
                name = name.trim(),
                sourceUri = uri,
                capabilities = capabilities,
            )

        if (
            result.isFailure &&
            !AppStorage.isSpaceLinked(spaceId)
                .getOrDefault(true)
        ) {
            AppStorage.deleteSpace(spaceId)
        }

        return result
    }

    override suspend fun removeFile(
        assetId: Long,
    ): Result<Unit> =
        AppStorage.removeHtml(assetId)

    override suspend fun retrieveFileUri(
        assetId: Long,
    ): Result<String> =
        AppStorage.retrieveSourceUri(assetId)

    override suspend fun retrieveAllAllowedDomains(): Result<List<String>> =
        AppStorage.retrieveAllDomains()

    override suspend fun isDomainAllowed(
        domainUri: String,
    ): Result<Boolean> =
        AppStorage.isDomainAllowed(domainUri)

    override suspend fun disallowDomain(
        domainUri: String,
    ): Result<Unit> =
        AppStorage.disallowDomain(domainUri)
}

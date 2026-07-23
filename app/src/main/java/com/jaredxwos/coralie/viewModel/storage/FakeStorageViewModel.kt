package com.jaredxwos.coralie.viewModel.storage

import android.net.Uri
import com.jaredxwos.coralie.capability.PageCapabilities
import com.jaredxwos.coralie.ui.composable.component.rows.fileRow.FileRowConfig
import com.jaredxwos.coralie.ui.composable.component.rows.spaceRow.SpaceRowConfig
import com.jaredxwos.coralie.ui.composable.component.rows.spaceUsageRow.SpaceUsage

class FakeStorageViewModel : StorageViewModel {
    private data class SeedSpace(
        val spaceId: Long,
        val name: String,
    )

    private data class SeedFile(
        val assetId: Long,
        val spaceId: Long,
        val name: String,
        val uri: Uri,
        val capabilityMask: Long,
    )

    private var nextSpaceId = 100L
    private var nextAssetId = 1000L

    private val spaces = mutableListOf(
        SeedSpace(1L, "Recipes"),
        SeedSpace(2L, "Work Notes"),
        SeedSpace(3L, "Travel"),
    )

    private val files = mutableListOf(
        SeedFile(
            1L,
            1L,
            "Banana Bread.html",
            Uri.EMPTY,
            PageCapabilities.NONE_MASK,
        ),
        SeedFile(
            2L,
            2L,
            "Sprint Retro.html",
            Uri.EMPTY,
            PageCapabilities.NONE_MASK,
        ),
    )

    override suspend fun retrieveAllFileConfig(): Result<List<FileRowConfig>> {
        val nameById = spaces.associate { it.spaceId to it.name }
        return Result.success(
            files.map { file ->
                FileRowConfig(
                    assetId = file.assetId,
                    spaceId = file.spaceId,
                    name = file.name,
                    spaceName =
                        nameById[file.spaceId]
                            ?: "(unknown space)",
                    capabilityMask = file.capabilityMask,
                )
            },
        )
    }

    override suspend fun retrieveAllSpaceConfig(): Result<List<SpaceRowConfig>> =
        Result.success(
            spaces.map {
                SpaceRowConfig(
                    spaceId = it.spaceId,
                    name = it.name,
                )
            },
        )

    override suspend fun saveNewFileToExistingSpace(
        spaceId: Long,
        name: String,
        uri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> {
        if (spaces.none { it.spaceId == spaceId }) {
            return Result.failure(
                IllegalArgumentException(
                    "No such space: $spaceId",
                ),
            )
        }

        val assetId = nextAssetId++
        files.add(
            SeedFile(
                assetId,
                spaceId,
                name.trim(),
                uri,
                capabilities.mask,
            ),
        )
        return Result.success(assetId)
    }

    override suspend fun saveNewFileToNewSpace(
        spaceName: String,
        name: String,
        uri: Uri,
        capabilities: PageCapabilities,
    ): Result<Long> {
        val spaceId = nextSpaceId++
        spaces.add(SeedSpace(spaceId, spaceName.trim()))
        return saveNewFileToExistingSpace(
            spaceId,
            name,
            uri,
            capabilities,
        )
    }

    override suspend fun updateFileInExistingSpace(
        assetId: Long,
        spaceId: Long,
        name: String,
        uri: Uri,
        capabilities: PageCapabilities,
    ): Result<Unit> {
        val index = files.indexOfFirst { it.assetId == assetId }
        if (index < 0) {
            return Result.failure(
                NoSuchElementException(
                    "No HTML asset with id $assetId",
                ),
            )
        }
        if (spaces.none { it.spaceId == spaceId }) {
            return Result.failure(
                NoSuchElementException(
                    "No space with id $spaceId",
                ),
            )
        }

        files[index] = SeedFile(
            assetId,
            spaceId,
            name.trim(),
            uri,
            capabilities.mask,
        )
        return Result.success(Unit)
    }

    override suspend fun updateFileInNewSpace(
        assetId: Long,
        spaceName: String,
        name: String,
        uri: Uri,
        capabilities: PageCapabilities,
    ): Result<Unit> {
        val spaceId = nextSpaceId++
        spaces.add(SeedSpace(spaceId, spaceName.trim()))
        return updateFileInExistingSpace(
            assetId,
            spaceId,
            name,
            uri,
            capabilities,
        )
    }

    override suspend fun removeFile(assetId: Long): Result<Unit> {
        files.removeAll { it.assetId == assetId }
        return Result.success(Unit)
    }

    override suspend fun retrieveFileUri(assetId: Long): Result<String> =
        files.firstOrNull { it.assetId == assetId }
            ?.let { Result.success(it.uri.toString()) }
            ?: Result.failure(
                NoSuchElementException(
                    "No HTML asset with id $assetId",
                ),
            )

    override suspend fun retrieveAllAllowedDomains(): Result<List<String>> =
        Result.success(emptyList())

    override suspend fun isDomainAllowed(domainUri: String): Result<Boolean> =
        Result.success(false)

    override suspend fun disallowDomain(domainUri: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun retrieveAllSpaceUsage(): Result<List<SpaceUsage>> =
        Result.success(
            spaces.map { space ->
                SpaceUsage(
                    space.spaceId,
                    space.name,
                    files.count { it.spaceId == space.spaceId },
                )
            },
        )

    override suspend fun clearSpace(spaceId: Long): Result<Unit> {
        files.removeAll { it.spaceId == spaceId }
        return Result.success(Unit)
    }

    override suspend fun deleteSpace(spaceId: Long): Result<Unit> {
        files.removeAll { it.spaceId == spaceId }
        spaces.removeAll { it.spaceId == spaceId }
        return Result.success(Unit)
    }
}

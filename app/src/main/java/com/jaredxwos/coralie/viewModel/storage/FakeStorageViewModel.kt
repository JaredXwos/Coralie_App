package com.jaredxwos.coralie.viewModel.storage

import android.net.Uri
import com.jaredxwos.coralie.ui.composable.component.rows.fileRow.FileRowConfig
import com.jaredxwos.coralie.ui.composable.component.rows.spaceRow.SpaceRowConfig
import com.jaredxwos.coralie.ui.composable.component.rows.spaceUsageRow.SpaceUsage

/**
 * In-memory [StorageViewModel] for @Preview and tests. No AppStorage dependency.
 *
 * Keeps spaces and files as separate seed lists and performs the join itself
 * (§3.5-B) rather than pre-baking joined rows — that way a broken join shows up
 * against the fake during preview, same as it would against real storage.
 *
 * Not thread-safe (plain MutableList) — fine for single-threaded preview/test
 * use; don't share one instance across concurrent coroutines.
 */
class FakeStorageViewModel : StorageViewModel {

    private data class SeedSpace(val spaceId: Long, val name: String)
    private data class SeedFile(val assetId: Long, val spaceId: Long, val name: String)

    private var nextSpaceId = 100L
    private var nextAssetId = 1000L

    private val spaces = mutableListOf(
        SeedSpace(1L, "Recipes"),
        SeedSpace(2L, "Work Notes"),
        SeedSpace(3L, "Travel"),
    )

    private val files = mutableListOf(
        SeedFile(1L, 1L, "Banana Bread.html"),
        SeedFile(2L, 1L, "Curry Night.html"),
        SeedFile(3L, 2L, "Sprint Retro.html"),
        SeedFile(4L, 3L, "Japan Itinerary.html"),
        SeedFile(5L, 3L, "Packing List.html"),
    )

    override suspend fun retrieveAllFileConfig(): Result<List<FileRowConfig>> {
        val nameById = spaces.associate { it.spaceId to it.name }
        return Result.success(
            files.map { f ->
                FileRowConfig(
                    assetId = f.assetId,
                    spaceId = f.spaceId,
                    name = f.name,
                    spaceName = nameById[f.spaceId] ?: "(unknown space)",
                )
            },
        )
    }

    override suspend fun retrieveAllSpaceConfig(): Result<List<SpaceRowConfig>> =
        Result.success(spaces.map { SpaceRowConfig(spaceId = it.spaceId, name = it.name) })

    override suspend fun saveNewFileToExistingSpace(
        spaceId: Long,
        name: String,
        uri: Uri,
    ): Result<Long> {
        if (spaces.none { it.spaceId == spaceId }) {
            return Result.failure(IllegalArgumentException("No such space: $spaceId"))
        }
        val assetId = nextAssetId++
        files.add(SeedFile(assetId, spaceId, name.trim()))
        return Result.success(assetId)
    }

    override suspend fun saveNewFileToNewSpace(
        spaceName: String,
        name: String,
        uri: Uri,
    ): Result<Long> {
        val spaceId = nextSpaceId++
        spaces.add(SeedSpace(spaceId, spaceName.trim()))
        val assetId = nextAssetId++
        files.add(SeedFile(assetId, spaceId, name.trim()))
        return Result.success(assetId)
    }

    override suspend fun removeFile(assetId: Long): Result<Unit> {
        files.removeAll { it.assetId == assetId }
        return Result.success(Unit)
    }

    override suspend fun retrieveFileUri(assetId: Long): Result<String> {
        return Result.success("")
    }

    override suspend fun retrieveAllAllowedDomains(): Result<List<String>> {
        return Result.success(emptyList())
    }

    override suspend fun isDomainAllowed(domainUri: String): Result<Boolean> {
        return Result.success(false)
    }

    override suspend fun disallowDomain(domainUri: String): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun retrieveAllSpaceUsage(): Result<List<SpaceUsage>> {
        return Result.success(emptyList())
    }

    override suspend fun clearSpace(spaceId: Long): Result<Unit> {
        return Result.success(Unit)
    }

    override suspend fun deleteSpace(spaceId: Long): Result<Unit> {
        return Result.success(Unit)
    }
}
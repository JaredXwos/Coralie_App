package com.jaredxwos.coralie.viewModel.storage

import android.net.Uri
import com.jaredxwos.coralie.connection.DataChannelFrame
import com.jaredxwos.coralie.storage.AppStorage
import com.jaredxwos.coralie.ui.composable.component.fileRow.FileRowConfig
import com.jaredxwos.coralie.ui.composable.component.spaceRow.SpaceRowConfig

/**
 * [StorageViewModel] backed by [AppStorage].
 *
 * Performs the file/space join itself (§3.5-B), same as
 * [FakeStorageViewModel], rather than relying on AppStorage to pre-join.
 *
 * Only the first five methods are wired up per current scope. The rest
 * intentionally fail loudly via TODO() rather than silently no-op-ing —
 * a no-op success on a "real" backing store would be worse than a crash,
 * since it hides the fact that nothing happened (§7 Open items).
 */
object LiveStorageViewModel : StorageViewModel {

    override suspend fun retrieveAllFileConfig(): Result<List<FileRowConfig>> {
        val spaces = AppStorage.retrieveAllSpaces().getOrElse { return Result.failure(it) }
        val html = AppStorage.retrieveAllHtml().getOrElse { return Result.failure(it) }
        val nameById = spaces.associate { it.spaceId to it.name }
        return Result.success(
            html.map { f ->
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
        AppStorage.retrieveAllSpaces().map { spaces ->
            spaces.map { SpaceRowConfig(spaceId = it.spaceId, name = it.name) }
        }

    override suspend fun saveNewFileToExistingSpace(
        spaceId: Long,
        name: String,
        uri: Uri,
    ): Result<Long> = AppStorage.addHtml(spaceId, name.trim(), uri)

    override suspend fun saveNewFileToNewSpace(
        spaceName: String,
        name: String,
        uri: Uri,
    ): Result<Long> {
        val spaceId = AppStorage.createSpace(spaceName.trim()).getOrElse { return Result.failure(it) }
        return AppStorage.addHtml(spaceId, name.trim(), uri)
    }

    override suspend fun removeFile(assetId: Long): Result<Unit> =
        AppStorage.removeHtml(assetId)

    override suspend fun retrieveFileUri(assetId: Long): Result<String> =
        AppStorage.retrieveSourceUri(assetId)

    override suspend fun retrieveAllAllowedDomains(): Result<List<String>> = AppStorage.retrieveAllDomains()
    override suspend fun isDomainAllowed(domainUri: String): Result<Boolean> = AppStorage.isDomainAllowed(domainUri)
    override suspend fun disallowDomain(domainUri: String): Result<Unit> = AppStorage.disallowDomain(domainUri)

    // --- Not yet wired (§7 Open items) ---

    override suspend fun renameFile(assetId: Long, name: String): Result<Unit> {
        TODO("renameFile not yet implemented — wire to AppStorage.updateHtmlName")
    }

    override suspend fun renameSpace(spaceId: Long, name: String): Result<Unit> {
        TODO("renameSpace not yet implemented — wire to AppStorage.updateSpaceName")
    }

    override suspend fun changeFileUri(assetId: Long, uri: Uri): Result<Unit> {
        TODO("changeFileUri not yet implemented — wire to AppStorage.updateHtmlUri (+ old-URI release?)")
    }

    override suspend fun removeSpace(spaceId: Long): Result<Unit> {
        TODO("removeSpace not yet implemented — wire to AppStorage.deleteSpace (+ isSpaceLinked check?)")
    }
}
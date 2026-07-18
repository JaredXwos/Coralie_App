package com.jaredxwos.coralie.viewModel.storage

import android.net.Uri
import com.jaredxwos.coralie.ui.composable.component.rows.fileRow.FileRowConfig
import com.jaredxwos.coralie.ui.composable.component.rows.spaceRow.SpaceRowConfig
import com.jaredxwos.coralie.ui.composable.component.rows.spaceUsageRow.SpaceUsage

interface StorageViewModel{
    suspend fun retrieveAllFileConfig(): Result<List<FileRowConfig>>
    suspend fun retrieveAllSpaceConfig(): Result<List<SpaceRowConfig>>
    suspend fun saveNewFileToExistingSpace(spaceId: Long, name: String, uri: Uri): Result<Long>
    suspend fun saveNewFileToNewSpace(spaceName: String, name: String, uri: Uri): Result<Long>
    suspend fun removeFile(assetId: Long): Result<Unit>
    suspend fun retrieveFileUri(assetId: Long): Result<String>
    suspend fun retrieveAllAllowedDomains(): Result<List<String>>
    suspend fun isDomainAllowed(domainUri: String): Result<Boolean>
    suspend fun disallowDomain(domainUri: String): Result<Unit>
    suspend fun retrieveAllSpaceUsage(): Result<List<SpaceUsage>>
    suspend fun clearSpace(spaceId: Long): Result<Unit>
    suspend fun deleteSpace(spaceId: Long): Result<Unit>
}
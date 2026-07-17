package com.jaredxwos.coralie.viewModel.storage

import android.net.Uri
import com.jaredxwos.coralie.ui.composable.component.fileRow.FileRowConfig
import com.jaredxwos.coralie.ui.composable.component.spaceRow.SpaceRowConfig

interface StorageViewModel{
    suspend fun retrieveAllFileConfig(): Result<List<FileRowConfig>>
    suspend fun retrieveAllSpaceConfig(): Result<List<SpaceRowConfig>>
    suspend fun saveNewFileToExistingSpace(spaceId: Long, name: String, uri: Uri): Result<Long>
    suspend fun saveNewFileToNewSpace(spaceName: String, name: String, uri: Uri): Result<Long>
    suspend fun removeFile(assetId: Long): Result<Unit>
    suspend fun retrieveFileUri(assetId: Long): Result<String>
    suspend fun renameFile(assetId: Long, name: String): Result<Unit>
    suspend fun renameSpace(spaceId: Long, name: String): Result<Unit>
    suspend fun changeFileUri(assetId: Long, uri: Uri): Result<Unit>
    suspend fun removeSpace(spaceId: Long): Result<Unit>
}
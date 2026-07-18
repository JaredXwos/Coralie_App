package com.jaredxwos.coralie.ui.composable.component.rows.fileRow

import kotlinx.serialization.Serializable

@Serializable
data class FileRowConfig(
    val assetId: Long,
    val spaceId: Long,
    val name: String,
    val spaceName: String
)
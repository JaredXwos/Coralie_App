package com.jaredxwos.coralie.feature.home.component

import com.jaredxwos.coralie.data.library.model.PageCapabilities
import kotlinx.serialization.Serializable

@Serializable
data class FileRowConfig(
    val assetId: Long,
    val spaceId: Long,
    val name: String,
    val spaceName: String,
    val capabilityMask: Long = PageCapabilities.NONE_MASK,
)

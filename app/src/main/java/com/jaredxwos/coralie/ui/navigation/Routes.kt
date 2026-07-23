package com.jaredxwos.coralie.ui.navigation

import com.jaredxwos.coralie.capability.PageCapabilities
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object AddFileRoute

@Serializable
data class EditFileRoute(
    val assetId: Long,
    val spaceId: Long,
    val existingName: String,
    val sourceUri: String,
    val capabilityMask: Long = PageCapabilities.NONE_MASK,
)

@Serializable
data class ViewerRoute(
    val assetId: Long,
    val spaceId: Long,
    val name: String,
)

@Serializable
data object SettingsRoute

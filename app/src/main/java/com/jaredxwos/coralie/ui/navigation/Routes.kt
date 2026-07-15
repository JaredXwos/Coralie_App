package com.jaredxwos.coralie.ui.navigation
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object AddFileRoute

@Serializable
data class ViewerRoute(
    val assetId: Long,
    val spaceId: Long,
    val name: String
)

@Serializable
data object SettingsRoute
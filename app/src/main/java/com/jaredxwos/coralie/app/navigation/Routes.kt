package com.jaredxwos.coralie.app.navigation

import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object AddFileRoute

@Serializable
data class EditFileRoute(
    val assetId: Long,
)

@Serializable
data class ViewerRoute(
    val assetId: Long,
)

@Serializable
data object SettingsRoute

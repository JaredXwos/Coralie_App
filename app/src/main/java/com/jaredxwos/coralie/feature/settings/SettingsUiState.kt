package com.jaredxwos.coralie.feature.settings

import com.jaredxwos.coralie.data.library.model.SpaceUsageSummary

enum class SettingsOperation {
    LOAD_SPACES,
    LOAD_DOMAINS,
    CLEAR_SPACE,
    DELETE_SPACE,
    REVOKE_DOMAIN,
}

data class SettingsError(
    val operation: SettingsOperation,
    val cause: Throwable,
)

data class SettingsUiState(
    val spaces: List<SpaceUsageSummary> =
        emptyList(),
    val allowedDomains: List<String> =
        emptyList(),
    val isLoading: Boolean = true,
    val pendingSpaceId: Long? = null,
    val pendingDomain: String? = null,
    val error: SettingsError? = null,
)

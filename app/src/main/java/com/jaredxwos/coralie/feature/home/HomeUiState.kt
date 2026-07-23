package com.jaredxwos.coralie.feature.home

import com.jaredxwos.coralie.data.library.model.PageSummary

enum class HomeOperation {
    LOAD,
    DELETE,
}

data class HomeError(
    val operation: HomeOperation,
    val cause: Throwable,
)

data class HomeUiState(
    val pages: List<PageSummary> =
        emptyList(),
    val isLoading: Boolean = true,
    val deletingAssetId: Long? = null,
    val error: HomeError? = null,
)

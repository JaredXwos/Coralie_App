package com.jaredxwos.coralie.feature.viewer

import com.jaredxwos.coralie.data.library.model.PageDetails
import com.jaredxwos.coralie.feature.viewer.runtime.ViewerSession
import java.io.File

sealed interface ViewerUiState {
    data object Loading : ViewerUiState

    data class Ready(
        val page: PageDetails,
        val cachedFile: File,
        val session: ViewerSession,
    ) : ViewerUiState

    data class Failed(
        val cause: Throwable,
    ) : ViewerUiState
}

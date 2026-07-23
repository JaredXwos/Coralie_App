package com.jaredxwos.coralie.feature.editor

import android.net.Uri
import com.jaredxwos.coralie.data.library.model.PageCapabilities
import com.jaredxwos.coralie.data.library.model.SpaceSummary

sealed interface EditorSpaceSelection {
    data object None :
        EditorSpaceSelection

    data class Existing(
        val spaceId: Long,
    ) : EditorSpaceSelection

    data class New(
        val name: String,
    ) : EditorSpaceSelection
}

enum class PageEditorValidationError {
    MISSING_NAME,
    MISSING_FILE,
    MISSING_SPACE,
}

enum class PageEditorOperation {
    LOAD,
    SAVE,
}

data class PageEditorError(
    val operation: PageEditorOperation,
    val cause: Throwable,
)

data class PageEditorUiState(
    val isEditing: Boolean = false,
    val pageName: String = "",
    val selectedUri: Uri? = null,
    val spaces: List<SpaceSummary> =
        emptyList(),
    val spaceSelection:
        EditorSpaceSelection =
        EditorSpaceSelection.None,
    val capabilities: PageCapabilities =
        PageCapabilities.NONE,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val completed: Boolean = false,
    val validationError:
        PageEditorValidationError? = null,
    val operationError: PageEditorError? = null,
)

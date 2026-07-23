package com.jaredxwos.coralie.feature.editor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jaredxwos.coralie.data.library.model.PageCapabilities
import com.jaredxwos.coralie.data.library.model.PageCapability
import com.jaredxwos.coralie.data.library.PageLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PageEditorViewModel(
    private val mode: PageEditorMode,
    private val pageLibrary: PageLibrary,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            PageEditorUiState(
                isEditing =
                    mode is PageEditorMode.Edit,
            ),
        )
    val uiState:
        StateFlow<PageEditorUiState> =
        _uiState.asStateFlow()

    init {
        load()
    }

    fun setPageName(value: String) {
        _uiState.mutate {
            it.copy(
                pageName = value,
                validationError = null,
            )
        }
    }

    fun setSourceUri(uri: Uri) {
        _uiState.mutate {
            it.copy(
                selectedUri = uri,
                validationError = null,
            )
        }
    }

    fun selectSpace(spaceId: Long) {
        _uiState.mutate {
            it.copy(
                spaceSelection =
                    EditorSpaceSelection
                        .Existing(spaceId),
                validationError = null,
            )
        }
    }

    fun startNewSpaceSelection() {
        _uiState.mutate {
            it.copy(
                spaceSelection =
                    EditorSpaceSelection.New(""),
                validationError = null,
            )
        }
    }

    fun setNewSpaceName(value: String) {
        _uiState.mutate {
            it.copy(
                spaceSelection =
                    if (value.isBlank()) {
                        EditorSpaceSelection.New("")
                    } else {
                        EditorSpaceSelection.New(
                            value,
                        )
                    },
                validationError = null,
            )
        }
    }

    fun setCapability(
        capability: PageCapability,
        enabled: Boolean,
    ) {
        _uiState.mutate { state ->
            val capabilities =
                state.capabilities
                    .asSet()
                    .toMutableSet()

            if (enabled) {
                capabilities += capability
            } else {
                capabilities -= capability
            }

            state.copy(
                capabilities =
                    PageCapabilities.from(
                        capabilities,
                    ),
            )
        }
    }

    fun submit() {
        val state = _uiState.value
        if (
            state.isSaving ||
            state.isLoading
        ) {
            return
        }

        val trimmedName =
            state.pageName.trim()
        val uri = state.selectedUri
        val selection =
            state.spaceSelection

        val validationError =
            when {
                trimmedName.isBlank() ->
                    PageEditorValidationError
                        .MISSING_NAME
                uri == null ->
                    PageEditorValidationError
                        .MISSING_FILE
                selection is
                    EditorSpaceSelection.None ->
                    PageEditorValidationError
                        .MISSING_SPACE
                selection is
                    EditorSpaceSelection.New &&
                    selection.name.trim()
                        .isBlank() ->
                    PageEditorValidationError
                        .MISSING_SPACE
                else -> null
            }

        if (validationError != null) {
            _uiState.mutate {
                it.copy(
                    validationError =
                        validationError,
                    operationError = null,
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.mutate {
                it.copy(
                    isSaving = true,
                    validationError = null,
                    operationError = null,
                )
            }

            val result =
                when (val activeMode = mode) {
                    PageEditorMode.Create ->
                        when (selection) {
                            is EditorSpaceSelection
                                .Existing ->
                                pageLibrary.importPage(
                                    spaceId =
                                        selection.spaceId,
                                    name = trimmedName,
                                    sourceUri =
                                        requireNotNull(uri),
                                    capabilities =
                                        state.capabilities,
                                )

                            is EditorSpaceSelection.New ->
                                pageLibrary
                                    .importPageIntoNewSpace(
                                        spaceName =
                                            selection.name,
                                        pageName =
                                            trimmedName,
                                        sourceUri =
                                            requireNotNull(uri),
                                        capabilities =
                                            state.capabilities,
                                    )

                            EditorSpaceSelection.None ->
                                error(
                                    "Space selection " +
                                        "disappeared",
                                )
                        }

                    is PageEditorMode.Edit ->
                        when (selection) {
                            is EditorSpaceSelection
                                .Existing ->
                                pageLibrary.replacePage(
                                    assetId =
                                        activeMode.assetId,
                                    spaceId =
                                        selection.spaceId,
                                    name = trimmedName,
                                    sourceUri =
                                        requireNotNull(uri),
                                    capabilities =
                                        state.capabilities,
                                )

                            is EditorSpaceSelection.New ->
                                pageLibrary
                                    .replacePageIntoNewSpace(
                                        assetId =
                                            activeMode.assetId,
                                        spaceName =
                                            selection.name,
                                        pageName =
                                            trimmedName,
                                        sourceUri =
                                            requireNotNull(uri),
                                        capabilities =
                                            state.capabilities,
                                    )

                            EditorSpaceSelection.None ->
                                error(
                                    "Space selection " +
                                        "disappeared",
                                )
                        }
                }

            result
                .onSuccess {
                    _uiState.mutate {
                        it.copy(
                            isSaving = false,
                            completed = true,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.mutate {
                        it.copy(
                            isSaving = false,
                            operationError =
                                PageEditorError(
                                    operation =
                                        PageEditorOperation.SAVE,
                                    cause = error,
                                ),
                        )
                    }
                }
        }
    }

    fun consumeCompletion() {
        _uiState.mutate {
            it.copy(completed = false)
        }
    }

    fun consumeError() {
        _uiState.mutate {
            it.copy(
                validationError = null,
                operationError = null,
            )
        }
    }

    private fun load() {
        viewModelScope.launch {
            val spaces =
                pageLibrary.getSpaces()
                    .getOrElse { error ->
                        _uiState.mutate {
                            it.copy(
                                isLoading = false,
                                operationError =
                                    PageEditorError(
                                        operation =
                                            PageEditorOperation.LOAD,
                                        cause = error,
                                    ),
                            )
                        }
                        return@launch
                    }

            when (val activeMode = mode) {
                PageEditorMode.Create -> {
                    _uiState.mutate {
                        it.copy(
                            spaces = spaces,
                            isLoading = false,
                        )
                    }
                }

                is PageEditorMode.Edit -> {
                    pageLibrary
                        .getPage(
                            activeMode.assetId,
                        )
                        .onSuccess { page ->
                            _uiState.mutate {
                                it.copy(
                                    pageName =
                                        page.name,
                                    selectedUri =
                                        page.sourceUri,
                                    spaces = spaces,
                                    spaceSelection =
                                        EditorSpaceSelection
                                            .Existing(
                                                page.spaceId,
                                            ),
                                    capabilities =
                                        page.capabilities,
                                    isLoading = false,
                                )
                            }
                        }
                        .onFailure { error ->
                            _uiState.mutate {
                                it.copy(
                                    spaces = spaces,
                                    isLoading = false,
                                    operationError =
                                        PageEditorError(
                                            operation =
                                                PageEditorOperation.LOAD,
                                            cause = error,
                                        ),
                                )
                            }
                        }
                }
            }
        }
    }

    companion object {
        fun factory(
            mode: PageEditorMode,
            pageLibrary: PageLibrary,
        ): ViewModelProvider.Factory =
            object :
                ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel>
                    create(
                    modelClass: Class<T>,
                ): T {
                    require(
                        modelClass.isAssignableFrom(
                            PageEditorViewModel::
                                class.java,
                        ),
                    )
                    return PageEditorViewModel(
                        mode = mode,
                        pageLibrary =
                            pageLibrary,
                    ) as T
                }
            }
    }
}

private inline fun <T> MutableStateFlow<T>.mutate(
    transform: (T) -> T,
) {
    value = transform(value)
}

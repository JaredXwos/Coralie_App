package com.jaredxwos.coralie.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jaredxwos.coralie.data.library.PageLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val pageLibrary: PageLibrary,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> =
        _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loadPages(showLoading = true)
        }
    }

    fun deletePage(assetId: Long) {
        if (
            _uiState.value.deletingAssetId != null
        ) {
            return
        }

        viewModelScope.launch {
            _uiState.mutate {
                it.copy(
                    deletingAssetId = assetId,
                    error = null,
                )
            }

            pageLibrary.deletePage(assetId)
                .onSuccess {
                    _uiState.mutate { state ->
                        state.copy(
                            pages =
                                state.pages
                                    .filterNot { page ->
                                        page.assetId ==
                                            assetId
                                    },
                            deletingAssetId = null,
                        )
                    }
                    loadPages(showLoading = false)
                }
                .onFailure { error ->
                    _uiState.mutate {
                        it.copy(
                            deletingAssetId = null,
                            error =
                                HomeError(
                                    operation =
                                        HomeOperation.DELETE,
                                    cause = error,
                                ),
                        )
                    }
                }
        }
    }

    fun consumeError() {
        _uiState.mutate {
            it.copy(error = null)
        }
    }

    private suspend fun loadPages(
        showLoading: Boolean,
    ) {
        if (showLoading) {
            _uiState.mutate {
                it.copy(
                    isLoading = true,
                    error = null,
                )
            }
        }

        pageLibrary.getPages()
            .onSuccess { pages ->
                _uiState.mutate {
                    it.copy(
                        pages = pages,
                        isLoading = false,
                        error = null,
                    )
                }
            }
            .onFailure { error ->
                _uiState.mutate {
                    it.copy(
                        isLoading = false,
                        error =
                            HomeError(
                                operation =
                                    HomeOperation.LOAD,
                                cause = error,
                            ),
                    )
                }
            }
    }

    companion object {
        fun factory(
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
                            HomeViewModel::class.java,
                        ),
                    )
                    return HomeViewModel(
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

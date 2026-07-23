package com.jaredxwos.coralie.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jaredxwos.coralie.data.library.PageLibrary
import com.jaredxwos.coralie.data.permission.DomainPermissionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val pageLibrary: PageLibrary,
    private val domainPermissionStore:
        DomainPermissionStore,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(SettingsUiState())
    val uiState:
        StateFlow<SettingsUiState> =
        _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loadAll(showLoading = true)
        }
    }

    fun clearSpace(spaceId: Long) {
        mutateSpace(
            spaceId = spaceId,
            operation =
                SettingsOperation.CLEAR_SPACE,
        ) {
            pageLibrary.clearSpace(spaceId)
        }
    }

    fun deleteSpace(spaceId: Long) {
        mutateSpace(
            spaceId = spaceId,
            operation =
                SettingsOperation.DELETE_SPACE,
        ) {
            pageLibrary.deleteSpace(spaceId)
        }
    }

    fun revokeDomain(domain: String) {
        if (
            _uiState.value.pendingDomain != null
        ) {
            return
        }

        viewModelScope.launch {
            _uiState.mutate {
                it.copy(
                    pendingDomain = domain,
                    error = null,
                )
            }

            domainPermissionStore
                .revoke(domain)
                .onSuccess {
                    _uiState.mutate { state ->
                        state.copy(
                            allowedDomains =
                                state.allowedDomains
                                    .filterNot {
                                        it == domain
                                    },
                            pendingDomain = null,
                        )
                    }
                    loadAll(showLoading = false)
                }
                .onFailure { error ->
                    _uiState.mutate {
                        it.copy(
                            pendingDomain = null,
                            error =
                                SettingsError(
                                    operation =
                                        SettingsOperation
                                            .REVOKE_DOMAIN,
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

    private fun mutateSpace(
        spaceId: Long,
        operation: SettingsOperation,
        block: suspend () -> Result<Unit>,
    ) {
        if (
            _uiState.value.pendingSpaceId != null
        ) {
            return
        }

        viewModelScope.launch {
            _uiState.mutate {
                it.copy(
                    pendingSpaceId = spaceId,
                    error = null,
                )
            }

            block()
                .onSuccess {
                    _uiState.mutate {
                        it.copy(
                            pendingSpaceId = null,
                        )
                    }
                    loadAll(showLoading = false)
                }
                .onFailure { error ->
                    _uiState.mutate {
                        it.copy(
                            pendingSpaceId = null,
                            error =
                                SettingsError(
                                    operation =
                                        operation,
                                    cause = error,
                                ),
                        )
                    }
                }
        }
    }

    private suspend fun loadAll(
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

        val spacesResult =
            pageLibrary.getSpaceUsage()

        if (spacesResult.isFailure) {
            _uiState.mutate {
                it.copy(
                    isLoading = false,
                    error =
                        SettingsError(
                            operation =
                                SettingsOperation
                                    .LOAD_SPACES,
                            cause =
                                requireNotNull(
                                    spacesResult
                                        .exceptionOrNull(),
                                ),
                        ),
                )
            }
            return
        }

        val domainsResult =
            domainPermissionStore
                .getAllowedDomains()

        if (domainsResult.isFailure) {
            _uiState.mutate {
                it.copy(
                    spaces =
                        spacesResult
                            .getOrThrow(),
                    isLoading = false,
                    error =
                        SettingsError(
                            operation =
                                SettingsOperation
                                    .LOAD_DOMAINS,
                            cause =
                                requireNotNull(
                                    domainsResult
                                        .exceptionOrNull(),
                                ),
                        ),
                )
            }
            return
        }

        _uiState.mutate {
            it.copy(
                spaces =
                    spacesResult.getOrThrow(),
                allowedDomains =
                    domainsResult.getOrThrow(),
                isLoading = false,
                error = null,
            )
        }
    }

    companion object {
        fun factory(
            pageLibrary: PageLibrary,
            domainPermissionStore:
                DomainPermissionStore,
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
                            SettingsViewModel::
                                class.java,
                        ),
                    )
                    return SettingsViewModel(
                        pageLibrary =
                            pageLibrary,
                        domainPermissionStore =
                            domainPermissionStore,
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

package com.jaredxwos.coralie.feature.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jaredxwos.coralie.data.library.PageLibrary
import com.jaredxwos.coralie.feature.viewer.runtime.ViewerSession
import com.jaredxwos.coralie.feature.viewer.runtime.ViewerSessionFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ViewerViewModel(
    private val assetId: Long,
    private val pageLibrary: PageLibrary,
    private val sessionFactory:
        ViewerSessionFactory,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow<ViewerUiState>(
            ViewerUiState.Loading,
        )
    val uiState: StateFlow<ViewerUiState> =
        _uiState.asStateFlow()

    private var session:
        ViewerSession? = null

    init {
        load()
    }

    override fun onCleared() {
        session?.close()
        session = null
    }

    private fun load() {
        viewModelScope.launch {
            val page =
                pageLibrary.getPage(assetId)
                    .getOrElse { error ->
                        _uiState.value =
                            ViewerUiState
                                .Failed(error)
                        return@launch
                    }

            val cachedFile =
                pageLibrary
                    .ensureCached(assetId)
                    .getOrElse { error ->
                        _uiState.value =
                            ViewerUiState
                                .Failed(error)
                        return@launch
                    }

            val newSession =
                sessionFactory.create(
                    page = page,
                    parentScope =
                        viewModelScope,
                )

            try {
                newSession.prepare()
            } catch (error: Exception) {
                newSession.close()
                _uiState.value =
                    ViewerUiState.Failed(
                        error,
                    )
                return@launch
            }

            session?.close()
            session = newSession
            _uiState.value =
                ViewerUiState.Ready(
                    page = page,
                    cachedFile = cachedFile,
                    session = newSession,
                )
        }
    }

    companion object {
        fun factory(
            assetId: Long,
            pageLibrary: PageLibrary,
            sessionFactory:
                ViewerSessionFactory,
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
                            ViewerViewModel::
                                class.java,
                        ),
                    )
                    return ViewerViewModel(
                        assetId = assetId,
                        pageLibrary =
                            pageLibrary,
                        sessionFactory =
                            sessionFactory,
                    ) as T
                }
            }
    }
}

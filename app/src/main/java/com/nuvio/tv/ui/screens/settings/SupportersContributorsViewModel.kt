package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.repository.GitHubContributor
import com.nuvio.tv.data.repository.GitHubContributorsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SupportersContributorsUiState(
    val isContributorsLoading: Boolean = false,
    val hasLoadedContributors: Boolean = false,
    val contributors: List<GitHubContributor> = emptyList(),
    val contributorsErrorMessage: String? = null,
    val selectedContributor: GitHubContributor? = null
)

@HiltViewModel
class SupportersContributorsViewModel @Inject constructor(
    private val contributorsRepository: GitHubContributorsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupportersContributorsUiState())
    val uiState: StateFlow<SupportersContributorsUiState> = _uiState.asStateFlow()

    init {
        loadContributors(force = false)
    }

    fun retryContributors() {
        loadContributors(force = true)
    }

    fun onContributorSelected(contributor: GitHubContributor) {
        _uiState.update { it.copy(selectedContributor = contributor) }
    }

    fun dismissContributorDetails() {
        _uiState.update { it.copy(selectedContributor = null) }
    }

    private fun loadContributors(force: Boolean) {
        val current = _uiState.value
        if (current.isContributorsLoading) return
        if (!force && current.hasLoadedContributors) return

        _uiState.update {
            it.copy(
                isContributorsLoading = true,
                contributorsErrorMessage = null
            )
        }

        viewModelScope.launch {
            val result = contributorsRepository.getContributors()
            result.fold(
                onSuccess = { list ->
                    _uiState.update {
                        it.copy(
                            isContributorsLoading = false,
                            hasLoadedContributors = true,
                            contributors = list,
                            contributorsErrorMessage = null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isContributorsLoading = false,
                            contributorsErrorMessage = error.message
                        )
                    }
                }
            )
        }
    }
}

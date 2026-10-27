package com.nuvio.tv.updater

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaystoreAppUpdate(
    val tag: String = "",
    val title: String = "",
    val version: String = ""
)

data class UpdateUiState(
    val isChecking: Boolean = false,
    val update: PlaystoreAppUpdate? = null,
    val latestRelease: PlaystoreAppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showBanner: Boolean = false,
    val showPromptDialog: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
    val feedbackMessage: String? = null,
    val updateBannerEnabled: Boolean = false,
    val updateChannel: UpdateChannel = UpdateChannel.STABLE,
    val autoInstallEnabled: Boolean = false,
    val autoInstallSupported: Boolean = false,
    val autoInstallNoticeEnabled: Boolean = false
)

@HiltViewModel
class UpdateViewModel @Inject constructor() : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    fun checkForUpdates(
        force: Boolean = false,
        showNoUpdateFeedback: Boolean = false,
        ignoreBannerSetting: Boolean = false
    ) = Unit

    fun dismissBanner() = Unit

    fun dismissPromptDialog() = Unit

    fun dismissUnknownSourcesDialog() = Unit

    fun consumeFeedbackMessage() = Unit

    fun setUpdateBannerEnabled(enabled: Boolean) = Unit

    fun setUpdateChannel(channel: UpdateChannel) = Unit

    fun downloadUpdate() = Unit

    fun installUpdateOrRequestPermission() = Unit

    fun openUnknownSourcesSettings() = Unit

    fun setAutoInstallEnabled(enabled: Boolean) = Unit

    fun setAutoInstallNoticeEnabled(enabled: Boolean) = Unit
}

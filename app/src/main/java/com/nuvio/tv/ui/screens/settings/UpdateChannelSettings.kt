package com.nuvio.tv.ui.screens.settings

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.updater.UpdateViewModel

@Composable
internal fun UpdateChannelSettings(
    initialFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: UpdateViewModel = hiltViewModel(context as ComponentActivity)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsToggleRow(
        title = stringResource(R.string.about_update_banner_title),
        subtitle = stringResource(R.string.about_update_banner_subtitle),
        checked = state.updateBannerEnabled,
        onToggle = {
            viewModel.setUpdateBannerEnabled(!state.updateBannerEnabled)
        },
        modifier = modifier.then(
            if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester)
            else Modifier
        )
    )

    var showConfirmUpdateDialog by rememberSaveable { mutableStateOf(false) }

    val updateSubtitle = when {
        state.isChecking -> stringResource(R.string.update_checking)
        state.isUpdateAvailable && state.update != null -> stringResource(R.string.update_available_title, state.update?.tag.orEmpty())
        else -> stringResource(R.string.about_check_updates_subtitle)
    }


    SettingsActionRow(
        title = stringResource(R.string.about_check_updates),
        subtitle = updateSubtitle,
        value = when {
            state.isChecking -> stringResource(R.string.update_wait)
            state.isUpdateAvailable -> "(1)"
            else -> null
        },
        trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
        onClick = {
            if (state.isUpdateAvailable && state.update != null) {
                showConfirmUpdateDialog = true
            } else {
                viewModel.checkForUpdates(force = true, showNoUpdateFeedback = true)
            }
        }
    )

    if (showConfirmUpdateDialog || state.isDownloading) {
        val update = state.update
        val updateTag = update?.tag ?: ""
        com.nuvio.tv.ui.components.NuvioDialog(
            onDismiss = {
                if (!state.isDownloading) showConfirmUpdateDialog = false
            },
            title = if (state.isDownloading) {
                stringResource(R.string.update_downloading_title)
            } else {
                stringResource(R.string.update_available_dialog_title)
            },
            subtitle = if (state.isDownloading) {
                val progress = ((state.downloadProgress ?: 0f) * 100).toInt()
                stringResource(R.string.update_downloading_version_progress, updateTag, progress)
            } else {
                stringResource(R.string.update_available_message, updateTag)
            },
            width = 440.dp
        ) {
            if (!state.isDownloading) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = com.nuvio.tv.ui.theme.NuvioTheme.spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(com.nuvio.tv.ui.theme.NuvioTheme.spacing.md, Alignment.End)
                ) {
                    androidx.tv.material3.Button(
                        onClick = { showConfirmUpdateDialog = false },
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = com.nuvio.tv.ui.theme.NuvioTheme.colors.BackgroundCard,
                            contentColor = com.nuvio.tv.ui.theme.NuvioTheme.colors.TextPrimary
                        )
                    ) {
                        androidx.tv.material3.Text(stringResource(R.string.update_later))
                    }

                    androidx.tv.material3.Button(
                        onClick = {
                            viewModel.downloadUpdate()
                            showConfirmUpdateDialog = false
                        },
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = com.nuvio.tv.ui.theme.NuvioTheme.colors.Secondary,
                            contentColor = com.nuvio.tv.ui.theme.NuvioTheme.colors.OnSecondary
                        )
                    ) {
                        androidx.tv.material3.Text(stringResource(R.string.update_now))
                    }
                }
            } else {
                val progress = state.downloadProgress ?: 0f
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = com.nuvio.tv.ui.theme.NuvioTheme.spacing.md)
                )
            }
        }
    }
}


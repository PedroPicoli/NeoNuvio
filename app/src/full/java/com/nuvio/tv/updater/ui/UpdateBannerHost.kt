package com.nuvio.tv.updater.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.updater.UpdateUiState

@Composable
fun UpdateBannerHost(
    state: UpdateUiState,
    onDismissBanner: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismissUnknownSources: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onFeedbackShown: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showReleaseNotes by remember { mutableStateOf(false) }

    LaunchedEffect(state.update?.tag) {
        showReleaseNotes = false
    }

    LaunchedEffect(state.feedbackMessage) {
        val message = state.feedbackMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onFeedbackShown()
    }

    DisposableEffect(lifecycleOwner, state.showUnknownSourcesDialog) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && state.showUnknownSourcesDialog) {
                onInstall()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // A newer release takes the banner; otherwise it can report the release that was just installed automatically.
    val update = state.update ?: state.installedUpdate
    val showBanner = state.showBanner && update != null

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showBanner,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(durationMillis = 300)
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(durationMillis = 240)
            ) + fadeOut(animationSpec = tween(durationMillis = 150))
        ) {
            update?.let {
                UpdateBanner(
                    state = state,
                    update = it,
                    isInstalledUpdate = state.update == null,
                    autoDismissPaused = showReleaseNotes,
                    onDownload = onDownload,
                    onInstall = onInstall,
                    onShowReleaseNotes = { showReleaseNotes = true },
                    onDismiss = onDismissBanner
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            content()
        }
    }

    if (showReleaseNotes && update != null) {
        UpdateReleaseNotesDialog(
            update = update,
            onDismiss = { showReleaseNotes = false }
        )
    }

    if (state.showUnknownSourcesDialog) {
        UpdateUnknownSourcesDialog(
            onOpenSettings = onOpenUnknownSources,
            onDismiss = onDismissUnknownSources
        )
    }

    if (state.showPromptDialog && update != null && !state.isDownloading && state.downloadedApkPath == null) {
        NuvioDialog(
            onDismiss = onDismissBanner,
            title = stringResource(R.string.update_dialog_title),
            subtitle = stringResource(R.string.update_dialog_message, update.tag),
            width = 460.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = NuvioTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, Alignment.End)
            ) {
                Button(
                    onClick = onDismissBanner,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.BackgroundCard,
                        contentColor = NuvioTheme.colors.TextPrimary
                    )
                ) {
                    Text(stringResource(R.string.update_later))
                }

                Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioTheme.colors.Secondary,
                        contentColor = NuvioTheme.colors.OnSecondary
                    )
                ) {
                    Text(stringResource(R.string.update_now))
                }
            }
        }
    }
}

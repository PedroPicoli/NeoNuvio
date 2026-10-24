package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.dub.ForceDubWarningType
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun ForceDubWarningDialog(
    warningType: ForceDubWarningType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val confirmFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { confirmFocusRequester.requestFocus() }
    }

    val (title, description) = when (warningType) {
        ForceDubWarningType.WARNING_4K_AUDIO -> Pair(
            stringResource(R.string.force_dub_warn_4k_title),
            stringResource(R.string.force_dub_warn_4k_desc)
        )
        ForceDubWarningType.WARNING_BLIND_SYNC -> Pair(
            stringResource(R.string.force_dub_warn_blind_title),
            stringResource(R.string.force_dub_warn_blind_desc)
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(NuvioTheme.radii.xl))
                .background(NuvioTheme.colors.BackgroundElevated)
        ) {
            Column(
                modifier = Modifier.padding(NuvioTheme.spacing.xl),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
                )

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.xl)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md, Alignment.End)
                ) {
                    // Botão 2: Reproduzir original
                    Card(
                        onClick = onDismiss,
                        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                        colors = CardDefaults.colors(
                            containerColor = NuvioTheme.colors.SurfaceVariant,
                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        scale = CardDefaults.scale(focusedScale = 1.02f)
                    ) {
                        Text(
                            text = stringResource(R.string.force_dub_btn_play_original),
                            style = MaterialTheme.typography.labelLarge,
                            color = NuvioTheme.colors.TextPrimary,
                            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md)
                        )
                    }

                    // Botão 1: Continuar com dublagem
                    Card(
                        onClick = onConfirm,
                        modifier = Modifier.focusRequester(confirmFocusRequester),
                        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                        colors = CardDefaults.colors(
                            containerColor = NuvioTheme.colors.Primary,
                            focusedContainerColor = NuvioTheme.colors.Primary
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                            )
                        ),
                        scale = CardDefaults.scale(focusedScale = 1.02f)
                    ) {
                        Text(
                            text = stringResource(R.string.force_dub_btn_continue),
                            style = MaterialTheme.typography.labelLarge,
                            color = NuvioTheme.colors.OnPrimary,
                            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md)
                        )
                    }
                }
            }
        }
    }
}

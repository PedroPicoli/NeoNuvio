@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nuvio.tv.ui.screens.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.ui.screens.settings.LanguageSelectionDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LanguageOption
import com.nuvio.tv.domain.model.SeriesCustomSettings
import com.nuvio.tv.domain.model.SeriesSourceAnalyzer
import com.nuvio.tv.domain.model.VerifiedAddonGroup
import com.nuvio.tv.domain.model.VerifiedSource
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun SeriesSettingsDialog(
    seriesTitle: String,
    initialSettings: SeriesCustomSettings,
    verifiedAddons: List<VerifiedAddonGroup>,
    isAnalyzing: Boolean,
    onSaveSettings: (SeriesCustomSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var settings by remember(initialSettings) { mutableStateOf(initialSettings) }
    var expandedAddonName by remember { mutableStateOf<String?>(null) }
    var showForceDubLanguageDialog by remember { mutableStateOf(false) }
    val firstFocusRequester = remember { FocusRequester() }
    val forceDubLangFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    BackHandler {
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(820.dp)
                    .height(640.dp)
                    .clip(RoundedCornerShape(NuvioTheme.radii.xxl))
                    .background(NuvioTheme.colors.SurfaceVariant)
                    .border(
                        border = BorderStroke(1.dp, NuvioTheme.colors.Border.copy(alpha = 0.4f)),
                        shape = RoundedCornerShape(NuvioTheme.radii.xxl)
                    )
                    .padding(NuvioTheme.spacing.xxl)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(NuvioTheme.colors.Primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = NuvioTheme.colors.Primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = stringResource(R.string.series_custom_settings_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = NuvioTheme.colors.TextPrimary
                                )
                                Text(
                                    text = seriesTitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = NuvioTheme.colors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Card(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp),
                            shape = CardDefaults.shape(CircleShape),
                            colors = CardDefaults.colors(
                                containerColor = NuvioTheme.colors.SurfaceVariant,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            scale = CardDefaults.scale(focusedScale = 1.1f)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.series_custom_settings_close),
                                    tint = NuvioTheme.colors.TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                    ) {
                        // 1. Desativar Skips
                        item {
                            val isSkipsDisabled = settings.disableSkips
                            var isFocused by remember { mutableStateOf(false) }

                            Card(
                                onClick = {
                                    val newSettings = settings.copy(disableSkips = !settings.disableSkips)
                                    settings = newSettings
                                    onSaveSettings(newSettings)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(firstFocusRequester)
                                    .onFocusChanged { isFocused = it.isFocused },
                                shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.lg)),
                                colors = CardDefaults.colors(
                                    containerColor = NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.6f),
                                    focusedContainerColor = NuvioTheme.colors.FocusBackground
                                ),
                                border = CardDefaults.border(
                                    focusedBorder = Border(
                                        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                        shape = RoundedCornerShape(NuvioTheme.radii.lg)
                                    )
                                ),
                                scale = CardDefaults.scale(focusedScale = 1.01f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(NuvioTheme.spacing.lg),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Speed,
                                            contentDescription = null,
                                            tint = if (isFocused) NuvioTheme.colors.Primary else NuvioTheme.colors.TextSecondary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column {
                                            Text(
                                                text = stringResource(R.string.series_custom_settings_disable_skips),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = NuvioTheme.colors.TextPrimary
                                            )
                                            Text(
                                                text = if (isSkipsDisabled) {
                                                    stringResource(R.string.series_custom_settings_skips_off)
                                                } else {
                                                    stringResource(R.string.series_custom_settings_skips_on)
                                                },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isSkipsDisabled) Color(0xFFFFB74D) else NuvioTheme.colors.TextSecondary
                                            )
                                        }
                                    }

                                    Switch(
                                        checked = isSkipsDisabled,
                                        onCheckedChange = { checked ->
                                            val newSettings = settings.copy(disableSkips = checked)
                                            settings = newSettings
                                            onSaveSettings(newSettings)
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = NuvioTheme.colors.Primary,
                                            checkedTrackColor = NuvioTheme.colors.Primary.copy(alpha = 0.35f),
                                            uncheckedThumbColor = NuvioTheme.colors.TextSecondary,
                                            uncheckedTrackColor = NuvioTheme.colors.SurfaceVariant
                                        )
                                    )
                                }
                            }
                        }

                        // Forçar dublagem (Experimental) para esta série
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        tint = NuvioTheme.colors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.force_dub_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NuvioTheme.colors.TextPrimary
                                    )
                                }

                                // 3 Modos: Padrão (Seguir app), Sempre dublar, Nunca dublar
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                                    contentPadding = PaddingValues(vertical = 2.dp)
                                ) {
                                    // 1. Padrão (Seguir app)
                                    item {
                                        val isSelected = settings.forceDubEnabled == null
                                        Card(
                                            onClick = {
                                                val newSettings = settings.copy(forceDubEnabled = null)
                                                settings = newSettings
                                                onSaveSettings(newSettings)
                                            },
                                            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.xl)),
                                            colors = CardDefaults.colors(
                                                containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.25f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f),
                                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                                            ),
                                            border = CardDefaults.border(
                                                focusedBorder = Border(
                                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                                    shape = RoundedCornerShape(NuvioTheme.radii.xl)
                                                )
                                            ),
                                            scale = CardDefaults.scale(focusedScale = 1.05f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = NuvioTheme.colors.Primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Text(
                                                    text = stringResource(R.string.series_force_dub_mode_default),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                                                )
                                            }
                                        }
                                    }

                                    // 2. Sempre dublar
                                    item {
                                        val isSelected = settings.forceDubEnabled == true
                                        Card(
                                            onClick = {
                                                val newSettings = settings.copy(forceDubEnabled = true)
                                                settings = newSettings
                                                onSaveSettings(newSettings)
                                                coroutineScope.launch {
                                                    delay(120)
                                                    runCatching { forceDubLangFocusRequester.requestFocus() }
                                                }
                                            },
                                            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.xl)),
                                            colors = CardDefaults.colors(
                                                containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.25f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f),
                                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                                            ),
                                            border = CardDefaults.border(
                                                focusedBorder = Border(
                                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                                    shape = RoundedCornerShape(NuvioTheme.radii.xl)
                                                )
                                            ),
                                            scale = CardDefaults.scale(focusedScale = 1.05f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = NuvioTheme.colors.Primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Text(
                                                    text = stringResource(R.string.series_force_dub_mode_enabled),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                                                )
                                            }
                                        }
                                    }

                                    // 3. Nunca dublar
                                    item {
                                        val isSelected = settings.forceDubEnabled == false
                                        Card(
                                            onClick = {
                                                val newSettings = settings.copy(forceDubEnabled = false)
                                                settings = newSettings
                                                onSaveSettings(newSettings)
                                            },
                                            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.xl)),
                                            colors = CardDefaults.colors(
                                                containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.25f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f),
                                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                                            ),
                                            border = CardDefaults.border(
                                                focusedBorder = Border(
                                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                                    shape = RoundedCornerShape(NuvioTheme.radii.xl)
                                                )
                                            ),
                                            scale = CardDefaults.scale(focusedScale = 1.05f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = NuvioTheme.colors.Primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Text(
                                                    text = stringResource(R.string.series_force_dub_mode_disabled),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                                                )
                                            }
                                        }
                                    }
                                }

                                // Se não for "Nunca dublar", permite escolher o idioma alvo desta série
                                if (settings.forceDubEnabled != false) {
                                    val currentLangCode = settings.forceDubTargetLanguage
                                    val currentLangLabel = if (currentLangCode.isNullOrBlank()) {
                                        stringResource(R.string.series_force_dub_language_default)
                                    } else {
                                        AVAILABLE_SUBTITLE_LANGUAGES.find { it.code.equals(currentLangCode, ignoreCase = true) }?.displayName ?: currentLangCode.uppercase()
                                    }

                                    Card(
                                        onClick = { showForceDubLanguageDialog = true },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(forceDubLangFocusRequester),
                                        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                                        colors = CardDefaults.colors(
                                            containerColor = NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.35f),
                                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                                        ),
                                        border = CardDefaults.border(
                                            focusedBorder = Border(
                                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                                            )
                                        ),
                                        scale = CardDefaults.scale(focusedScale = 1.01f)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text(
                                                    text = stringResource(R.string.series_force_dub_select_language),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = NuvioTheme.colors.TextPrimary
                                                )
                                                Text(
                                                    text = currentLangLabel,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = NuvioTheme.colors.Primary
                                                )
                                            }
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = null,
                                                tint = NuvioTheme.colors.TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Selecionar Fonte
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = NuvioTheme.colors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.series_custom_settings_select_source),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NuvioTheme.colors.TextPrimary
                                    )
                                    if (isAnalyzing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .size(16.dp),
                                            color = NuvioTheme.colors.Primary,
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = stringResource(R.string.series_custom_settings_source_analyzing),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = NuvioTheme.colors.TextSecondary
                                        )
                                    }
                                }

                                // Opção Automático
                                val hasCustomPreferredSource = settings.preferredSourceKey != null || settings.preferredSourceName != null
                                val isAuto = !hasCustomPreferredSource && settings.preferredSourceAddon == null
                                var autoFocused by remember { mutableStateOf(false) }

                                if (hasCustomPreferredSource) {
                                    var customSourceFocused by remember { mutableStateOf(false) }
                                    val epInfo = when {
                                        settings.sourceEpisode != null && settings.sourceSeason != null ->
                                            stringResource(R.string.series_custom_settings_based_on_season_episode, settings.sourceSeason!!, settings.sourceEpisode!!)
                                        settings.sourceEpisode != null ->
                                            stringResource(R.string.series_custom_settings_based_on_episode, settings.sourceEpisode!!)
                                        else -> null
                                    }
                                    val sourceSubtitle = listOfNotNull(
                                        epInfo,
                                        settings.preferredSourceAddon?.let { "Add-on: $it" }
                                    ).joinToString(" • ")

                                    Card(
                                        onClick = { /* Já selecionada */ },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { customSourceFocused = it.isFocused },
                                        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                                        colors = CardDefaults.colors(
                                            containerColor = NuvioTheme.colors.Primary.copy(alpha = 0.2f),
                                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                                        ),
                                        border = CardDefaults.border(
                                            focusedBorder = Border(
                                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                                shape = RoundedCornerShape(NuvioTheme.radii.md)
                                            )
                                        ),
                                        scale = CardDefaults.scale(focusedScale = 1.01f)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.series_custom_settings_selected_source_title) + ":",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = NuvioTheme.colors.Primary
                                                    )
                                                    Text(
                                                        text = settings.preferredSourceName ?: settings.preferredSourceKey.orEmpty(),
                                                        style = MaterialTheme.typography.bodyLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        color = NuvioTheme.colors.TextPrimary,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                if (sourceSubtitle.isNotBlank()) {
                                                    Text(
                                                        text = sourceSubtitle,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = NuvioTheme.colors.TextSecondary
                                                    )
                                                }
                                            }
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = NuvioTheme.colors.Primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                Card(
                                    onClick = {
                                        val newSettings = settings.copy(
                                            preferredSourceAddon = null,
                                            preferredSourceKey = null,
                                            preferredSourceName = null,
                                            sourceEpisode = null,
                                            sourceSeason = null,
                                            detectedLanguages = emptyList()
                                        )
                                        settings = newSettings
                                        onSaveSettings(newSettings)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { autoFocused = it.isFocused },
                                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                                    colors = CardDefaults.colors(
                                        containerColor = if (isAuto) NuvioTheme.colors.Primary.copy(alpha = 0.15f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f),
                                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                                    ),
                                    border = CardDefaults.border(
                                        focusedBorder = Border(
                                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                            shape = RoundedCornerShape(NuvioTheme.radii.md)
                                        )
                                    ),
                                    scale = CardDefaults.scale(focusedScale = 1.01f)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = stringResource(R.string.series_custom_settings_source_auto),
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = if (isAuto) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isAuto) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                                            )
                                            Text(
                                                text = stringResource(R.string.series_custom_settings_source_auto_sub),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = NuvioTheme.colors.TextSecondary
                                            )
                                        }
                                        if (isAuto) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = NuvioTheme.colors.Primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                if (!isAnalyzing && verifiedAddons.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.series_custom_settings_source_no_match),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NuvioTheme.colors.TextTertiary,
                                        modifier = Modifier.padding(vertical = NuvioTheme.spacing.sm)
                                    )
                                }

                                // Lista em cascata dos Addons (fechados inicialmente)
                                verifiedAddons.forEach { addonGroup ->
                                    val isExpanded = expandedAddonName == addonGroup.addonName
                                    var addonFocused by remember { mutableStateOf(false) }
                                    val hasSelectedSourceInGroup = settings.preferredSourceAddon == addonGroup.addonName

                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Card(
                                            onClick = {
                                                expandedAddonName = if (isExpanded) null else addonGroup.addonName
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { addonFocused = it.isFocused },
                                            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                                            colors = CardDefaults.colors(
                                                containerColor = if (hasSelectedSourceInGroup) NuvioTheme.colors.Primary.copy(alpha = 0.1f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f),
                                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                                            ),
                                            border = CardDefaults.border(
                                                focusedBorder = Border(
                                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                                    shape = RoundedCornerShape(NuvioTheme.radii.md)
                                                )
                                            ),
                                            scale = CardDefaults.scale(focusedScale = 1.01f)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    val isAddonAlwaysSelected = settings.preferredSourceAddon == addonGroup.addonName && settings.preferredSourceKey == null
                                                    Text(
                                                        text = if (isAddonAlwaysSelected) "${addonGroup.addonName} (Sempre este add-on)" else addonGroup.addonName,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isAddonAlwaysSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.series_custom_settings_sources_count, addonGroup.sources.size),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = NuvioTheme.colors.TextSecondary
                                                    )
                                                }

                                                Icon(
                                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                    contentDescription = null,
                                                    tint = NuvioTheme.colors.TextPrimary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }

                                        // Fontes abertas daquele addon específico
                                        AnimatedVisibility(
                                            visible = isExpanded,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut()
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(start = NuvioTheme.spacing.lg),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                // Opção para usar sempre qualquer fonte deste add-on
                                                val isAddonAlwaysSelected = settings.preferredSourceAddon == addonGroup.addonName && settings.preferredSourceKey == null
                                                var addonAlwaysFocused by remember { mutableStateOf(false) }

                                                Card(
                                                    onClick = {
                                                        val newSettings = settings.copy(
                                                            preferredSourceAddon = addonGroup.addonName,
                                                            preferredSourceKey = null,
                                                            preferredSourceName = "Qualquer fonte do " + addonGroup.addonName
                                                        )
                                                        settings = newSettings
                                                        onSaveSettings(newSettings)
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .onFocusChanged { addonAlwaysFocused = it.isFocused },
                                                    shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm)),
                                                    colors = CardDefaults.colors(
                                                        containerColor = if (isAddonAlwaysSelected) NuvioTheme.colors.Primary.copy(alpha = 0.2f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.35f),
                                                        focusedContainerColor = NuvioTheme.colors.FocusBackground
                                                    ),
                                                    border = CardDefaults.border(
                                                        focusedBorder = Border(
                                                            border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                                                            shape = RoundedCornerShape(NuvioTheme.radii.sm)
                                                        )
                                                    ),
                                                    scale = CardDefaults.scale(focusedScale = 1.01f)
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = stringResource(R.string.series_custom_settings_use_addon_always, addonGroup.addonName),
                                                                style = MaterialTheme.typography.bodyMedium,
                                                                fontWeight = if (isAddonAlwaysSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                                color = if (isAddonAlwaysSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                                                            )
                                                            Text(
                                                                text = stringResource(R.string.series_custom_settings_use_addon_always_sub),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = NuvioTheme.colors.TextSecondary
                                                            )
                                                        }
                                                        if (isAddonAlwaysSelected) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = NuvioTheme.colors.Primary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                addonGroup.sources.forEach { source ->
                                                    val isSelected = settings.preferredSourceKey == source.id
                                                    var sourceFocused by remember { mutableStateOf(false) }

                                                    Card(
                                                        onClick = {
                                                            val newSettings = settings.copy(
                                                                preferredSourceAddon = source.addonName,
                                                                preferredSourceKey = source.id,
                                                                preferredSourceName = source.title,
                                                                sourceSeason = source.season ?: settings.sourceSeason,
                                                                sourceEpisode = source.episode ?: settings.sourceEpisode
                                                            )
                                                            settings = newSettings
                                                            onSaveSettings(newSettings)
                                                        },
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .onFocusChanged { sourceFocused = it.isFocused },
                                                        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.sm)),
                                                        colors = CardDefaults.colors(
                                                            containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.2f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.35f),
                                                            focusedContainerColor = NuvioTheme.colors.FocusBackground
                                                        ),
                                                        border = CardDefaults.border(
                                                            focusedBorder = Border(
                                                                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.hairline),
                                                                shape = RoundedCornerShape(NuvioTheme.radii.sm)
                                                            )
                                                        ),
                                                        scale = CardDefaults.scale(focusedScale = 1.01f)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = source.title,
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary,
                                                                    maxLines = 2,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                                if (source.quality != null || source.languages.isNotEmpty()) {
                                                                    Row(
                                                                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                                                                        verticalAlignment = Alignment.CenterVertically
                                                                    ) {
                                                                        source.quality?.let { q ->
                                                                            Text(
                                                                                text = q,
                                                                                style = MaterialTheme.typography.labelSmall,
                                                                                color = NuvioTheme.colors.TextSecondary
                                                                            )
                                                                        }
                                                                        if (source.languages.isNotEmpty()) {
                                                                            Text(
                                                                                text = source.languages.joinToString(", ") { SeriesSourceAnalyzer.getLanguageLabel(it) },
                                                                                style = MaterialTheme.typography.labelSmall,
                                                                                color = NuvioTheme.colors.Primary.copy(alpha = 0.8f)
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }

                                                            if (isSelected) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Check,
                                                                    contentDescription = null,
                                                                    tint = NuvioTheme.colors.Primary,
                                                                    modifier = Modifier.size(18.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Idioma
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = NuvioTheme.colors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.series_custom_settings_language),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = NuvioTheme.colors.TextPrimary
                                    )
                                }

                                // Se uma fonte específica estiver selecionada, restringe estritamente aos idiomas comuns comprovados nela
                                val selectedSource = remember(settings.preferredSourceKey, verifiedAddons) {
                                    if (settings.preferredSourceKey == null) null
                                    else {
                                        verifiedAddons.flatMap { it.sources }.firstOrNull { it.id == settings.preferredSourceKey }
                                    }
                                }

                                val availableLanguages = remember(selectedSource, settings.detectedLanguages) {
                                    val langs = when {
                                        selectedSource != null && selectedSource.languages.isNotEmpty() -> selectedSource.languages
                                        settings.detectedLanguages.isNotEmpty() -> settings.detectedLanguages
                                        else -> emptyList()
                                    }
                                    if (langs.isNotEmpty()) {
                                        langs.distinct().map { code ->
                                            LanguageOption(code, SeriesSourceAnalyzer.getLanguageLabel(code))
                                        }
                                    } else {
                                        SeriesSourceAnalyzer.ALL_SUPPORTED_LANGUAGES
                                    }
                                }

                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    // Padrão / Automático
                                    item {
                                        val isSelected = settings.preferredLanguage == null
                                        Card(
                                            onClick = {
                                                val newSettings = settings.copy(preferredLanguage = null)
                                                settings = newSettings
                                                onSaveSettings(newSettings)
                                            },
                                            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.xl)),
                                            colors = CardDefaults.colors(
                                                containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.25f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f),
                                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                                            ),
                                            border = CardDefaults.border(
                                                focusedBorder = Border(
                                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                                    shape = RoundedCornerShape(NuvioTheme.radii.xl)
                                                )
                                            ),
                                            scale = CardDefaults.scale(focusedScale = 1.05f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = NuvioTheme.colors.Primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Text(
                                                    text = stringResource(R.string.series_custom_settings_language_auto),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                                                )
                                            }
                                        }
                                    }

                                    items(availableLanguages, key = { it.code }) { lang ->
                                        val isSelected = settings.preferredLanguage.equals(lang.code, ignoreCase = true)
                                        Card(
                                            onClick = {
                                                val newSettings = settings.copy(preferredLanguage = lang.code)
                                                settings = newSettings
                                                onSaveSettings(newSettings)
                                            },
                                            shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.xl)),
                                            colors = CardDefaults.colors(
                                                containerColor = if (isSelected) NuvioTheme.colors.Primary.copy(alpha = 0.25f) else NuvioTheme.colors.SurfaceVariant.copy(alpha = 0.5f),
                                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                                            ),
                                            border = CardDefaults.border(
                                                focusedBorder = Border(
                                                    border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                                                    shape = RoundedCornerShape(NuvioTheme.radii.xl)
                                                )
                                            ),
                                            scale = CardDefaults.scale(focusedScale = 1.05f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = NuvioTheme.colors.Primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                Text(
                                                    text = lang.label,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showForceDubLanguageDialog) {
        LanguageSelectionDialog(
            title = stringResource(R.string.series_force_dub_select_language),
            selectedLanguage = settings.forceDubTargetLanguage,
            showNoneOption = true,
            onLanguageSelected = { lang ->
                val newSettings = settings.copy(forceDubTargetLanguage = lang)
                settings = newSettings
                onSaveSettings(newSettings)
                showForceDubLanguageDialog = false
            },
            onDismiss = { showForceDubLanguageDialog = false }
        )
    }

    LaunchedEffect(Unit) {
        runCatching { firstFocusRequester.requestFocus() }
    }
}

@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.GitHubContributor
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

private val SUPPORT_URL: String
    get() = BuildConfig.SUPPORT_URL
        .takeIf { it.isNotBlank() }
        ?: "https://ko-fi.com/pedropicoli"

@Composable
fun SupportersContributorsScreen(
    viewModel: SupportersContributorsViewModel = hiltViewModel(),
    onBackPress: () -> Unit = {}
) {
    val membershipActionFocusRequester = remember { FocusRequester() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val contributorFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var pendingContributorRestoreKey by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (uiState.selectedContributor != null) {
            pendingContributorRestoreKey = uiState.selectedContributor?.id
            viewModel.dismissContributorDetails()
        } else {
            onBackPress()
        }
    }

    LaunchedEffect(uiState.contributors) {
        contributorFocusRequesters.keys.retainAll(uiState.contributors.map { it.id }.toSet())
    }

    LaunchedEffect(uiState.selectedContributor, pendingContributorRestoreKey) {
        val key = pendingContributorRestoreKey ?: return@LaunchedEffect
        if (uiState.selectedContributor != null) return@LaunchedEffect
        contributorFocusRequesters[key]?.requestFocusAfterFrames()
        pendingContributorRestoreKey = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            SupporterMembershipPanel(
                supportUrl = SUPPORT_URL,
                actionFocusRequester = membershipActionFocusRequester,
                modifier = Modifier.weight(0.38f)
            )

            ContributorsContentPanel(
                uiState = uiState,
                leftFocusRequester = membershipActionFocusRequester,
                contributorFocusRequesters = contributorFocusRequesters,
                onRetryContributors = viewModel::retryContributors,
                onContributorClick = viewModel::onContributorSelected,
                modifier = Modifier.weight(0.62f)
            )
        }
    }

    uiState.selectedContributor?.let { contributor ->
        ContributorDetailsDialog(
            contributor = contributor,
            onDismiss = {
                pendingContributorRestoreKey = contributor.id
                viewModel.dismissContributorDetails()
            }
        )
    }
}

@Composable
private fun ContributorsContentPanel(
    uiState: SupportersContributorsUiState,
    leftFocusRequester: FocusRequester,
    contributorFocusRequesters: MutableMap<String, FocusRequester>,
    onRetryContributors: () -> Unit,
    onContributorClick: (GitHubContributor) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(28.dp))
            .background(NuvioTheme.colors.BackgroundElevated)
            .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, RoundedCornerShape(28.dp))
            .padding(NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.contributors_tab),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = NuvioTheme.colors.TextPrimary
            )
            Text(
                text = stringResource(R.string.about_supporters_contributors_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }

        when {
            uiState.isContributorsLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.contributors_loading),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }

            uiState.contributorsErrorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        Text(
                            text = stringResource(R.string.contributors_error_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = NuvioTheme.colors.TextPrimary
                        )
                        uiState.contributorsErrorMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = NuvioTheme.colors.TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                        Button(
                            onClick = onRetryContributors,
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioTheme.colors.BackgroundCard,
                                focusedContainerColor = NuvioTheme.colors.FocusBackground,
                                contentColor = NuvioTheme.colors.TextPrimary,
                                focusedContentColor = NuvioTheme.colors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Text(text = stringResource(R.string.action_retry))
                        }
                    }
                }
            }

            uiState.contributors.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.contributors_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }

            else -> {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(
                        items = uiState.contributors,
                        key = { it.id }
                    ) { contributor ->
                        val requester = contributorFocusRequesters.getOrPut(contributor.id) { FocusRequester() }
                        ContributorCard(
                            contributor = contributor,
                            focusRequester = requester,
                            leftFocusRequester = leftFocusRequester,
                            onClick = { onContributorClick(contributor) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorCard(
    contributor: GitHubContributor,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftFocusRequester
            }
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.BackgroundCard
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = RoundedCornerShape(22.dp)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(22.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(22.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = NuvioTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            PersonAvatar(
                label = contributor.name,
                avatarUrl = contributor.avatarUrl,
                modifier = Modifier.size(54.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    Text(
                        text = contributor.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    contributorRoleLabel(contributor.githubLogin ?: contributor.name)?.let { role ->
                        ContributorRoleBadge(role = role)
                    }
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
                Text(
                    text = stringResource(R.string.contributors_total_contributions, contributor.totalContributions),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = if (isFocused) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ContributorRoleBadge(role: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(NuvioTheme.colors.Background)
            .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, RoundedCornerShape(NuvioTheme.radii.sm))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = role,
            style = MaterialTheme.typography.labelSmall,
            color = NuvioTheme.colors.TextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PersonAvatar(
    label: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(avatarUrl)
            .crossfade(true)
            .build()
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(NuvioTheme.colors.Background)
            .border(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl.isNullOrBlank() || painter.state.collectAsState().value is AsyncImagePainter.State.Error) {
            Text(
                text = label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            Image(
                painter = painter,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun ContributorDetailsDialog(
    contributor: GitHubContributor,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(contributor.id) {
        primaryFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = contributor.name,
        width = 560.dp,
        suppressFirstKeyUp = false
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            PersonAvatar(
                label = contributor.name,
                avatarUrl = contributor.avatarUrl,
                modifier = Modifier.size(72.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                contributorRoleLabel(contributor.githubLogin ?: contributor.name)?.let { role ->
                    ContributorRoleBadge(role = role)
                }
                Text(
                    text = contributor.profileUrl ?: stringResource(R.string.contributors_profile_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary
                )
                Text(
                    text = stringResource(R.string.contributors_total_contributions, contributor.totalContributions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            Button(
                onClick = {
                    val profileUrl = contributor.profileUrl ?: return@Button
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl))
                        )
                    }
                },
                enabled = contributor.profileUrl != null,
                modifier = Modifier.focusRequester(primaryFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.Secondary,
                    focusedContainerColor = NuvioTheme.colors.SecondaryVariant,
                    contentColor = NuvioTheme.colors.OnSecondary,
                    focusedContentColor = NuvioTheme.colors.OnSecondaryVariant
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(text = stringResource(R.string.contributors_open_github))
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NuvioTheme.colors.BackgroundCard,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContentColor = NuvioTheme.colors.Primary
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(50))
            ) {
                Text(text = stringResource(R.string.action_close))
            }
        }
    }
}

@Composable
private fun contributorRoleLabel(login: String): String? = when (login.lowercase(Locale.ROOT)) {
    "pedropicoli" -> stringResource(R.string.contributor_role_creator)
    "milicevicivan" -> stringResource(R.string.contributor_role_translator)
    "tapframe" -> stringResource(R.string.contributor_role_maintainer)
    else -> null
}

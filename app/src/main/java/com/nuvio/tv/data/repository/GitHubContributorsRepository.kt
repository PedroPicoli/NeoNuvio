package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubContributorDto
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubContributor(
    val id: String,
    val name: String,
    val githubLogin: String?,
    val avatarUrl: String?,
    val profileUrl: String?,
    val totalContributions: Int,
    val tvContributions: Int = 0,
    val mobileContributions: Int = 0,
    val webContributions: Int = 0
)

@Singleton
class GitHubContributorsRepository @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val gitHubReleaseApi: GitHubReleaseApi
) {
    internal var targetOwner: String = BuildConfig.GITHUB_OWNER.takeIf { it.isNotBlank() } ?: "PedroPicoli"
    internal var targetRepo: String = BuildConfig.GITHUB_REPO.takeIf { it.isNotBlank() } ?: "NeoNuvio"

    suspend fun getContributors(): Result<List<GitHubContributor>> = runCatching {
        val owner = targetOwner
        val repo = targetRepo

        val response = gitHubReleaseApi.getContributors(owner = owner, repo = repo)
        if (!response.isSuccessful) {
            error(appContext.getString(com.nuvio.tv.R.string.contributors_error_api_http, response.code()))
        }

        val allContributors = response.body()
            .orEmpty()
            .mapNotNull { it.toContributor() }
            .toMutableList()

        val ownerIndex = allContributors.indexOfFirst { it.githubLogin.equals(owner, ignoreCase = true) }
        val ownerContributor = if (ownerIndex != -1) {
            allContributors.removeAt(ownerIndex)
        } else if (owner.equals("PedroPicoli", ignoreCase = true)) {
            GitHubContributor(
                id = owner,
                name = "Pedro Picoli",
                githubLogin = owner,
                avatarUrl = "https://avatars.githubusercontent.com/u/64288536?v=4",
                profileUrl = "https://github.com/$owner",
                totalContributions = 7
            )
        } else {
            null
        }

        val sortedOthers = allContributors.sortedWith(
            compareByDescending<GitHubContributor> { it.totalContributions }
                .thenBy { it.name.lowercase() }
        )

        if (ownerContributor != null) {
            listOf(ownerContributor) + sortedOthers
        } else {
            sortedOthers
        }
    }

    private fun GitHubContributorDto.toContributor(): GitHubContributor? {
        val loginName = login?.trim().orEmpty()
        val total = contributions ?: 0
        if (loginName.isBlank() || total <= 0) return null

        val profile = htmlUrl?.takeIf { it.isNotBlank() } ?: "https://github.com/$loginName"

        return GitHubContributor(
            id = loginName,
            name = loginName,
            githubLogin = loginName,
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
            profileUrl = profile,
            totalContributions = total
        )
    }
}

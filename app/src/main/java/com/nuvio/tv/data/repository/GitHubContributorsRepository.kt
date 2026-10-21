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

    suspend fun getContributors(): Result<List<GitHubContributor>> = runCatching {
        val owner = BuildConfig.GITHUB_OWNER.takeIf { it.isNotBlank() } ?: "PedroPicoli"
        val repo = BuildConfig.GITHUB_REPO.takeIf { it.isNotBlank() } ?: "NeoNuvio"

        val response = gitHubReleaseApi.getContributors(owner = owner, repo = repo)
        if (!response.isSuccessful) {
            error(appContext.getString(com.nuvio.tv.R.string.contributors_error_api_http, response.code()))
        }

        response.body()
            .orEmpty()
            .mapNotNull { it.toContributor() }
            .sortedWith(
                compareByDescending<GitHubContributor> { it.totalContributions }
                    .thenBy { it.name.lowercase() }
            )
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

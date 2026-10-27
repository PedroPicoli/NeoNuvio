package com.nuvio.tv.updater

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import com.nuvio.tv.updater.model.AppUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal class NoEligibleUpdateException(message: String = "No compatible APK release found") :
    IllegalStateException(message)

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi,
    @ApplicationContext private val context: Context
) {

    suspend fun getLatestUpdate(channel: UpdateChannel = UpdateChannel.STABLE): Result<AppUpdate> {
        return runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val releases: List<GitHubReleaseDto> = try {
                val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                if (response.isSuccessful && response.body() != null) {
                    listOf(response.body()!!)
                } else {
                    val allResponse = gitHubReleaseApi.getReleases(owner = owner, repo = repo)
                    if (allResponse.isSuccessful) {
                        allResponse.body().orEmpty()
                    } else if (allResponse.code() == 404 || response.code() == 404) {
                        throw NoEligibleUpdateException()
                    } else {
                        error("GitHub API error: ${allResponse.code()}")
                    }
                }
            } catch (e: NoEligibleUpdateException) {
                throw e
            } catch (e: Exception) {
                if (e.message?.contains("404") == true) {
                    throw NoEligibleUpdateException()
                }
                throw e
            }

            if (releases.isEmpty()) {
                throw NoEligibleUpdateException()
            }

            val releaseWithAsset = ReleaseSelector
                .eligibleReleases(releases)
                .firstNotNullOfOrNull { release ->
                    AbiSelector.chooseBestApkAsset(release.assets, context)?.let { asset ->
                        release to asset
                    }
                }
                ?: throw NoEligibleUpdateException()
            val (dto, asset) = releaseWithAsset

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            val title = dto.name?.takeIf { it.isNotBlank() } ?: tag
            val cleanVersion = VersionUtils.extractVersion(tag)
                ?: VersionUtils.extractVersion(title)
                ?: VersionUtils.normalize(tag)

            AppUpdate(
                tag = tag,
                title = title,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size,
                version = cleanVersion
            )
        }
    }
}


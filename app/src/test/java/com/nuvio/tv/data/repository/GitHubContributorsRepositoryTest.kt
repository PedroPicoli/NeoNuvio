package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubContributorDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubContributorsRepositoryTest {
    private val context = io.mockk.mockk<Context>(relaxed = true)

    @Test
    fun `uses contributions from github api and sorts descending`() = runTest {
        val repository = GitHubContributorsRepository(
            appContext = context,
            gitHubReleaseApi = FakeGitHubReleaseApi(
                contributorsResponse = Response.success(
                    listOf(
                        GitHubContributorDto(login = "bob", contributions = 3),
                        GitHubContributorDto(login = "alice", contributions = 12),
                        GitHubContributorDto(login = "charlie", contributions = 4)
                    )
                )
            )
        )

        val result = repository.getContributors()

        assertTrue(result.isSuccess)
        val contributors = result.getOrThrow()
        assertEquals(listOf("alice", "charlie", "bob"), contributors.map { it.name })
        assertEquals(12, contributors.first().totalContributions)
    }

    @Test
    fun `filters blank names and non-positive totals`() = runTest {
        val repository = GitHubContributorsRepository(
            appContext = context,
            gitHubReleaseApi = FakeGitHubReleaseApi(
                contributorsResponse = Response.success(
                    listOf(
                        GitHubContributorDto(login = "", contributions = 10),
                        GitHubContributorDto(login = "zero", contributions = 0),
                        GitHubContributorDto(login = "alice", contributions = 2)
                    )
                )
            )
        )

        val contributors = repository.getContributors().getOrThrow()

        assertEquals(1, contributors.size)
        assertEquals("alice", contributors.first().name)
    }

    @Test
    fun `fails when github api fails`() = runTest {
        val repository = GitHubContributorsRepository(
            appContext = context,
            gitHubReleaseApi = FakeGitHubReleaseApi(
                contributorsResponse = errorResponse(500)
            )
        )

        val result = repository.getContributors()

        assertTrue(result.isFailure)
    }

    private fun errorResponse(code: Int): Response<List<GitHubContributorDto>> {
        return Response.error(
            code,
            "{}".toResponseBody("application/json".toMediaType())
        )
    }

    private class FakeGitHubReleaseApi(
        private val contributorsResponse: Response<List<GitHubContributorDto>>
    ) : GitHubReleaseApi {

        override suspend fun getLatestRelease(owner: String, repo: String): Response<GitHubReleaseDto> {
            throw NotImplementedError()
        }

        override suspend fun getReleases(owner: String, repo: String, perPage: Int): Response<List<GitHubReleaseDto>> {
            throw NotImplementedError()
        }

        override suspend fun getContributors(owner: String, repo: String): Response<List<GitHubContributorDto>> {
            return contributorsResponse
        }
    }
}

package com.nuvio.tv.updater

import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseSelectorTest {
    @Test
    fun `all published releases are sorted by semantic version`() {
        val releases = listOf(
            release("1.0.0"),
            release("1.2.0"),
            release("1.0.1"),
            release("1.1.0")
        )

        val selected = ReleaseSelector.eligibleReleases(releases)

        assertEquals(listOf("1.2.0", "1.1.0", "1.0.1", "1.0.0"), selected.map { it.tagName })
    }

    @Test
    fun `releases with v prefix are parsed and sorted correctly`() {
        val releases = listOf(
            release("v1.0.0"),
            release("v1.2.0"),
            release("v1.1.0")
        )

        val selected = ReleaseSelector.eligibleReleases(releases)

        assertEquals(listOf("v1.2.0", "v1.1.0", "v1.0.0"), selected.map { it.tagName })
    }

    @Test
    fun `drafts and invalid tags are excluded`() {
        val releases = listOf(
            release("1.2.0", draft = true),
            release("nightly"),
            release("1.1.0")
        )

        val selected = ReleaseSelector.eligibleReleases(releases)

        assertEquals(listOf("1.1.0"), selected.map { it.tagName })
    }

    private fun release(
        tag: String,
        name: String = tag,
        draft: Boolean = false,
        prerelease: Boolean = false
    ) = GitHubReleaseDto(
        tagName = tag,
        name = name,
        draft = draft,
        prerelease = prerelease
    )
}

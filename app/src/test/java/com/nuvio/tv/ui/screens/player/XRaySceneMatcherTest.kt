package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.MetaCastMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XRaySceneMatcherTest {

    private val cast = listOf(
        MetaCastMember(name = "Alaqua Cox", character = "Maya Lopez / Echo", photo = "https://image.tmdb.org/t/p/w185/maya.jpg"),
        MetaCastMember(name = "Charlie Cox", character = "Matt Murdock / Demolidor", photo = "https://image.tmdb.org/t/p/w185/matt.jpg"),
        MetaCastMember(name = "Vincent D'Onofrio", character = "Wilson Fisk / Kingpin", photo = "https://image.tmdb.org/t/p/w185/fisk.jpg"),
        MetaCastMember(name = "Chaske Spencer", character = "Henry Lopez", photo = null)
    )

    @Test
    fun `test exact speaker detection identifies character speaking now`() {
        val cues = listOf(
            SubtitleSyncCue(startTimeMs = 10_000L, endTimeMs = 14_000L, text = "[Maya]: O que você está fazendo aqui?"),
            SubtitleSyncCue(startTimeMs = 15_000L, endTimeMs = 18_000L, text = "Fisk: Eu vim conversar.")
        )

        val result = XRaySceneMatcher.matchSceneCast(
            positionMs = 12_000L,
            castMembers = cast,
            cues = cues
        )

        assertEquals("Alaqua Cox", result[0].member.name)
        assertTrue(result[0].isSpeakingNow)
        assertTrue(result[0].isInScene)
    }

    @Test
    fun `test recent dialogue identifies characters in scene`() {
        val cues = listOf(
            SubtitleSyncCue(startTimeMs = 2_000L, endTimeMs = 6_000L, text = "Wilson Fisk: Maya, escute com atenção."),
            SubtitleSyncCue(startTimeMs = 7_000L, endTimeMs = 9_000L, text = "Eu não tenho medo de você.")
        )

        val result = XRaySceneMatcher.matchSceneCast(
            positionMs = 10_000L,
            castMembers = cast,
            cues = cues
        )

        // Wilson Fisk e Maya foram mencionados / falaram na janela recente (últimos 10s)
        val inSceneNames = result.filter { it.isInScene }.map { it.member.name }
        assertTrue(inSceneNames.contains("Vincent D'Onofrio"))
        assertTrue(inSceneNames.contains("Alaqua Cox"))

        // Charlie Cox não estava na cena
        val charlie = result.first { it.member.name == "Charlie Cox" }
        assertFalse(charlie.isInScene)
    }

    @Test
    fun `test fallback keeps all cast members available`() {
        val result = XRaySceneMatcher.matchSceneCast(
            positionMs = 50_000L,
            castMembers = cast,
            cues = emptyList()
        )

        assertEquals(4, result.size)
        assertEquals("Alaqua Cox", result[0].member.name)
        assertFalse(result[0].isInScene)
    }

    @Test
    fun `test two speakers in dialogue cue both detected as speaking`() {
        val cues = listOf(
            SubtitleSyncCue(
                startTimeMs = 20_000L,
                endTimeMs = 24_000L,
                text = "- Maya, você viu aquilo?\n- Vi sim, Fisk."
            )
        )

        val result = XRaySceneMatcher.matchSceneCast(
            positionMs = 22_000L,
            castMembers = cast,
            cues = cues
        )

        val speaking = result.filter { it.isSpeakingNow }.map { it.member.name }
        assertTrue("Maya should be speaking", speaking.contains("Alaqua Cox"))
        assertTrue("Fisk should be speaking", speaking.contains("Vincent D'Onofrio"))
    }

    @Test
    fun `test two speakers in dialogue without nominal mentions detects top cast in scene without false speaking tag`() {
        val cues = listOf(
            SubtitleSyncCue(
                startTimeMs = 30_000L,
                endTimeMs = 35_000L,
                text = "- O que foi aquilo que caiu do céu?\n- Não tenho ideia, vamos investigar!"
            )
        )

        val result = XRaySceneMatcher.matchSceneCast(
            positionMs = 32_000L,
            castMembers = cast,
            cues = cues
        )

        val inScene = result.filter { it.isInScene }.map { it.member.name }
        // Os dois atores principais da obra são exibidos como participantes da cena
        assertTrue(inScene.contains("Alaqua Cox"))
        assertTrue(inScene.contains("Charlie Cox"))

        // Mas sem menção nominal direta, nenhum recebe falsamente o selo isSpeakingNow
        val speaking = result.filter { it.isSpeakingNow }
        assertEquals(0, speaking.size)
    }

    @Test
    fun `test backend cues work seamlessly when no on-screen subtitle is present`() {
        // Simula usuário assistindo dublado ou sem legendas na tela: activeSubtitleText é nulo/vazio
        val backendCues = listOf(
            SubtitleSyncCue(
                startTimeMs = 40_000L,
                endTimeMs = 44_000L,
                text = "Wilson Fisk: Esta cidade precisa de ordem."
            )
        )

        val result = XRaySceneMatcher.matchSceneCast(
            positionMs = 42_000L,
            castMembers = cast,
            cues = backendCues,
            activeSubtitleText = null
        )

        val speaking = result.filter { it.isSpeakingNow }.map { it.member.name }
        assertTrue(speaking.contains("Vincent D'Onofrio"))
        assertEquals("Vincent D'Onofrio", result[0].member.name)
        assertTrue(result[0].isInScene)
    }

    @Test
    fun `test character speaking far in the future is NOT detected in current scene`() {
        // Personagem fala muito tempo depois (aos 50 segundos)
        val cues = listOf(
            SubtitleSyncCue(
                startTimeMs = 50_000L,
                endTimeMs = 55_000L,
                text = "Charlie Cox: Eu sou o Demolidor."
            )
        )

        // Pausado aos 10 segundos
        val result = XRaySceneMatcher.matchSceneCast(
            positionMs = 10_000L,
            castMembers = cast,
            cues = cues
        )

        val charlie = result.first { it.member.name == "Charlie Cox" }
        assertFalse("Personagem que só fala no futuro não pode estar falando agora", charlie.isSpeakingNow)
        assertFalse("Personagem que só fala no futuro não pode estar na cena recente", charlie.isInScene)
    }

    @Test
    fun `test subtitle delay compensation selects correct speaker when effective position is used`() {
        val cues = listOf(
            SubtitleSyncCue(startTimeMs = 10_000L, endTimeMs = 14_000L, text = "Wilson Fisk: Hora de agir."),
            SubtitleSyncCue(startTimeMs = 25_000L, endTimeMs = 28_000L, text = "Charlie Cox: Cheguei tarde.")
        )

        // Vídeo está em 25.000ms, mas a legenda está com delay de 15.000ms
        // A posição real na legenda é: 25.000ms - 15.000ms = 10.000ms
        val videoPos = 25_000L
        val subtitleDelayMs = 15_000
        val effectiveSubPos = (videoPos - subtitleDelayMs).coerceAtLeast(0L) // 10_000ms

        val result = XRaySceneMatcher.matchSceneCast(
            positionMs = effectiveSubPos,
            castMembers = cast,
            cues = cues
        )

        // Quem está falando na posição efetiva (10s) é Wilson Fisk
        val fisk = result.first { it.member.name == "Vincent D'Onofrio" }
        assertTrue("Fisk deve estar falando na posição efetiva da legenda", fisk.isSpeakingNow)

        // Charlie Cox (que só fala aos 25s da legenda) não deve estar falando
        val charlie = result.first { it.member.name == "Charlie Cox" }
        assertFalse("Charlie só falará aos 25s na legenda e não pode estar falando agora", charlie.isSpeakingNow)
    }
}

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.R
import java.util.Locale

@Composable
internal fun ForceDubDebugStatsOverlay(
    info: ForceDubDebugInfo,
    modifier: Modifier = Modifier
) {
    val isFallbackOriginal = info.isFallbackToOriginal ||
        info.method.contains("Nativo", ignoreCase = true) ||
        info.method.contains("Original", ignoreCase = true) ||
        info.logicApplied.contains("TIMEOUT", ignoreCase = true) ||
        info.logicApplied.contains("FALLBACK ÁUDIO ORIGINAL", ignoreCase = true)

    val statusColor = when {
        isFallbackOriginal -> Color(0xFFE57373) // Vermelho suave
        info.isActive && info.confidence > 0.50f -> Color(0xFF4CAF50) // Verde (sincronizado)
        info.isActive && info.appliedOffsetMs != 0L -> Color(0xFF00E676) // Verde brilhante
        info.isActive -> Color(0xFFFFB300) // Amarelo (0ms)
        info.isLoading -> Color(0xFFFFB300) // Amarelo
        info.isEnabled -> Color(0xFF29B6F6) // Azul claro
        else -> Color(0xFF9E9E9E) // Cinza
    }

    val statusText = when {
        isFallbackOriginal -> "INATIVO (FALLBACK ÁUDIO ORIGINAL - TIMEOUT)"
        info.isActive && info.confidence > 0.50f -> "ATIVO (ÁUDIO SINCRONIZADO)"
        info.isActive && info.appliedOffsetMs != 0L -> "ATIVO (OFFSET: ${if (info.appliedOffsetMs > 0) "+" else ""}${info.appliedOffsetMs}ms)"
        info.isActive -> "ATIVO (OFFSET 0ms)"
        info.isLoading -> "CARREGANDO / AFINANDO..."
        info.isEnabled -> "HABILITADO (AGUARDANDO)"
        else -> "DESATIVADO"
    }

    Column(
        modifier = modifier
            .widthIn(min = 380.dp, max = 720.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xF00A0C14))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Cabeçalho
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FORÇAR DUBLAGEM • STATS",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF81D4FA)
            )
            Text(
                text = statusText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        // Linhas de Estatística
        DebugStatRow(label = "Motor:", value = info.engine)
        DebugStatRow(label = "Idioma:", value = info.targetLanguage)
        DebugStatRow(
            label = "Offset:",
            value = "${if (info.appliedOffsetMs >= 0) "+" else ""}${info.appliedOffsetMs} ms",
            valueColor = if (info.appliedOffsetMs != 0L) Color(0xFF00E676) else Color(0xFFFFB300)
        )
        DebugStatRow(
            label = "Confiança:",
            value = String.format(Locale.US, "%.0f%%", info.confidence * 100),
            valueColor = if (info.confidence >= 0.85f) Color(0xFF00E676) else Color(0xFFFFB300)
        )
        DebugStatRow(label = "Origem:", value = if (info.fromCache) "Cache (Instantâneo)" else "Avaliação em Tempo Real")
        if (info.winningLayer.isNotBlank()) {
            DebugStatRow(label = "Camada:", value = info.winningLayer, valueColor = Color(0xFF64B5F6), maxLines = 4)
        }
        if (info.tVideoMs != null || info.tAudioMs != null) {
            val tvStr = info.tVideoMs?.let { "${it}ms" } ?: "0ms"
            val taStr = info.tAudioMs?.let { "${it}ms" } ?: "0ms"
            DebugStatRow(
                label = "T_vid/aud:",
                value = "T_vídeo=$tvStr | T_áudio=$taStr (Δ=${info.appliedOffsetMs}ms)",
                valueColor = Color(0xFF80E27E),
                maxLines = 2
            )
        }
        if (info.acousticNccScore != null && info.acousticNccScore > 0f) {
            DebugStatRow(
                label = "NCC M&E:",
                value = String.format(Locale.US, "%.1f%% (Correlação 10s-45s)", info.acousticNccScore),
                valueColor = if (info.acousticNccScore >= 60f) Color(0xFF00E676) else Color(0xFFFFB300)
            )
        }
        DebugStatRow(label = "Método:", value = info.method, valueColor = Color(0xFFFFD54F), maxLines = 4)
        DebugStatRow(label = "Lógica:", value = info.logicApplied.ifBlank { "Nenhuma" }, maxLines = 8)

        if (info.audioStreamTitle.isNotBlank() || info.audioStreamName.isNotBlank()) {
            val audioName = listOfNotNull(info.audioStreamName.takeIf { it.isNotBlank() }, info.audioStreamTitle.takeIf { it.isNotBlank() }).joinToString(" - ")
            DebugStatRow(
                label = "Fonte Dub:",
                value = audioName,
                maxLines = 4,
                valueColor = Color(0xFF80D8FF)
            )
        }

        if (info.videoStreamTitle.isNotBlank()) {
            DebugStatRow(
                label = "Fonte Vídeo:",
                value = info.videoStreamTitle,
                maxLines = 4,
                valueColor = Color(0xFFB0BEC5)
            )
        }

        if (info.engineeringSummary.isNotBlank()) {
            DebugStatRow(
                label = "Engenharia:",
                value = info.engineeringSummary,
                maxLines = 6,
                valueColor = Color(0xFFCE93D8)
            )
        }

        if (info.diagnosticFilePath.isNotBlank()) {
            DebugStatRow(
                label = "Dump:",
                value = info.diagnosticFilePath,
                maxLines = 2,
                valueColor = Color(0xFFFFD54F)
            )
        }
    }
}

@Composable
private fun DebugStatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color(0xFFF0F0F0),
    maxLines: Int = 1
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.width(95.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF8E8E93)
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Normal,
            color = valueColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

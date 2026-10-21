package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Anel de carregamento e efeito de hold desativados e removidos por completo conforme solicitação do usuário.
 */
@Composable
fun LongPressSelectionRing(
    isPressing: Boolean = false,
    onLongPressComplete: () -> Unit = {},
    modifier: Modifier = Modifier,
    ringSize: Dp = 44.dp,
    strokeWidth: Dp = 4.dp,
    ringColor: Color = Color.White,
    trackColor: Color = Color.White.copy(alpha = 0.28f),
    holdDurationMs: Int = 700,
    alignment: Alignment = Alignment.BottomEnd
) {
    // Efeito e visual de anel removidos por completo
}

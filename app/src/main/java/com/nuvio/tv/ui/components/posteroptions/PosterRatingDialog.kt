package com.nuvio.tv.ui.components.posteroptions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.random.Random

private val STAR_RATINGS = (1..10).toList()

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterRatingDialog(
    title: String,
    currentRating: Int?,
    onSelectRating: (Int) -> Unit,
    onRemoveRating: () -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var animatingScore by remember { mutableStateOf<Int?>(null) }
    var isHandlingSelection by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val focusRequesters = remember { STAR_RATINGS.associateWith { FocusRequester() } }

    LaunchedEffect(Unit) {
        val targetScore = currentRating ?: 10
        val targetIndex = STAR_RATINGS.indexOf(targetScore).coerceAtLeast(0)
        listState.scrollToItem((targetIndex - 2).coerceAtLeast(0))
        delay(50)
        focusRequesters[targetScore]?.requestFocus()
    }

    NuvioDialog(
        onDismiss = {
            if (!isHandlingSelection) onDismiss()
        },
        title = title,
        width = 460.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp),
            contentAlignment = Alignment.Center
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(STAR_RATINGS, key = { it }) { score ->
                    val isCurrent = currentRating == score
                    val requester = focusRequesters[score] ?: remember { FocusRequester() }

                    Button(
                        onClick = {
                            if (isHandlingSelection) return@Button
                            if (isCurrent) {
                                // Clicar na mesma nota remove a avaliação
                                isHandlingSelection = true
                                onRemoveRating()
                                onDismiss()
                            } else {
                                // Nova nota selecionada
                                when (score) {
                                    10 -> {
                                        isHandlingSelection = true
                                        animatingScore = 10
                                        coroutineScope.launch {
                                            delay(1400)
                                            onSelectRating(10)
                                            onDismiss()
                                        }
                                    }
                                    8, 9 -> {
                                        isHandlingSelection = true
                                        animatingScore = score
                                        coroutineScope.launch {
                                            delay(1000)
                                            onSelectRating(score)
                                            onDismiss()
                                        }
                                    }
                                    in 1..3 -> {
                                        isHandlingSelection = true
                                        animatingScore = score
                                        coroutineScope.launch {
                                            delay(1000)
                                            onSelectRating(score)
                                            onDismiss()
                                        }
                                    }
                                    else -> {
                                        // 4 a 7 sem animações
                                        isHandlingSelection = true
                                        onSelectRating(score)
                                        onDismiss()
                                    }
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(requester),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            border = if (isCurrent) {
                                Border(
                                    border = androidx.compose.foundation.BorderStroke(
                                        2.dp,
                                        Color(0xFFFFD700)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                            } else {
                                Border.None
                            },
                            focusedBorder = Border(
                                border = androidx.compose.foundation.BorderStroke(
                                    2.dp,
                                    Color(0xFFFFD700)
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = if (isCurrent) Color(0x33FFD700) else NuvioTheme.colors.BackgroundCard,
                            contentColor = Color(0xFFFFD700),
                            focusedContainerColor = if (isCurrent) Color(0x55FFD700) else NuvioTheme.colors.FocusBackground,
                            focusedContentColor = Color(0xFFFFD700)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val stars = remember(score) { "⭐".repeat(score) }
                            Text(
                                text = stars,
                                fontSize = if (score > 7) 15.sp else 18.sp,
                                maxLines = 1,
                                letterSpacing = 2.sp
                            )
                        }
                    }
                }
            }

            // Camada de animação de feedback
            when (animatingScore) {
                10 -> RatingCelebrationTenAnimation()
                8, 9 -> RatingPositiveEightNineAnimation(animatingScore ?: 8)
                in 1..3 -> RatingNegativeSadAnimation(animatingScore ?: 1)
            }
        }
    }
}

/**
 * Animação espetacular para nota 10 estrelas (Golden Celebration / Fireworks)
 */
@Composable
private fun RatingCelebrationTenAnimation() {
    val scale = remember { Animatable(0.7f) }
    val glowAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(
                targetValue = 1.25f,
                animationSpec = tween(durationMillis = 400, easing = LinearEasing)
            )
            scale.animateTo(
                targetValue = 1.0f,
                animationSpec = tween(durationMillis = 300, easing = LinearEasing)
            )
        }
        launch {
            glowAlpha.animateTo(1f, tween(300))
            delay(600)
            glowAlpha.animateTo(0f, tween(400))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE60A0A0E))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Efeito de partículas/estrelas brilhantes
        val particles = remember {
            List(24) {
                ParticleData(
                    offsetX = (Random.nextFloat() * 320f - 160f),
                    offsetY = (Random.nextFloat() * 260f - 130f),
                    char = listOf("✨", "⭐", "🌟", "💫", "🏆").random(),
                    size = Random.nextInt(16, 28).sp
                )
            }
        }

        particles.forEach { p ->
            val particleAlpha by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 800),
                label = "particleAlpha"
            )
            Text(
                text = p.char,
                fontSize = p.size,
                modifier = Modifier
                    .offset { IntOffset(p.offsetX.roundToInt(), p.offsetY.roundToInt()) }
                    .alpha(particleAlpha * glowAlpha.value)
            )
        }

        // Centro brilhante
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.scale(scale.value)
        ) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFFFFD700), Color(0x33FFA000), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🌟",
                    fontSize = 48.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "⭐⭐⭐⭐⭐⭐⭐⭐⭐⭐",
                fontSize = 17.sp,
                letterSpacing = 2.sp
            )
        }
    }
}

/**
 * Animação moderada e positiva para notas 8 e 9 estrelas (pulso suave verde/âmbar)
 */
@Composable
private fun RatingPositiveEightNineAnimation(score: Int) {
    val scale = remember { Animatable(0.9f) }
    val overlayAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            scale.animateTo(1.1f, tween(300))
            scale.animateTo(1.0f, tween(250))
        }
        launch {
            overlayAlpha.animateTo(1f, tween(250))
            delay(500)
            overlayAlpha.animateTo(0f, tween(250))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD90B1015))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.scale(scale.value)
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x804CAF50), Color(0x332E7D32), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✨",
                    fontSize = 40.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "⭐".repeat(score),
                fontSize = 17.sp,
                letterSpacing = 2.sp
            )
        }
    }
}

/**
 * Animação triste / desagradável para notas baixas de 1 a 3 estrelas (shake horizontal e tom avermelhado)
 */
@Composable
private fun RatingNegativeSadAnimation(score: Int) {
    val shakeOffset = remember { Animatable(0f) }
    val overlayAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 600
                    0f at 0
                    -16f at 50
                    16f at 100
                    -12f at 180
                    12f at 260
                    -8f at 340
                    8f at 420
                    -4f at 500
                    0f at 600
                }
            )
        }
        launch {
            overlayAlpha.animateTo(1f, tween(200))
            delay(500)
            overlayAlpha.animateTo(0f, tween(300))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE61A0808))
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0x66D32F2F), Color(0x22B71C1C), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "💔",
                    fontSize = 38.sp
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "⭐".repeat(score),
                fontSize = 20.sp,
                letterSpacing = 3.sp
            )
        }
    }
}

private data class ParticleData(
    val offsetX: Float,
    val offsetY: Float,
    val char: String,
    val size: androidx.compose.ui.unit.TextUnit
)

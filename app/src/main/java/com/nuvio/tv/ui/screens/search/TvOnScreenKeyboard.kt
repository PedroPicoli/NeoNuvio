package com.nuvio.tv.ui.screens.search

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Teclado em tela para TV fiel ao Better-Nuvio (busca.c / teclado.c):
 * - Largura compacta: 6 colunas x 38dp + 5 vãos x 4dp = 248dp
 * - Altura das teclas: 30dp
 * - Efeito Zoom/Destaque: ao focar em qualquer tecla, ela aumenta de tamanho (scale 1.22f) e ganha z-index
 * - Linha de controle: [Espaço universal na base], [⌫ Backspace tag], [× Limpar]
 * - Caracteres em repouso: sem fundo, branco puro, BOLD grande (20sp ExtraBold)
 * - Caracteres em foco: retângulo branco brilhante com texto preto em negrito
 * - Retorno inteligente: onNavigateRight informa a linha do teclado (0 a 6) para mapear
 *   com precisão a altura correspondente da grade de catálogo à direita.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvOnScreenKeyboard(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateRight: (keyboardRow: Int) -> Unit = {},
    onNavigateUp: () -> Unit = {},
    onNavigateDown: () -> Unit = {},
    firstKeyFocusRequester: FocusRequester? = null,
    keyFocusRequesters: Map<Char, FocusRequester> = emptyMap(),
    clearKeyFocusRequester: FocusRequester? = null,
    onKeyFocused: ((FocusRequester) -> Unit)? = null,
    spaceKeyFocusRequester: FocusRequester? = null,
    backspaceKeyFocusRequester: FocusRequester? = null
) {
    val charRows = remember {
        listOf(
            listOf('a', 'b', 'c', 'd', 'e', 'f'),
            listOf('g', 'h', 'i', 'j', 'k', 'l'),
            listOf('m', 'n', 'o', 'p', 'q', 'r'),
            listOf('s', 't', 'u', 'v', 'w', 'x'),
            listOf('y', 'z', '0', '1', '2', '3'),
            listOf('4', '5', '6', '7', '8', '9')
        )
    }

    val keyWidth = 38.dp
    val keyHeight = 30.dp
    val keyGap = 4.dp
    val totalWidth = 248.dp

    Column(
        modifier = modifier.width(totalWidth),
        verticalArrangement = Arrangement.spacedBy(keyGap)
    ) {
        // Linha 0 (Controle): [Espaço], [⌫ Apagar], [× Limpar]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(keyGap)
        ) {
            val spaceRequester = spaceKeyFocusRequester ?: firstKeyFocusRequester

            // Tecla Espaço: Ícone universal de barra de espaço na parte inferior (⌊_______⌋)
            TvKeyControl(
                onClick = {
                    if (query.isNotEmpty() && !query.endsWith(" ")) {
                        onQueryChanged("$query ")
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(keyHeight)
                    .then(if (spaceRequester != null) Modifier.focusRequester(spaceRequester) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused && spaceRequester != null) {
                            onKeyFocused?.invoke(spaceRequester)
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                            if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                                onNavigateUp()
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
            ) { isFocused ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 6.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    UniversalSpaceIcon(isFocused = isFocused)
                }
            }

            // Tecla Apagar: ícone backspace tag clássico
            TvKeyControl(
                onClick = {
                    if (query.isNotEmpty()) {
                        onQueryChanged(query.dropLast(1))
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(keyHeight)
                    .then(if (backspaceKeyFocusRequester != null) Modifier.focusRequester(backspaceKeyFocusRequester) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused && backspaceKeyFocusRequester != null) {
                            onKeyFocused?.invoke(backspaceKeyFocusRequester)
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                            if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                                onNavigateUp()
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
            ) { isFocused ->
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "apagar",
                    tint = if (isFocused) Color.Black else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Tecla Limpar: símbolo "×"
            TvKeyControl(
                onClick = { onQueryChanged("") },
                modifier = Modifier
                    .weight(1f)
                    .height(keyHeight)
                    .then(if (clearKeyFocusRequester != null) Modifier.focusRequester(clearKeyFocusRequester) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused && clearKeyFocusRequester != null) {
                            onKeyFocused?.invoke(clearKeyFocusRequester)
                        }
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                            if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                                onNavigateUp()
                                return@onPreviewKeyEvent true
                            }
                            if (event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                                if (clearKeyFocusRequester != null) {
                                    onKeyFocused?.invoke(clearKeyFocusRequester)
                                }
                                onNavigateRight(0)
                                return@onPreviewKeyEvent true
                            }
                        }
                        false
                    }
            ) { isFocused ->
                Text(
                    text = "×",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp
                    ),
                    color = if (isFocused) Color.Black else Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }

        // 6 Fileiras de letras e números
        charRows.forEachIndexed { rowIndex, rowChars ->
            val isLastRow = rowIndex == charRows.lastIndex
            val keyboardRowIndex = rowIndex + 1 // 1 a 6

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(keyGap)
            ) {
                rowChars.forEachIndexed { colIndex, char ->
                    val isLastColumn = colIndex == rowChars.lastIndex
                    val customRequester = keyFocusRequesters[char]

                    TvCharKey(
                        char = char,
                        keyWidth = keyWidth,
                        keyHeight = keyHeight,
                        onClick = { onQueryChanged("$query$char") },
                        focusRequester = customRequester,
                        onFocused = {
                            if (customRequester != null) {
                                onKeyFocused?.invoke(customRequester)
                            }
                        },
                        modifier = Modifier
                            .size(width = keyWidth, height = keyHeight)
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                                    if (isLastColumn && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                                        if (customRequester != null) {
                                            onKeyFocused?.invoke(customRequester)
                                        }
                                        onNavigateRight(keyboardRowIndex)
                                        return@onPreviewKeyEvent true
                                    }
                                    if (isLastRow && event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                                        onNavigateDown()
                                        return@onPreviewKeyEvent true
                                    }
                                }
                                false
                            }
                    )
                }
            }
        }
    }
}

/**
 * Desenha o ícone padrão universal de tecla de espaço:
 * Uma barra horizontal inferior com duas hastes verticais nas extremidades (Open Box: ⌊_______⌋).
 */
@Composable
private fun UniversalSpaceIcon(
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (isFocused) Color.Black else Color.White
    Box(
        modifier = modifier
            .width(22.dp)
            .height(7.dp)
    ) {
        // Haste vertical esquerda
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .width(2.5.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
        // Linha horizontal de base
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
        // Haste vertical direita
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(2.5.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
    }
}

@Composable
private fun TvCharKey(
    char: Char,
    keyWidth: androidx.compose.ui.unit.Dp,
    keyHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.22f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "charKeyScale"
    )

    Box(
        modifier = modifier
            .zIndex(if (isFocused) 10f else 1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { 
                isFocused = it.isFocused 
                if (it.isFocused) {
                    onFocused?.invoke()
                }
            }
            .clip(RoundedCornerShape(7.dp))
            .background(if (isFocused) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (event.nativeKeyEvent.keyCode in listOf(
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                            AndroidKeyEvent.KEYCODE_ENTER,
                            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                        )
                    ) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = char.toString(),
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            ),
            color = if (isFocused) Color.Black else Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TvKeyControl(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (isFocused: Boolean) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "controlKeyScale"
    )

    Box(
        modifier = modifier
            .zIndex(if (isFocused) 10f else 1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (isFocused) Color.White else Color(0xFF282A31)
            )
            .clickable(onClick = onClick)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (event.nativeKeyEvent.keyCode in listOf(
                            AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                            AndroidKeyEvent.KEYCODE_ENTER,
                            AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                        )
                    ) {
                        onClick()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusable(),
        contentAlignment = Alignment.Center
    ) {
        content(isFocused)
    }
}

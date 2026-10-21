package com.nuvio.tv.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor de Cor Viva / Dinâmica Imersiva portado 1:1 do Better-Nuvio (corviva.c / corviva.h).
 *
 * Princípios do Better-Nuvio:
 * 1. Amostragem em grade de 32x18 pontos diretamente da arte/backdrop em exibição.
 * 2. Quantização por matiz e brilho em 24 baldes, com desconto de 12% em tons de pele
 *    (para rostos não dominarem sobre roupas/marcas).
 * 3. Conversão e cálculo estrito em OKLab / OKLCh:
 *    - Limites de luminância L em [0.50, 0.68] e croma C em [0.10, 0.24] (+15% de realce).
 *    - Garantia WCAG: contraste com texto branco >= 3.2:1 e contraste com fundo escuro >= 3.0:1.
 *    - Fundo base escuro (#0D0D0D) sutilmente tingido com o matiz da arte (L=0.175, C max 0.032).
 *    - Superfícies de destaque em degradê de 3 paradas em OKLCh.
 * 4. Ao navegar fora de Home/Detalhes/Player (Ajustes, Biblioteca, Busca), a cor permanece
 *    a do último título em cena, sem piscar.
 */
object CorVivaEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val paletteCache = object : android.util.LruCache<String, ThemeColorPalette>(60) {}

    // Paleta padrão inicial antes do primeiro título ser carregado
    val DefaultImmersivePalette = ThemeColorPalette(
        secondary = Color(0xFF42A5F5),
        secondaryVariant = Color(0xFF1E88E5),
        onSecondary = Color.White,
        onSecondaryVariant = Color.White,
        accentGradient = listOf(Color(0xFF64B5F6), Color(0xFF42A5F5), Color(0xFF1976D2)),
        focusRing = Color(0xFF64B5F6),
        focusRingGradient = listOf(Color(0xFF90CAF9), Color(0xFF42A5F5), Color(0xFF1976D2)),
        focusBackground = Color(0xFF182232),
        background = Color(0xFF0D0D0D),
        backgroundElevated = Color(0xFF141416),
        backgroundCard = Color(0xFF1C1D21),
        surface = Color(0xFF16171A),
        surfaceVariant = Color(0xFF202227),
        panel = Color(0xFF141416),
        overlay = Color(0xD90D0D0D),
        field = Color(0xFF1C1D21),
        menu = Color(0xFF141416),
        modal = Color(0xFF141416),
        playerOverlay = Color(0xCC0D0D0D)
    )

    private val _currentPalette = MutableStateFlow(DefaultImmersivePalette)
    val currentPalette: StateFlow<ThemeColorPalette> = _currentPalette.asStateFlow()

    private var lastUrl: String? = null

    fun notifyArtworkUrl(context: Context, url: String?) {
        if (url.isNullOrBlank()) return // Mantém a última cor conforme regra do Better-Nuvio
        if (url == lastUrl) return
        lastUrl = url

        val cached = paletteCache.get(url)
        if (cached != null) {
            _currentPalette.value = cached
            return
        }

        scope.launch {
            try {
                val palette = extractFromUrl(context.applicationContext, url)
                if (palette != null) {
                    paletteCache.put(url, palette)
                    _currentPalette.value = palette
                }
            } catch (_: Exception) {
                // Silencioso; preserva a paleta anterior
            }
        }
    }

    private suspend fun extractFromUrl(context: Context, url: String): ThemeColorPalette? = withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .size(Size(64, 36))
            .build()

        val result = try {
            context.imageLoader.execute(request)
        } catch (_: Exception) {
            null
        }

        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@withContext null
        extractFromBitmap(bitmap)
    }

    fun extractFromBitmap(bitmap: Bitmap): ThemeColorPalette {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return _currentPalette.value

        val gx = if (w < 32) w else 32
        val gy = if (h < 18) h else 18

        var total = 0
        var transp = 0
        var crom = 0

        class Celula {
            var w = 0f
            var r = 0f
            var g = 0f
            var b = 0f
            var wc = 0f
            var rc = 0f
            var gc = 0f
            var bc = 0f
            var n = 0
        }

        val cel = Array(48) { Celula() }
        val reg = Array(4) { FloatArray(3) }
        val regN = IntArray(4)

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (j in 0 until gy) {
            val y = (2 * j + 1) * h / (2 * gy)
            val lineOffset = y * w
            for (i in 0 until gx) {
                val x = (2 * i + 1) * w / (2 * gx)
                val pixel = pixels[lineOffset + x]
                val a = (pixel ushr 24) and 0xFF
                if (a < 200) {
                    transp++
                    continue
                }
                total++

                val r = ((pixel ushr 16) and 0xFF) / 255f
                val g = ((pixel ushr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f

                // Regiões da luz ambiente (esquerda, direita, topo, base)
                if (i * 3 < gx) { reg[0][0] += r; reg[0][1] += g; reg[0][2] += b; regN[0]++ }
                if (i * 3 >= 2 * gx) { reg[1][0] += r; reg[1][1] += g; reg[1][2] += b; regN[1]++ }
                if (j * 3 < gy) { reg[2][0] += r; reg[2][1] += g; reg[2][2] += b; regN[2]++ }
                if (j * 3 >= 2 * gy) { reg[3][0] += r; reg[3][1] += g; reg[3][2] += b; regN[3]++ }

                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                val c = mx - mn
                val v = mx
                if (v < 0.14f || c < 0.10f) continue

                var hue = when (mx) {
                    r -> (g - b) / c
                    g -> (b - r) / c + 2f
                    else -> (r - g) / c + 4f
                }
                if (hue < 0f) hue += 6f
                var k = (hue * 4f).toInt()
                if (k < 0) k = 0
                if (k >= 24) k = 23

                val luma = 0.299f * r + 0.587f * g + 0.114f * b
                val faixa = if (luma < 0.40f) 1 else 0
                var wt = c * c * (1f - 1.1f * abs(v - 0.62f))
                if (wt < 0.005f) wt = 0.005f

                val ce = cel[k * 2 + faixa]
                val s = c / v
                val graus = hue * 60f
                var wp = wt
                // Desconto em tom de pele (8 a 42 graus)
                if (graus in 8f..42f && s in 0.18f..0.64f && v >= 0.30f) {
                    wp = wt * 0.12f
                }
                ce.w += wp; ce.r += r * wp; ce.g += g * wp; ce.b += b * wp
                ce.wc += wt; ce.rc += r * wt; ce.gc += g * wt; ce.bc += b * wt
                ce.n++
                crom++
            }
        }

        if (total < 8) return _currentPalette.value
        val isLogo = transp * 5 >= (transp + total)
        if (isLogo) {
            for (cell in cel) {
                cell.w = cell.wc; cell.r = cell.rc; cell.g = cell.gc; cell.b = cell.bc
            }
        }

        if (crom * 25 < total) {
            // Arte praticamente P&B: usa a cor padrão do Better-Nuvio (branco/grafite)
            return buildPaletteFromRgb(floatArrayOf(0.9f, 0.9f, 0.95f), floatArrayOf(0.051f, 0.051f, 0.051f))
        }

        val matizW = FloatArray(24) { i -> cel[i * 2].w + cel[i * 2 + 1].w }
        val matizWc = FloatArray(24) { i -> cel[i * 2].wc + cel[i * 2 + 1].wc }

        var melhor = 0
        var melhorSc = -1f
        for (i in 0 until 24) {
            val sc = matizW[i] + 0.5f * (matizW[(i + 23) % 24] + matizW[(i + 1) % 24])
            if (sc > melhorSc) { melhorSc = sc; melhor = i }
        }

        if (melhorSc < 0.0025f * total) {
            melhorSc = -1f
            for (i in 0 until 24) {
                val sc = matizWc[i] + 0.5f * (matizWc[(i + 23) % 24] + matizWc[(i + 1) % 24])
                if (sc > melhorSc) { melhorSc = sc; melhor = i }
            }
            if (melhorSc < 0.0025f * total) return _currentPalette.value
            for (cell in cel) {
                cell.w = cell.wc; cell.r = cell.rc; cell.g = cell.gc; cell.b = cell.bc
            }
        }

        val a = (melhor + 23) % 24
        val z = (melhor + 1) % 24
        val W = cel[melhor * 2].w + cel[melhor * 2 + 1].w +
                0.5f * (cel[a * 2].w + cel[a * 2 + 1].w + cel[z * 2].w + cel[z * 2 + 1].w)
        val safeW = if (W > 1e-6f) W else 1e-6f

        val bruto = floatArrayOf(
            (cel[melhor * 2].r + cel[melhor * 2 + 1].r + 0.5f * (cel[a * 2].r + cel[a * 2 + 1].r + cel[z * 2].r + cel[z * 2 + 1].r)) / safeW,
            (cel[melhor * 2].g + cel[melhor * 2 + 1].g + 0.5f * (cel[a * 2].g + cel[a * 2 + 1].g + cel[z * 2].g + cel[z * 2 + 1].g)) / safeW,
            (cel[melhor * 2].b + cel[melhor * 2 + 1].b + 0.5f * (cel[a * 2].b + cel[a * 2 + 1].b + cel[z * 2].b + cel[z * 2 + 1].b)) / safeW
        )

        val acento = FloatArray(3)
        ajustarCor(bruto, acento, 0.50f, 0.68f)

        val base = FloatArray(3)
        ajustarBase(bruto, base)

        return buildPaletteFromRgb(acento, base)
    }

    private fun buildPaletteFromRgb(acento: FloatArray, base: FloatArray): ThemeColorPalette {
        val accentColor = Color(
            red = acento[0].coerceIn(0f, 1f),
            green = acento[1].coerceIn(0f, 1f),
            blue = acento[2].coerceIn(0f, 1f)
        )

        val lch = FloatArray(3)
        lchDe(acento, lch)
        val l0 = lch[0]
        val c0 = lch[1]
        val h0 = lch[2]

        // 3 paradas do degradê em OKLCh do Better-Nuvio (clara, média, escura)
        val grad0Rgb = FloatArray(3)
        val grad2Rgb = FloatArray(3)
        conferirContraste((l0 + 0.09f).coerceAtMost(0.70f), c0 * 0.95f, h0 - 0.17f, grad0Rgb)
        conferirContraste((l0 - 0.10f).coerceAtLeast(0.40f), c0, h0 + 0.17f, grad2Rgb)

        val grad0 = Color(grad0Rgb[0].coerceIn(0f, 1f), grad0Rgb[1].coerceIn(0f, 1f), grad0Rgb[2].coerceIn(0f, 1f))
        val grad1 = accentColor
        val grad2 = Color(grad2Rgb[0].coerceIn(0f, 1f), grad2Rgb[1].coerceIn(0f, 1f), grad2Rgb[2].coerceIn(0f, 1f))

        // Fundo base escura tingida com o matiz da arte (Better-Nuvio: CV_BASE_L = 0.175, C max 0.032)
        val baseR = base[0].coerceIn(0f, 1f)
        val baseG = base[1].coerceIn(0f, 1f)
        val baseB = base[2].coerceIn(0f, 1f)

        val bgColor = Color(baseR, baseG, baseB)
        val bgElevated = Color((baseR + 0.03f).coerceAtMost(1f), (baseG + 0.03f).coerceAtMost(1f), (baseB + 0.035f).coerceAtMost(1f))
        val bgCard = Color((baseR + 0.055f).coerceAtMost(1f), (baseG + 0.055f).coerceAtMost(1f), (baseB + 0.065f).coerceAtMost(1f))
        val surfaceColor = Color((baseR + 0.04f).coerceAtMost(1f), (baseG + 0.04f).coerceAtMost(1f), (baseB + 0.045f).coerceAtMost(1f))
        val surfaceVariant = Color((baseR + 0.065f).coerceAtMost(1f), (baseG + 0.065f).coerceAtMost(1f), (baseB + 0.075f).coerceAtMost(1f))
        val focusBg = Color(
            (baseR * 0.7f + acento[0] * 0.3f).coerceIn(0f, 1f),
            (baseG * 0.7f + acento[1] * 0.3f).coerceIn(0f, 1f),
            (baseB * 0.7f + acento[2] * 0.3f).coerceIn(0f, 1f)
        )

        // Tinta de foco: se a luminância relativa for alta (> 0.88), texto escuro; senão, branco
        val lum = 0.2126f * acento[0] + 0.7152f * acento[1] + 0.0722f * acento[2]
        val onSecondary = if (lum > 0.88f) Color(0xFF111111) else Color.White

        return ThemeColorPalette(
            secondary = accentColor,
            secondaryVariant = grad2,
            onSecondary = onSecondary,
            onSecondaryVariant = onSecondary,
            accentGradient = listOf(grad0, grad1, grad2),
            focusRing = accentColor,
            focusRingGradient = listOf(grad0, grad1, grad2),
            focusBackground = focusBg,
            background = bgColor,
            backgroundElevated = bgElevated,
            backgroundCard = bgCard,
            surface = surfaceColor,
            surfaceVariant = surfaceVariant,
            panel = bgElevated,
            overlay = bgColor.copy(alpha = 0.85f),
            field = bgCard,
            menu = bgElevated,
            modal = bgElevated,
            playerOverlay = bgColor.copy(alpha = 0.80f)
        )
    }

    // ------------------------------------------------------------- OKLab Math
    private fun linDeSrgb(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun srgbDeLin(c: Float): Float = when {
        c <= 0f -> 0f
        c >= 1f -> 1f
        c <= 0.0031308f -> c * 12.92f
        else -> 1.055f * c.pow(1f / 2.4f) - 0.055f
    }

    private fun labDeLin(l3: FloatArray, lab: FloatArray) {
        var l = 0.4122214708f * l3[0] + 0.5363325363f * l3[1] + 0.0514459929f * l3[2]
        var m = 0.2119034982f * l3[0] + 0.6806995451f * l3[1] + 0.1073969566f * l3[2]
        var s = 0.0883024619f * l3[0] + 0.2817188376f * l3[1] + 0.6299787005f * l3[2]
        l = Math.cbrt(l.toDouble()).toFloat()
        m = Math.cbrt(m.toDouble()).toFloat()
        s = Math.cbrt(s.toDouble()).toFloat()
        lab[0] = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s
        lab[1] = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s
        lab[2] = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
    }

    private fun linDeLab(lab: FloatArray, l3: FloatArray) {
        var l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
        var m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
        var s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
        l = l * l * l; m = m * m * m; s = s * s * s
        l3[0] = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        l3[1] = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        l3[2] = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    }

    private fun srgbParaOklab(rgb: FloatArray, lab: FloatArray) {
        val l3 = floatArrayOf(linDeSrgb(rgb[0]), linDeSrgb(rgb[1]), linDeSrgb(rgb[2]))
        labDeLin(l3, lab)
    }

    private fun oklabParaSrgb(lab: FloatArray, rgb: FloatArray) {
        val l3 = FloatArray(3)
        linDeLab(lab, l3)
        rgb[0] = srgbDeLin(l3[0]); rgb[1] = srgbDeLin(l3[1]); rgb[2] = srgbDeLin(l3[2])
    }

    private fun luminanciaRel(rgb: FloatArray): Float =
        0.2126f * linDeSrgb(rgb[0]) + 0.7152f * linDeSrgb(rgb[1]) + 0.0722f * linDeSrgb(rgb[2])

    private fun contraste(a: FloatArray, b: FloatArray): Float {
        val ya = luminanciaRel(a) + 0.05f
        val yb = luminanciaRel(b) + 0.05f
        return if (ya > yb) ya / yb else yb / ya
    }

    private fun lchParaSrgb(L: Float, C: Float, h: Float, rgb: FloatArray) {
        var currentC = C
        val lab = FloatArray(3)
        val l3 = FloatArray(3)
        for (k in 0 until 60) {
            lab[0] = L
            lab[1] = currentC * cos(h.toDouble()).toFloat()
            lab[2] = currentC * sin(h.toDouble()).toFloat()
            linDeLab(lab, l3)
            if ((l3[0] in -0.001f..1.001f && l3[1] in -0.001f..1.001f && l3[2] in -0.001f..1.001f) || currentC <= 0f) {
                rgb[0] = srgbDeLin(l3[0]); rgb[1] = srgbDeLin(l3[1]); rgb[2] = srgbDeLin(l3[2])
                return
            }
            currentC -= 0.005f
            if (currentC < 0f) currentC = 0f
        }
        val fallbackLab = floatArrayOf(L, 0f, 0f)
        oklabParaSrgb(fallbackLab, rgb)
    }

    private fun lchDe(rgb: FloatArray, outLch: FloatArray) {
        val lab = FloatArray(3)
        srgbParaOklab(rgb, lab)
        outLch[0] = lab[0]
        outLch[1] = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        outLch[2] = atan2(lab[2].toDouble(), lab[1].toDouble()).toFloat()
    }

    private fun conferirContraste(L: Float, C: Float, h: Float, saida: FloatArray) {
        val branco = floatArrayOf(1f, 1f, 1f)
        val fundo = floatArrayOf(0.051f, 0.051f, 0.051f)
        var curL = L
        lchParaSrgb(curL, C, h, saida)
        for (k in 0 until 40) {
            if (contraste(saida, branco) >= 3.2f) break
            curL -= 0.01f
            lchParaSrgb(curL, C, h, saida)
        }
        for (k in 0 until 40) {
            if (contraste(saida, fundo) >= 3.0f) break
            curL += 0.01f
            lchParaSrgb(curL, C, h, saida)
        }
    }

    private fun ajustarCor(bruto: FloatArray, saida: FloatArray, lMin: Float, lMax: Float) {
        val lch = FloatArray(3)
        lchDe(bruto, lch)
        var l = lch[0].coerceIn(lMin, lMax)
        var c = (lch[1] * 1.15f).coerceIn(0.10f, 0.24f)
        conferirContraste(l, c, lch[2], saida)
    }

    private fun ajustarBase(bruto: FloatArray, saida: FloatArray) {
        val lch = FloatArray(3)
        lchDe(bruto, lch)
        val c = (lch[1] * 0.35f).coerceAtMost(0.032f)
        lchParaSrgb(0.175f, c, lch[2], saida)
    }
}

package com.chiron.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.chiron.mobile.service.AssistantBus.Phase
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Chiron'un çekirdeği — PC'deki tel kafes hologramın (frontend/three/EnergyCore.tsx +
 * shaders/wireCore.ts) Canvas karşılığı. WebGL yok; aynı model elle çiziliyor:
 *
 *  - Üç iç içe ikosfer kafes: dış (seyrek, soluk), orta (sık, TERS döner), çekirdek
 *    (küçük, yoğun). Derinlik hissi katmanların birbirine göre ters dönmesinden geliyor.
 *  - Köşeler gürültüyle dalgalanıyor (PC'de simplex; burada ucuz sinüs alanı).
 *  - Çizgi rengi: bakışa göre eğim (silüet) parlatıyor, arkada kalan yarı soluk.
 *  - Nefes, süzülme, duruma göre renk/hız/dalgalanma — sabitler PC'dekiyle aynı.
 *
 * Çizim maliyeti: kareyi ~24 yola (katman × parlaklık bandı) topluyoruz; her çizgi
 * için ayrı drawLine çağrısı ~2500 çağrı demekti.
 */
object CoreColors {
    val Ice = Color(0xFF7FB2FF)
    val IceDeep = Color(0xFF3F6DB8)
    val Teal = Color(0xFF4FD6A8)
    val TealDeep = Color(0xFF06403C)
    val Amber = Color(0xFFE0A355)
    val AmberDeep = Color(0xFF3D2408)
    val Sleep = Color(0xFF28374D)
    val SleepDeep = Color(0xFF0C141F)
    val Rim = Color(0xFFE8EEF7)
    val Background = Color(0xFF05070C)
}

private data class Palette(val deep: Color, val light: Color, val rim: Color)

private val ICE = Palette(CoreColors.IceDeep, CoreColors.Ice, CoreColors.Rim)
private val TEAL = Palette(CoreColors.TealDeep, CoreColors.Teal, Color(0xFFDFFFF4))
private val AMBER = Palette(CoreColors.AmberDeep, CoreColors.Amber, Color(0xFFFFF0D6))

// ── PC sabitleri (EnergyCore.tsx) ───────────────────────────────────────────
private const val ROTATION_SPEED = 0.08f
private const val FLOAT_AMPLITUDE = 0.12f
private const val FLOAT_SPEED = 0.6f
private const val BREATH_SPEED = 0.7f
private const val REACT_DAMPING = 4f
private const val BASE_DISTORTION = 0.16f
private const val REVEAL_S = 0.9f
private const val VIEW_EXTENT = 2.05f // sahne yarıçapı → Canvas yarıçapı

private class Layer(
    val radius: Float,
    val detail: Int,
    val opacity: Float,
    val distortionScale: Float,
    val spin: Float,
    val tilt: Float,
    val strokeScale: Float,
)

private val LAYERS = listOf(
    Layer(1.62f, 1, 0.32f, 0.4f, 0.55f, 0f, 1f),
    Layer(1.24f, 2, 0.44f, 1f, -1.35f, 0.46f, 0.9f),
    Layer(0.58f, 3, 0.8f, 1.5f, 2.1f, -0.32f, 0.75f),
)

/** Birim ikosfer: köşeler (x,y,z dizisi) + tekil kenarlar (köşe indisi çiftleri). */
private class Geometry(val verts: FloatArray, val edges: IntArray) {
    val count = verts.size / 3
}

private fun icosphere(detail: Int): Geometry {
    val t = ((1 + sqrt(5.0)) / 2).toFloat()
    val v = mutableListOf(
        floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
        floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t), floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
        floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f), floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f),
    ).map { norm(it) }.toMutableList()
    var faces = listOf(
        intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
        intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
        intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
        intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
    )
    // three.js IcosahedronGeometry(detail) her kenarı detail+1 parçaya böler.
    val n = detail + 1
    if (n > 1) {
        val cache = HashMap<String, Int>()
        fun point(a: FloatArray, b: FloatArray, c: FloatArray, i: Int, j: Int): Int {
            // Barisentrik: i adım a→b, j adım a→c
            val p = FloatArray(3) { k -> a[k] + (b[k] - a[k]) * i / n + (c[k] - a[k]) * j / n }
            val q = norm(p)
            val key = "%.4f,%.4f,%.4f".format(java.util.Locale.ROOT, q[0], q[1], q[2])
            return cache.getOrPut(key) { v.add(q); v.size - 1 }
        }
        // Mevcut köşeleri önbelleğe koy.
        v.forEachIndexed { idx, q -> cache["%.4f,%.4f,%.4f".format(java.util.Locale.ROOT, q[0], q[1], q[2])] = idx }
        val out = ArrayList<IntArray>()
        for (f in faces) {
            val a = v[f[0]]; val b = v[f[1]]; val c = v[f[2]]
            val grid = Array(n + 1) { i -> IntArray(n + 1 - i) { j -> point(a, b, c, i, j) } }
            for (i in 0 until n) for (j in 0 until n - i) {
                out += intArrayOf(grid[i][j], grid[i + 1][j], grid[i][j + 1])
                if (j + 1 < n - i) out += intArrayOf(grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1])
            }
        }
        faces = out
    }
    val edgeSet = LinkedHashSet<Long>()
    for (f in faces) for (k in 0 until 3) {
        val a = f[k]; val b = f[(k + 1) % 3]
        edgeSet += if (a < b) (a.toLong() shl 32) or b.toLong() else (b.toLong() shl 32) or a.toLong()
    }
    val edges = IntArray(edgeSet.size * 2)
    edgeSet.forEachIndexed { i, e -> edges[2 * i] = (e shr 32).toInt(); edges[2 * i + 1] = e.toInt() }
    val verts = FloatArray(v.size * 3)
    v.forEachIndexed { i, q -> verts[3 * i] = q[0]; verts[3 * i + 1] = q[1]; verts[3 * i + 2] = q[2] }
    return Geometry(verts, edges)
}

private fun norm(p: FloatArray): FloatArray {
    val l = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
    return floatArrayOf(p[0] / l, p[1] / l, p[2] / l)
}

/** Ucuz, pürüzsüz 3B alan (-1..1) — simplex'in görsel yerine geçen sinüs toplamı. */
private fun field(x: Float, y: Float, z: Float, t: Float): Float =
    (sin(x * 2.1f + t * 0.55f) * sin(y * 1.7f - t * 0.4f) +
        sin(z * 2.4f + t * 0.35f) * sin(x * 1.3f + y * 1.1f + t * 0.3f)) * 0.5f

private class Targets(val breath: Float, val intensity: Float, val distortion: Float, val scale: Float, val rotation: Float)

private fun targets(phase: Phase, mic: Float): Targets = when (phase) {
    Phase.OFF -> Targets(0.5f, 0.8f, 0.1f, 0.94f, 0.45f)
    Phase.WAITING -> Targets(0.6f, 0.62f, 0.1f, 0.94f, 0.6f)
    Phase.CONNECTING, Phase.THINKING -> Targets(0.6f, 0.85f, 0.08f, 1f, 0.7f)
    Phase.LISTENING -> Targets(1.7f, 1f + mic * 0.6f, BASE_DISTORTION + mic * 0.25f, 1f + mic * 0.12f, 1f)
    Phase.SPEAKING -> Targets(1f, 1.32f, BASE_DISTORTION, 1.14f, 1f)
    Phase.WORKING -> Targets(1.25f, 1.24f, 0.07f, 0.95f, 2.4f)
}

private fun damp(current: Float, target: Float, dt: Float) =
    current + (target - current) * (1 - exp(-REACT_DAMPING * dt))

private const val BANDS = 4 // silüet parlaklık bandı
private const val SIDES = 2 // ön / arka

@Composable
fun Orb(phase: Phase, micLevel: Float, modifier: Modifier = Modifier) {
    val palette = when (phase) {
        // Kapalıyken de buz mavisi: telefonda sönük kafes ekranı boş gösteriyordu (kullanıcı isteği, 2026-09-17).
        Phase.SPEAKING -> TEAL
        Phase.WORKING -> AMBER
        else -> ICE
    }
    val deep by animateColorAsState(palette.deep, tween(900), label = "deep")
    val light by animateColorAsState(palette.light, tween(900), label = "light")
    val rim by animateColorAsState(palette.rim, tween(900), label = "rim")

    val geometries = remember { LAYERS.map { icosphere(it.detail) } }
    val paths = remember { Array(LAYERS.size * BANDS * SIDES) { Path() } }
    val projected = remember { geometries.map { FloatArray(it.count * 3) } }
    val latestPhase by rememberUpdatedState(phase)
    val latestMic by rememberUpdatedState(micLevel)

    // Canlı durum — kare döngüsü günceller, çizim okur. State değil: yalnız `frame` tetikliyor.
    val sim = remember { FloatArray(9).apply { this[3] = BASE_DISTORTION; this[4] = 0.8f; this[5] = 1f } } // 0 zaman, 1 nefes fazı, 2 yoğunluk, 3 dalga, 4 ölçek, 5 dönüş çarpanı, 6-8 katman açıları
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val tg = targets(latestPhase, latestMic)
                sim[0] += dt
                sim[1] += dt * BREATH_SPEED * tg.breath
                sim[2] = damp(sim[2], tg.intensity, dt)
                sim[3] = damp(sim[3], tg.distortion, dt)
                sim[4] = damp(sim[4], tg.scale, dt)
                sim[5] = damp(sim[5], tg.rotation, dt)
                val spin = dt * ROTATION_SPEED * sim[5] * 6f // telefonda kafes küçük: dönüş biraz hızlı okunmalı
                LAYERS.forEachIndexed { i, l -> sim[6 + i] += spin * l.spin }
                frame = now
            }
        }
    }

    Canvas(modifier) {
        if (frame < 0) return@Canvas // okuma: her karede yeniden çiz
        val t = sim[0]
        val breath = sin(sim[1])
        val reveal = (t / REVEAL_S).coerceIn(0f, 1f)
        val intensity = (sim[2] * (1f + breath * 0.2f)).coerceAtMost(1.5f) * reveal
        val scale = sim[4] * (1f + breath * 0.03f)
        val c = Offset(size.width / 2, size.height / 2 + sin(t * FLOAT_SPEED) * FLOAT_AMPLITUDE * size.minDimension / 2 / VIEW_EXTENT)
        val unit = size.minDimension / 2 / VIEW_EXTENT * scale
        val strokeBase = (size.minDimension / 260f).coerceAtLeast(1f)
        if (unit <= 0f) return@Canvas

        // Bloom yerine: gövde renginde yumuşak ışıma.
        drawCircle(
            Brush.radialGradient(listOf(light.copy(alpha = 0.28f * intensity.coerceAtMost(1f)), Color.Transparent), c, unit * 1.9f),
            radius = unit * 1.9f, center = c,
        )

        val base = lerp(deep, light, 0.55f)
        LAYERS.forEachIndexed { li, layer ->
            val g = geometries[li]
            val p = projected[li]
            val angle = sim[6 + li]
            val cy = cos(angle); val sy = sin(angle)
            val ct = cos(layer.tilt); val st = sin(layer.tilt)
            val cz = cos(layer.tilt * 0.5f); val sz = sin(layer.tilt * 0.5f)
            val amp = sim[3] * layer.distortionScale
            for (i in 0 until g.count) {
                var x = g.verts[3 * i]; var y = g.verts[3 * i + 1]; var z = g.verts[3 * i + 2]
                val r = layer.radius * (1f + field(x * 1.4f, y * 1.4f, z * 1.4f, t) * amp)
                x *= r; y *= r; z *= r
                // dönüş: Y (spin) → X (eğim) → Z
                val x1 = x * cy + z * sy; val z1 = -x * sy + z * cy
                val y2 = y * ct - z1 * st; val z2 = y * st + z1 * ct
                val x3 = x1 * cz - y2 * sz; val y3 = x1 * sz + y2 * cz
                // perspektif: kamera +z'de, 6 birim uzakta
                val persp = 6f / (6f - z2)
                p[3 * i] = c.x + x3 * unit * persp
                p[3 * i + 1] = c.y - y3 * unit * persp
                p[3 * i + 2] = z2 / layer.radius // ~ -1..1: kenar normalinin bakışla açısı
            }
            for (k in 0 until BANDS * SIDES) paths[li * BANDS * SIDES + k].rewind()
            val e = g.edges
            var k = 0
            while (k < e.size) {
                val a = e[k]; val b = e[k + 1]
                k += 2
                val ndv = (p[3 * a + 2] + p[3 * b + 2]) * 0.5f
                val sil = (1f - abs(ndv)).coerceIn(0f, 1f).pow(2.2f)
                val band = (sil * BANDS).toInt().coerceAtMost(BANDS - 1)
                val side = if (ndv < -0.15f) 1 else 0
                val path = paths[li * BANDS * SIDES + band * SIDES + side]
                path.moveTo(p[3 * a], p[3 * a + 1])
                path.lineTo(p[3 * b], p[3 * b + 1])
            }
            for (band in 0 until BANDS) for (side in 0 until SIDES) {
                val sil = (band + 0.5f) / BANDS
                val tinted = lerp(base, rim, sil * 0.45f)
                val glow = (0.34f + sil * 0.85f) * intensity
                val color = Color(
                    (tinted.red * glow).coerceAtMost(1f),
                    (tinted.green * glow).coerceAtMost(1f),
                    (tinted.blue * glow).coerceAtMost(1f),
                )
                val alpha = layer.opacity * reveal * (if (side == 1) 0.3f else 1f)
                drawPath(
                    paths[li * BANDS * SIDES + band * SIDES + side],
                    color = color,
                    alpha = (alpha * 1.6f).coerceAtMost(1f), // telefonda bloom yok: çizgiler biraz daha belirgin
                    style = Stroke(strokeBase * layer.strokeScale),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }
}

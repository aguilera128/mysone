package dev.javieraguilera.mysone.asr

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakersTest {

    private fun voice(seed: Int, dim: Int = 16): FloatArray = Random(seed).let { r -> normalized(FloatArray(dim) { r.nextFloat() * 2 - 1 }) }

    /** [base] con ruido: parecida a ella (coseno ~0,9) y distinta de las demás. */
    private fun near(base: FloatArray, seed: Int, noise: Float = 0.25f): FloatArray =
        Random(seed).let { r -> normalized(FloatArray(base.size) { base[it] + (r.nextFloat() * 2 - 1) * noise }) }

    // --- Ventanas ---

    @Test fun `un trozo corto es una sola ventana`() {
        assertEquals(listOf(Span(0, 20_000)), voiceWindows(20_000))
        assertEquals(listOf(Span(0, VOICE_WINDOW)), voiceWindows(VOICE_WINDOW))
    }

    @Test fun `las ventanas avanzan a saltos y la última llega al final`() {
        assertEquals(listOf(Span(0, 24_000), Span(12_000, 36_000), Span(16_000, 40_000)), voiceWindows(40_000, 24_000, 12_000))
        assertEquals(listOf(Span(0, 24_000), Span(12_000, 36_000)), voiceWindows(36_000, 24_000, 12_000))
    }

    // --- Agrupar voces ---

    @Test fun `dos voces se separan y se numeran por orden de aparición`() {
        val a = voice(1)
        val b = voice(2)
        val prints = listOf(near(b, 10), near(b, 11), near(a, 12), near(b, 13), near(a, 14))
        assertArrayEquals(intArrayOf(0, 0, 1, 0, 1), clusterVoices(prints, IntArray(5) { 1 }, 0.5f))
    }

    @Test fun `con un umbral imposible cada huella va sola y con uno muy bajo todas juntas`() {
        val prints = (1..4).map { voice(it) }
        assertArrayEquals(intArrayOf(0, 1, 2, 3), clusterVoices(prints, IntArray(4) { 1 }, 1.1f))
        assertArrayEquals(intArrayOf(0, 0, 0, 0), clusterVoices(prints, IntArray(4) { 1 }, -1.1f))
        assertArrayEquals(intArrayOf(), clusterVoices(emptyList(), IntArray(0), 0.3f))
        assertArrayEquals(intArrayOf(0), clusterVoices(listOf(voice(1)), intArrayOf(1), 0.3f))
    }

    /** La cadena de vecinos da lo mismo que el aglomerativo de toda la vida (unir siempre el mejor par). */
    @Test fun `la cadena de vecinos corta el árbol igual que el aglomerativo directo`() {
        val rnd = Random(3)
        repeat(40) { round ->
            val voices = (0 until rnd.nextInt(1, 5)).map { voice(100 * round + it) }
            val n = rnd.nextInt(2, 30)
            val prints = List(n) { near(voices[rnd.nextInt(voices.size)], 1000 * round + it, noise = rnd.nextFloat()) }
            val weights = IntArray(n) { rnd.nextInt(1, 6) }
            for (t in listOf(-0.2f, 0.1f, 0.3f, 0.5f, 0.8f)) {
                assertArrayEquals("ronda $round, umbral $t", naive(prints, weights, t), clusterVoices(prints, weights, t))
            }
        }
    }

    private fun naive(prints: List<FloatArray>, weights: IntArray, threshold: Float): IntArray {
        val groups = prints.indices.map { mutableListOf(it) }.toMutableList()
        val sim = Array(prints.size) { a -> FloatArray(prints.size) { b -> dot(prints[a], prints[b]) } }
        // sim entre grupos = media pesada de los pares, que es lo que mantiene Lance-Williams
        fun between(x: List<Int>, y: List<Int>): Float {
            var s = 0f
            var n = 0f
            for (i in x) for (j in y) {
                s += weights[i] * weights[j] * sim[i][j]
                n += weights[i] * weights[j]
            }
            return s / n
        }
        while (groups.size > 1) {
            var best = Float.NEGATIVE_INFINITY
            var bi = 0
            var bj = 0
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val s = between(groups[i], groups[j])
                if (s > best) {
                    best = s
                    bi = i
                    bj = j
                }
            }
            if (best < threshold) break
            groups[bi].addAll(groups[bj])
            groups.removeAt(bj)
        }
        val label = IntArray(prints.size)
        groups.forEachIndexed { g, members -> members.forEach { label[it] = g } }
        val order = HashMap<Int, Int>()
        return IntArray(prints.size) { order.getOrPut(label[it]) { order.size } }
    }

    // --- Partir un trozo por voces ---

    @Test fun `el corte cae en la pausa entre los dos turnos`() {
        // 1 s de voz A, 0,3 s de silencio, 1,2 s de voz B.
        val samples = FloatArray(40_000) { i -> if (i in 16_000 until 20_800) 0f else 0.5f }
        val windows = listOf(Span(0, 16_000), Span(8_000, 24_000), Span(16_000, 32_000), Span(24_000, 40_000))
        val parts = splitByVoice(samples, windows, intArrayOf(0, 0, 1, 1), minPart = 8_000)
        assertEquals(2, parts.size)
        assertEquals(0, parts[0].from)
        assertEquals(40_000, parts[1].to)
        assertEquals(parts[0].to, parts[1].from)
        assertTrue("corte en ${parts[0].to}", parts[0].to in 16_000..20_800 - 320)
    }

    @Test fun `una parte demasiado corta se une a la vecina más larga`() {
        val samples = FloatArray(64_000) { 0.3f }
        val windows = voiceWindows(64_000, 16_000, 8_000) // centros cada 0,5 s
        val labels = IntArray(windows.size) { if (it == 3) 1 else 0 } // una ventana suelta de otra voz
        assertEquals(listOf(Span(0, 64_000)), splitByVoice(samples, windows, labels, minPart = SAMPLE_RATE))
    }

    @Test fun `tres turnos dan tres partes seguidas que cubren el trozo`() {
        val samples = FloatArray(96_000) { 0.3f }
        val windows = voiceWindows(96_000, 24_000, 12_000)
        val labels = IntArray(windows.size) { if (it < 2) 0 else if (it < 5) 1 else 0 }
        val parts = splitByVoice(samples, windows, labels)
        assertEquals(3, parts.size)
        assertEquals(0, parts.first().from)
        assertEquals(96_000, parts.last().to)
        for (i in 1 until parts.size) assertEquals(parts[i - 1].to, parts[i].from)
        assertTrue(parts.all { it.to - it.from >= MIN_PART / 4 })
    }

    @Test fun `el corte no sale de sus límites`() {
        val samples = FloatArray(10_000) { if (it < 100) 0f else 1f }
        assertEquals(1_000, quietestCut(samples, 1_000, 900, 5_000)) // lo más callado (el principio) queda fuera
        assertEquals(500, quietestCut(FloatArray(10_000) { 1f }, 500, 0, 5_000)) // nada más callado: no se mueve
        // Lo callado empieza en 280, pero un tramo de 20 ms desde 500 se saldría de 600: el corte queda antes.
        assertTrue(quietestCut(FloatArray(10_000) { if (it < 280) 1f else 0f }, 500, 0, 600) in 0..280)
    }

    // --- Números de hablante ---

    @Test fun `el grupo del dueño es 0 y el resto se numera por orden de aparición`() {
        val me = voice(7)
        val other = voice(8)
        val third = voice(9)
        val prints = listOf(near(other, 1), near(me, 2), near(third, 3), near(other, 4))
        val groups = intArrayOf(0, 1, 2, 0)
        assertArrayEquals(intArrayOf(1, 0, 2, 1), speakerNumbers(groups, prints, IntArray(4) { 1 }, owner = me, ownerThreshold = 0.6f))
        assertArrayEquals(intArrayOf(1, 2, 3, 1), speakerNumbers(groups, prints, IntArray(4) { 1 }, owner = null))
    }

    @Test fun `si la voz del dueño sale partida en dos grupos los dos son Tú`() {
        val me = voice(7)
        val prints = listOf(near(me, 1, 0.1f), near(voice(8), 2), near(me, 3, 0.1f))
        assertArrayEquals(intArrayOf(0, 1, 0), speakerNumbers(intArrayOf(0, 1, 2), prints, IntArray(3) { 1 }, owner = me, ownerThreshold = 0.6f))
    }

    @Test fun `sin las partes descartadas los hablantes vuelven a numerarse sin huecos`() {
        // El grupo 1 era un trozo de ruido que salió sin texto: la voz que queda pasa a ser «Hablante 1».
        assertEquals(listOf(1, 1, 0, 1), compactSpeakers(listOf(2, 2, 0, 2)))
        assertEquals(listOf(1, 2, 1, 3), compactSpeakers(listOf(3, 2, 3, 5)))
        assertEquals(listOf(null, null), compactSpeakers(listOf(null, null))) // sin modelo de voces
        assertEquals(emptyList<Int?>(), compactSpeakers(emptyList()))
    }

    @Test fun `la huella media tiene norma 1`() {
        val m = meanVoice(listOf(voice(1), voice(2), voice(3)))
        assertEquals(1f, dot(m, m), 1e-5f)
    }
}

package dev.javieraguilera.mysone.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Quién habla en un audio importado (§5.6). De cada trozo del VAD salen huellas de voz (embeddings) de ventanas de
 * 1,5 s cada 0,75 s. Las del propio trozo deciden al momento dónde cambia la voz dentro de él, para transcribir cada
 * parte por separado sin guardar el audio; al acabar el fichero se agrupan todas las partes por su huella media y
 * cada grupo es «Hablante N», o «Tú» si se parece a la voz registrada del dueño. Medido con voces reales del
 * español (MLS-es) en docs/investigacion/09-quien-habla.md.
 */

/** Ventana de cada huella y cada cuánto sale una. */
internal const val VOICE_WINDOW = SAMPLE_RATE * 3 / 2
internal const val VOICE_HOP = SAMPLE_RATE * 3 / 4

/** Parte más corta que se transcribe sola: lo que quede por debajo se une a la vecina. */
internal const val MIN_PART = SAMPLE_RATE

/**
 * Coseno medio a partir del que dos grupos de huellas son la misma voz: en todo el fichero y dentro de un trozo. Dentro
 * es más exigente: partir de más solo deja dos líneas seguidas del mismo hablante, y partir de menos mezcla dos voces.
 */
internal const val SAME_VOICE = 0.35f
internal const val SAME_VOICE_IN_CHUNK = 0.4f

/**
 * Coseno entre la huella media de un grupo y la del dueño a partir del que el grupo es «Tú». Alto a propósito: un «Tú»
 * de más le atribuye al dueño lo que dijo otro; uno de menos solo lo deja como «Hablante N».
 */
internal const val OWNER_VOICE = 0.55f

/** Más partes que estas no se agrupan (la matriz de similitudes crece con el cuadrado): el audio queda sin hablantes. */
internal const val MAX_PARTS = 3_000

/** Medio margen alrededor de un corte para buscar el momento más callado (cubre la mitad de un salto). */
private const val CUT_SEARCH = SAMPLE_RATE * 2 / 5

/** Tramo para medir la energía al buscar dónde cortar: 20 ms. */
private const val CUT_FRAME = SAMPLE_RATE / 50

/** [from, to) en muestras, relativo al trozo. */
internal data class Span(val from: Int, val to: Int) {
    val centre: Int get() = (from + to) / 2
}

/** Ventanas de las huellas de un trozo de [size] muestras: una sola si no llega a [len], y la última pegada al final. */
internal fun voiceWindows(size: Int, len: Int = VOICE_WINDOW, hop: Int = VOICE_HOP): List<Span> {
    if (size <= len) return listOf(Span(0, size))
    val out = ArrayList<Span>()
    var from = 0
    while (from + len <= size) {
        out += Span(from, from + len)
        from += hop
    }
    if (out.last().to < size) out += Span(size - len, size)
    return out
}

/**
 * Partes de un trozo de [samples] según la voz de cada ventana ([labels], una por ventana): cada ventana manda hasta la
 * mitad entre su centro y el de la vecina; las seguidas de la misma voz se unen; una parte de menos de [minPart] se une
 * a su vecina más larga. Cada corte se mueve al momento más callado de alrededor: suele ser la pausa entre dos turnos y
 * así no se parte una palabra.
 */
internal fun splitByVoice(samples: FloatArray, windows: List<Span>, labels: IntArray, minPart: Int = MIN_PART): List<Span> {
    val size = samples.size
    val parts = ArrayList<IntArray>() // [from, to, label]
    for (k in windows.indices) {
        val from = if (k == 0) 0 else (windows[k - 1].centre + windows[k].centre) / 2
        val to = if (k == windows.lastIndex) size else (windows[k].centre + windows[k + 1].centre) / 2
        if (parts.isNotEmpty() && parts.last()[2] == labels[k]) parts.last()[1] = to else parts += intArrayOf(from, to, labels[k])
    }
    while (parts.size > 1) {
        val i = parts.indices.filter { parts[it][1] - parts[it][0] < minPart }.minByOrNull { parts[it][1] - parts[it][0] } ?: break
        val len = { p: Int -> parts[p][1] - parts[p][0] }
        val j = if (i == parts.lastIndex || (i > 0 && len(i - 1) >= len(i + 1))) i - 1 else i + 1
        val (lo, hi) = minOf(i, j) to maxOf(i, j)
        parts[lo] = intArrayOf(parts[lo][0], parts[hi][1], parts[j][2])
        parts.removeAt(hi)
        // La unión puede dejar juntas dos de la misma voz.
        var p = 1
        while (p < parts.size) {
            if (parts[p][2] == parts[p - 1][2]) {
                parts[p - 1][1] = parts[p][1]
                parts.removeAt(p)
            } else {
                p++
            }
        }
    }
    val cuts = (1 until parts.size).map { quietestCut(samples, parts[it][0], parts[it - 1][0] + 1, parts[it][1] - 1) }
    return parts.indices.map { Span(if (it == 0) 0 else cuts[it - 1], if (it == parts.lastIndex) size else cuts[it]) }
}

/** El inicio del tramo de 20 ms más callado a menos de [CUT_SEARCH] de [at], sin salir de (min, max). Si no hay uno más callado, [at]. */
internal fun quietestCut(samples: FloatArray, at: Int, min: Int, max: Int): Int {
    val from = maxOf(min, at - CUT_SEARCH)
    val to = minOf(max - CUT_FRAME, at + CUT_SEARCH)
    fun energy(s: Int): Double {
        var e = 0.0
        for (i in s until s + CUT_FRAME) e += samples[i] * samples[i]
        return e
    }
    var best = at
    var quietest = if (at in from..to) energy(at) else Double.MAX_VALUE
    var s = from
    while (s <= to) {
        val e = energy(s)
        if (e < quietest) {
            quietest = e
            best = s
        }
        s += CUT_FRAME / 2
    }
    return best
}

internal fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}

/** Con norma 1: el coseno entre dos huellas es su producto escalar. */
internal fun normalized(v: FloatArray): FloatArray {
    val n = sqrt(dot(v, v))
    return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
}

/** Huella media de [prints] (ya normalizadas), con norma 1. */
internal fun meanVoice(prints: List<FloatArray>): FloatArray {
    val sum = FloatArray(prints.first().size)
    for (p in prints) for (i in sum.indices) sum[i] += p[i]
    return normalized(sum)
}

/**
 * Agrupa huellas por voz: aglomerativo de enlace medio sobre el coseno, con [weights] = cuántas ventanas resume cada
 * huella. Une dos grupos mientras su similitud media llegue a [threshold]. Cadena de vecinos más cercanos: O(n²) en
 * tiempo y memoria. Devuelve el grupo de cada huella, numerados 0, 1… por orden de primera aparición.
 */
internal fun clusterVoices(prints: List<FloatArray>, weights: IntArray, threshold: Float): IntArray {
    val n = prints.size
    if (n <= 1) return IntArray(n)
    // Triángulo inferior: sim(a, b) con a > b en a(a-1)/2 + b.
    val sim = FloatArray(n * (n - 1) / 2)
    fun at(a: Int, b: Int) = if (a > b) a * (a - 1) / 2 + b else b * (b - 1) / 2 + a
    for (a in 1 until n) for (b in 0 until a) sim[at(a, b)] = dot(prints[a], prints[b])
    val size = weights.copyOf()
    val active = BooleanArray(n) { true }
    val root = IntArray(n) { it }
    fun find(x: Int): Int {
        var r = x
        while (root[r] != r) r = root[r]
        return r
    }
    val chain = IntArray(n)
    var top = 0
    var left = n
    while (left > 1) {
        if (top == 0) chain[top++] = active.indexOfFirst { it }
        val a = chain[top - 1]
        val prev = if (top >= 2) chain[top - 2] else -1
        var b = prev
        var best = if (prev >= 0) sim[at(a, prev)] else Float.NEGATIVE_INFINITY
        for (k in 0 until n) {
            if (active[k] && k != a && sim[at(a, k)] > best) {
                best = sim[at(a, k)]
                b = k
            }
        }
        if (b != prev) {
            chain[top++] = b
            continue
        }
        // a y b son vecinos mutuos: se unen en b (Lance-Williams). El enlace medio no tiene inversiones, así que
        // cortar el árbol en el umbral es unir solo los pares que lo pasan.
        top -= 2
        for (k in 0 until n) {
            if (active[k] && k != a && k != b) sim[at(b, k)] = (size[a] * sim[at(a, k)] + size[b] * sim[at(b, k)]) / (size[a] + size[b])
        }
        size[b] += size[a]
        active[a] = false
        left--
        if (best >= threshold) root[find(a)] = find(b)
    }
    val order = HashMap<Int, Int>()
    return IntArray(n) { order.getOrPut(find(it)) { order.size } }
}

/**
 * Hablante de cada huella a partir de sus [groups]: 0 = el dueño (la huella media del grupo se parece a [owner] al
 * menos [ownerThreshold]; puede serlo más de un grupo si su voz se ha partido en dos), y 1, 2… el resto por orden de
 * aparición. Sin voz registrada no hay 0.
 */
internal fun speakerNumbers(groups: IntArray, prints: List<FloatArray>, weights: IntArray, owner: FloatArray?, ownerThreshold: Float = OWNER_VOICE): IntArray {
    val count = (groups.maxOrNull() ?: -1) + 1
    val isOwner = BooleanArray(count) { g ->
        owner != null && dot(groupVoice(g, groups, prints, weights), owner) >= ownerThreshold
    }
    val number = HashMap<Int, Int>()
    return IntArray(groups.size) { i ->
        val g = groups[i]
        if (isOwner[g]) 0 else number.getOrPut(g) { number.size + 1 }
    }
}

/**
 * Los hablantes de las partes que se guardan ([kept], null sin hablante), otra vez 1, 2… por orden de aparición: una
 * parte que sale sin texto (ruido, un carraspeo) no se guarda, y si era un grupo propio dejaría un hueco («Hablante 2»
 * sin «Hablante 1»). «Tú» (0) se queda.
 */
// ponytail: las huellas de esas partes siguen entrando en el agrupado; quitarlas en Diarizer si desvían las medias.
internal fun compactSpeakers(kept: List<Int?>): List<Int?> {
    val number = HashMap<Int, Int>()
    return kept.map { s -> if (s == null || s == 0) s else number.getOrPut(s) { number.size + 1 } }
}

/** Huella media de un grupo, pesada por las ventanas de cada huella. */
private fun groupVoice(group: Int, groups: IntArray, prints: List<FloatArray>, weights: IntArray): FloatArray {
    val sum = FloatArray(prints.first().size)
    for (i in groups.indices) if (groups[i] == group) for (d in sum.indices) sum[d] += prints[i][d] * weights[i]
    return normalized(sum)
}

/** Huella de voz de [samples] (16 kHz) con norma 1. */
internal fun SpeakerEmbeddingExtractor.voiceprint(samples: FloatArray): FloatArray {
    val stream = createStream()
    try {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        stream.inputFinished()
        return normalized(compute(stream))
    } finally {
        stream.release()
    }
}

/**
 * Quién habla en un fichero que se importa: [split] con cada trozo del VAD y [speakers] al terminar. Lo que guarda
 * son huellas (un vector por parte), no audio. Una instancia por importación; no es segura entre hilos.
 */
internal class Diarizer(private val extractor: SpeakerEmbeddingExtractor) : AutoCloseable {
    private val prints = ArrayList<FloatArray>() // huella media de cada parte, en el orden en que salen
    private val weights = ArrayList<Int>()       // ventanas que resume cada una

    /** Partes de [chunk] separadas donde cambia la voz, cada una con su número para [speakers]. */
    fun split(chunk: SpeechChunk): List<Pair<Int, SpeechChunk>> {
        val samples = chunk.samples
        val windows = voiceWindows(samples.size)
        val windowPrints = windows.map { extractor.voiceprint(samples.copyOfRange(it.from, it.to)) }
        val labels = clusterVoices(windowPrints, IntArray(windows.size) { 1 }, SAME_VOICE_IN_CHUNK)
        val parts = if (labels.all { it == 0 }) listOf(Span(0, samples.size)) else splitByVoice(samples, windows, labels)
        return parts.map { part ->
            // Las ventanas cuyo centro cae en la parte; si es tan corta que no tiene ninguna, la más cercana.
            val mine = windows.indices.filter { windows[it].centre in part.from until part.to }
                .ifEmpty { listOf(windows.indices.minBy { abs(windows[it].centre - part.centre) }) }
            prints += meanVoice(mine.map { windowPrints[it] })
            weights += mine.size
            val piece = if (parts.size == 1) chunk else SpeechChunk(chunk.startSample + part.from, samples.copyOfRange(part.from, part.to))
            prints.lastIndex to piece
        }
    }

    /** Hablante de cada parte (índices de [split]); null si son demasiadas para agruparlas (> [MAX_PARTS]). */
    fun speakers(owner: FloatArray?): IntArray? {
        if (prints.isEmpty()) return IntArray(0)
        if (prints.size > MAX_PARTS) return null
        val w = weights.toIntArray()
        return speakerNumbers(clusterVoices(prints, w, SAME_VOICE), prints, w, owner)
    }

    /** Valores de cada huella (para leer la del dueño). */
    val dim: Int get() = extractor.dim()

    override fun close() = extractor.release()

    companion object {
        /** null si el modelo de voces no está instalado o no carga. */
        suspend fun open(models: ModelManager): Diarizer? = try {
            models.openSpeakerExtractor()?.let(::Diarizer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) { // también UnsatisfiedLinkError si la librería nativa no carga
            Log.w("MysoneAsr", "El modelo de voces no carga: ${e.javaClass.simpleName}")
            null
        }
    }
}

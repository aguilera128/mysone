package dev.javieraguilera.mysone.asr

/**
 * Remuestreo lineal por bloques (importación de audio). Guarda estado entre llamadas, así que trocear la
 * entrada da el mismo resultado que pasarla entera. Índices con aritmética entera para que no derive.
 */
// ponytail: interpolación lineal sin filtro antialiasing; al bajar de 44,1/48 kHz entra algo de alias por
// encima de 8 kHz. Para voz y ASR basta; si se nota, filtro paso bajo antes de diezmar.
class Resampler(private val fromRate: Int, private val toRate: Int = 16_000) {
    private var outIndex = 0L // siguiente muestra de salida
    private var inBase = 0L   // índice global de input[0]
    private var prev = 0f     // muestra global inBase - 1

    fun process(input: FloatArray): FloatArray {
        if (fromRate == toRate) return input
        val end = inBase + input.size
        // Salidas k con su muestra izquierda i = k·from/to y la derecha i+1 dentro de lo recibido.
        val last = if (end >= 1) ((end - 1) * toRate + fromRate - 1) / fromRate else 0L
        val out = FloatArray(maxOf(0L, last - outIndex).toInt())
        for (n in out.indices) {
            val num = outIndex * fromRate
            val i = num / toRate
            val frac = (num % toRate).toFloat() / toRate
            val s0 = if (i < inBase) prev else input[(i - inBase).toInt()]
            val s1 = input[(i + 1 - inBase).toInt()]
            out[n] = s0 + (s1 - s0) * frac
            outIndex++
        }
        if (input.isNotEmpty()) prev = input.last()
        inBase = end
        return out
    }
}

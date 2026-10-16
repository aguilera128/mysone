package dev.javieraguilera.mysone.asr

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrLogicTest {

    // --- Resampler ---

    @Test fun `48 kHz a 16 kHz sobre una rampa toma una de cada tres`() {
        val ramp = FloatArray(4_800) { it.toFloat() }
        val out = Resampler(48_000).process(ramp)
        assertEquals(1_600, out.size)
        out.forEachIndexed { k, v -> assertEquals(3f * k, v, 1e-3f) }
    }

    @Test fun `8 kHz a 16 kHz interpola el punto medio`() {
        val ramp = FloatArray(800) { it.toFloat() }
        val out = Resampler(8_000).process(ramp)
        assertEquals(1_598, out.size) // tras la última muestra de entrada no hay vecina para interpolar
        out.forEachIndexed { k, v -> assertEquals(k / 2f, v, 1e-3f) }
    }

    @Test fun `trocear la entrada da lo mismo que pasarla entera`() {
        val noise = Random(7)
        val input = FloatArray(44_100) { noise.nextFloat() * 2 - 1 }
        val whole = Resampler(44_100).process(input)
        val streamed = Resampler(44_100)
        val rnd = Random(42)
        val parts = ArrayList<FloatArray>()
        var from = 0
        while (from < input.size) {
            val to = minOf(input.size, from + rnd.nextInt(0, 3_000)) // incluye bloques vacíos y de una muestra
            parts += streamed.process(input.copyOfRange(from, to))
            from = to
        }
        val joined = parts.fold(FloatArray(0)) { acc, p -> acc + p }
        assertArrayEquals(whole, joined, 1e-6f)
        assertEquals(16_000f, whole.size.toFloat(), 1f)
    }

    @Test fun `misma frecuencia no toca nada`() {
        val input = floatArrayOf(0.1f, -0.2f, 0.3f)
        assertArrayEquals(input, Resampler(16_000).process(input), 0f)
    }

    // --- Troceo del VAD ---

    @Test fun `un trozo de hasta 25 s queda igual`() {
        val samples = FloatArray(MAX_CHUNK_SAMPLES)
        val chunks = splitSpeech(123, samples)
        assertEquals(1, chunks.size)
        assertEquals(123L, chunks[0].startSample)
        assertTrue(chunks[0].samples === samples)
    }

    @Test fun `26 s se parte en dos mitades, no en 25 más 1`() {
        val chunks = splitSpeech(0, FloatArray(26 * SAMPLE_RATE))
        assertEquals(listOf(13 * SAMPLE_RATE, 13 * SAMPLE_RATE), chunks.map { it.samples.size })
    }

    @Test fun `los trozos son contiguos, no pasan de 25 s y conservan la posición absoluta`() {
        val start = 5_000_000_000L // más allá de int32
        val samples = FloatArray(61 * SAMPLE_RATE + 7) { it.toFloat() }
        val chunks = splitSpeech(start, samples)
        assertEquals(3, chunks.size)
        var expected = start
        for (c in chunks) {
            assertEquals(expected, c.startSample)
            assertTrue(c.samples.size <= MAX_CHUNK_SAMPLES)
            assertEquals((c.startSample - start).toFloat(), c.samples[0])
            expected += c.samples.size
        }
        assertEquals(start + samples.size, expected)
    }

    // --- PCM ---

    // --- Margen antes de cada trozo ---

    @Test fun `el margen añade lo anterior al trozo y retrasa su inicio`() {
        val history = AudioHistory(10)
        history.add(FloatArray(25) { it.toFloat() }) // guarda 15..24
        val chunk = withPreRoll(22, floatArrayOf(22f, 23f), history.snapshot(), notBefore = 0, preRoll = 4)
        assertEquals(18L, chunk.startSample)
        assertArrayEquals(floatArrayOf(18f, 19f, 20f, 21f, 22f, 23f), chunk.samples, 0f)
    }

    @Test fun `el margen no repite el trozo anterior ni se inventa lo que ya no está`() {
        val history = AudioHistory(10)
        history.add(FloatArray(25) { it.toFloat() })
        val snap = history.snapshot()
        assertEquals(20L, withPreRoll(22, floatArrayOf(22f), snap, notBefore = 20, preRoll = 4).startSample)
        assertEquals(15L, withPreRoll(17, floatArrayOf(17f), snap, notBefore = 0, preRoll = 4).startSample) // solo quedan 15 y 16
        val alone = withPreRoll(22, floatArrayOf(22f), null, notBefore = 0, preRoll = 4)
        assertEquals(22L, alone.startSample)
        assertEquals(1, alone.samples.size)
    }

    @Test fun `PCM16 ida y vuelta es exacto para muestras de AudioRecord`() {
        val shorts = shortArrayOf(0, 1, -1, 12_345, -12_345, Short.MAX_VALUE, Short.MIN_VALUE)
        val floats = FloatArray(shorts.size) { shorts[it] / 32768f } // como convierte la captura
        val bytes = PcmFiles.encode(floats)
        assertEquals(shorts.size * 2, bytes.size)
        assertEquals(0x39.toByte(), bytes[6]) // 12345 = 0x3039, little-endian
        assertArrayEquals(floats, PcmFiles.decode(bytes), 0f)
    }

    @Test fun `PCM16 recorta lo que se sale de -1 a 1`() {
        val back = PcmFiles.decode(PcmFiles.encode(floatArrayOf(1.5f, -2f)))
        assertEquals(32767 / 32768f, back[0], 0f)
        assertEquals(-1f, back[1], 0f)
    }

    @Test fun `PCM16 por fichero`() {
        val file = Files.createTempFile("pcm", ".pcm").toFile()
        try {
            val samples = FloatArray(1_000) { ((it % 200) - 100) / 128f }
            PcmFiles.write(file, samples)
            assertEquals(2_000L, file.length())
            assertArrayEquals(samples, PcmFiles.read(file), 0f)
        } finally {
            file.delete()
        }
    }

    @Test fun `WAV con chunk fmt de 18 bytes y chunk extra`() {
        val pcm = PcmFiles.encode(floatArrayOf(0.5f, -0.25f, 0f))
        val wav = ByteBuffer.allocate(12 + 8 + 18 + 8 + 3 + 1 + 8 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(0); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(18)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16); putShort(0)
            put("LIST".toByteArray()); putInt(3); put(byteArrayOf(1, 2, 3, 0)) // tamaño impar: lleva relleno
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
        assertArrayEquals(floatArrayOf(0.5f, -0.25f, 0f), PcmFiles.readWav(wav), 0f)
    }

    @Test fun `el WAV de la autoprueba se lee entero`() {
        val bytes = File("src/main/assets/selftest_es.wav").readBytes()
        val samples = PcmFiles.readWav(bytes)
        assertTrue(samples.size > SAMPLE_RATE) // más de 1 s
        assertTrue(samples.size >= (bytes.size - 100) / 2)
        assertTrue(samples.any { it != 0f })
    }

    // --- Verificación SHA-256 ---

    @Test fun `copia verificada con hash correcto deja el fichero y nada más`() = withDir { dir ->
        val data = Random(1).nextBytes(200_000)
        val src = File(dir, "src").apply { writeBytes(data) }
        val dst = File(dir, "out/encoder.onnx").apply { parentFile!!.mkdirs() }
        assertTrue(copyVerified(src, dst, data.size.toLong(), sha256(data).uppercase()))
        assertArrayEquals(data, dst.readBytes())
        assertFalse(File(dst.path + ".part").exists())
    }

    @Test fun `copia verificada con hash o tamaño erróneos no deja nada`() = withDir { dir ->
        val data = Random(2).nextBytes(10_000)
        val src = File(dir, "src").apply { writeBytes(data) }
        val dst = File(dir, "tokens.txt")
        val wrong = sha256(data).replaceRange(0, 1, if (sha256(data)[0] == '0') "1" else "0")
        assertFalse(copyVerified(src, dst, data.size.toLong(), wrong))
        assertFalse(copyVerified(src, dst, data.size + 1L, sha256(data)))
        assertFalse(dst.exists())
        assertFalse(File(dst.path + ".part").exists())
    }

    @Test fun `sin hash publicado basta con el tamaño`() = withDir { dir ->
        val data = "<blk> 0\n".toByteArray()
        val src = File(dir, "src").apply { writeBytes(data) }
        assertFalse(copyVerified(src, File(dir, "a"), data.size - 1L, null))
        assertTrue(copyVerified(src, File(dir, "b"), data.size.toLong(), null))
        assertArrayEquals(data, File(dir, "b").readBytes())
    }

    @Test fun `limpia espacios del texto reconocido`() {
        assertEquals("Hola, ¿qué tal?", cleanText("  Hola,\n  ¿qué   tal? "))
        assertEquals("A las 9.", cleanText("A las 9."))
    }

    @Test fun `solo puntuación cuenta como nada dicho`() {
        assertEquals("", cleanText(" . "))
        assertEquals("", cleanText("¿?"))
        assertEquals("", cleanText(""))
    }

    @Test fun `recupera la ñ que Canary cambia por un corte de palabra`() {
        assertEquals("Hola, esto es una prueba de transcripción en español.", cleanText("Hola, esto es una prueba de transcripción en espa ol."))
        assertEquals("El niño de España tiene cinco años.", cleanText("El ni o de Espa a tiene cinco a os."))
        assertEquals("Recuérdame mañana a las diez de la mañana", cleanText("Recuérdame ma ana a las diez de la ma ana"))
        assertEquals("El señor Muñoz, cumpleaños. ESPAÑA", cleanText("El se or Mu oz, cumplea os. ESPA A"))
        assertEquals("Mañana llamo.", cleanText("Ma ana llamo."))
    }

    @Test fun `no pega dos palabras que existen por separado`() {
        assertEquals("ni a mí me gusta, se a ido y le a dado", cleanText("ni a mí me gusta, se a ido y le a dado"))
        assertEquals("la casa es grande", cleanText("la casa es grande"))
    }

    private fun sha256(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun withDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("asr").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }
}

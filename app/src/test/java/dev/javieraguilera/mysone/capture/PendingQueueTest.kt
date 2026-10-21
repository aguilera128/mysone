package dev.javieraguilera.mysone.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingQueueTest {
    @Test fun `PCM16 little endian de ida y vuelta`() {
        val bytes = PendingQueue.toPcm16(floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f))
        assertEquals(10, bytes.size)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), bytes.copyOfRange(6, 8)) // 1.0 → 32767, LE
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f), PendingQueue.fromPcm16(bytes), 1e-4f)
    }

    @Test fun `lo que se sale de rango se recorta en vez de dar la vuelta`() {
        val back = PendingQueue.fromPcm16(PendingQueue.toPcm16(floatArrayOf(1.7f, -3f)))
        assertArrayEquals(floatArrayOf(1f, -1f), back, 1e-4f)
    }

    @Test fun `tras olvidar se descartan los trozos con voz del intervalo y no los de después`() {
        val since = 1_000_000L
        val at = since + 5 * 60_000
        val forgotten = since..at
        assertTrue(ListeningController.overlaps(forgotten, at - 20_000, at - 2_000)) // aún en la VAD al pulsar
        assertTrue(ListeningController.overlaps(forgotten, at - 3_000, at + 4_000)) // empezó antes de pulsar
        assertTrue(ListeningController.overlaps(forgotten, since - 10_000, since + 1_000))
        assertFalse(ListeningController.overlaps(forgotten, at, at + 3_000)) // dicho después
        assertFalse(ListeningController.overlaps(forgotten, since - 10_000, since)) // terminó antes
        assertFalse(ListeningController.overlaps(LongRange.EMPTY, 0, Long.MAX_VALUE)) // sin olvidos
    }

    @Test fun `el reloj de la captura se resincroniza si el de pared salta más de 5 s`() {
        val t0 = 1_000_000L
        val fed = 16_000L * 60 // un minuto de muestras
        val expected = t0 + 60_000
        assertFalse(AudioCapture.clockJumped(expected + 4_000, t0, fed))
        assertTrue(AudioCapture.clockJumped(expected + 10 * 60_000, t0, fed)) // hora adelantada a mano
        assertTrue(AudioCapture.clockJumped(expected - 6_000, t0, fed)) // y atrasada
    }
}

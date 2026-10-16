package dev.javieraguilera.mysone.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}

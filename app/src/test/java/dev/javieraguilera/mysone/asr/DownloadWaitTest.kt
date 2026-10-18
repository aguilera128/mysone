package dev.javieraguilera.mysone.asr

import android.app.DownloadManager.PAUSED_QUEUED_FOR_WIFI
import android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK
import android.app.DownloadManager.PAUSED_WAITING_TO_RETRY
import android.app.DownloadManager.STATUS_PAUSED
import android.app.DownloadManager.STATUS_PENDING
import android.app.DownloadManager.STATUS_RUNNING
import android.app.DownloadManager.STATUS_SUCCESSFUL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadWaitTest {
    private val pending = STATUS_PENDING to 0

    @Test fun `recien encolada sin la red que pide queda en PENDING y se deduce de la red`() {
        assertEquals(DownloadWait.WIFI, downloadWait(listOf(pending, pending), allowMetered = false, Net.METERED))
        assertEquals(DownloadWait.NETWORK, downloadWait(listOf(pending), allowMetered = false, Net.NONE))
        assertEquals(DownloadWait.NETWORK, downloadWait(listOf(pending), allowMetered = true, Net.NONE))
        assertNull(downloadWait(listOf(pending), allowMetered = true, Net.METERED)) // a punto de empezar
        assertNull(downloadWait(listOf(pending), allowMetered = false, Net.UNMETERED))
    }

    @Test fun `en pausa manda el motivo de DownloadManager`() {
        assertEquals(DownloadWait.WIFI, downloadWait(listOf(STATUS_PAUSED to PAUSED_QUEUED_FOR_WIFI), false, Net.UNMETERED))
        assertEquals(DownloadWait.NETWORK, downloadWait(listOf(STATUS_PAUSED to PAUSED_WAITING_FOR_NETWORK), true, Net.METERED))
        assertEquals(DownloadWait.NETWORK, downloadWait(listOf(STATUS_PAUSED to PAUSED_WAITING_TO_RETRY, pending), true, Net.METERED))
    }

    @Test fun `si algun fichero baja, avanza`() {
        assertNull(downloadWait(listOf(STATUS_RUNNING to 0, pending), allowMetered = false, Net.METERED))
        assertNull(downloadWait(listOf(STATUS_SUCCESSFUL to 0, STATUS_RUNNING to 0), allowMetered = false, Net.UNMETERED))
    }
}

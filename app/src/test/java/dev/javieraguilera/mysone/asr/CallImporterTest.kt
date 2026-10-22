package dev.javieraguilera.mysone.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallImporterTest {
    private val now = 10_000_000L
    private fun rec(id: String, ageMs: Long, mime: String? = "audio/mp4", name: String? = "$id.m4a") =
        CallImporter.Recording(id, mime, name, now - ageMs)

    @Test fun `solo entran las no vistas y ya quietas, de la más antigua a la más reciente`() {
        val all = listOf(rec("b", 120_000), rec("a", 3_600_000), rec("vista", 600_000), rec("en-curso", 5_000))
        assertEquals(listOf("a", "b"), newRecordings(all, seen = setOf("vista"), since = 0L, now).map { it.documentId })
    }

    @Test fun `sin fecha no se importa`() {
        assertTrue(newRecordings(listOf(CallImporter.Recording("x", "audio/mp4", "x.m4a", 0L)), emptySet(), since = 0L, now).isEmpty())
    }

    @Test fun `audio por tipo o por extensión, nunca carpetas ni otros ficheros`() {
        assertTrue(isCallAudio(rec("a", 0)))
        assertTrue(isCallAudio(rec("b", 0, mime = "application/octet-stream", name = "Llamada_250630_191709.M4A")))
        assertFalse(isCallAudio(rec("c", 0, mime = "vnd.android.document/directory", name = "sub.m4a")))
        assertFalse(isCallAudio(rec("d", 0, mime = "text/plain", name = "notas.txt")))
        assertFalse(isCallAudio(rec("e", 0, name = ".trashed-1767225600-Llamada_250630_191709.m4a")))
        assertFalse(isCallAudio(rec("f", 0, name = ".pending-1767225600-Llamada_250630_191709.m4a")))
    }

    @Test fun `con «Solo las nuevas» lo de antes no entra nunca, aunque salga y vuelva de la papelera`() {
        val vieja = rec("vieja", 3_600_000)
        val nueva = rec("nueva", 120_000)
        val since = vieja.modified // la más reciente de las que había al elegir la carpeta
        assertEquals(listOf("nueva"), newRecordings(listOf(vieja, nueva), seen = emptySet(), since, now).map { it.documentId })
    }
}

package dev.javieraguilera.mysone.capture

import dev.javieraguilera.mysone.asr.ModelInstall
import dev.javieraguilera.mysone.data.GapDao
import dev.javieraguilera.mysone.data.GapReason
import dev.javieraguilera.mysone.data.ListeningGap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningLogicTest {
    private class FakeGaps : GapDao {
        val rows = mutableListOf<ListeningGap>()
        override suspend fun insert(g: ListeningGap): Long = (rows.size + 1L).also { rows += g.copy(id = it) }
        override suspend fun setEnded(id: Long, endedAt: Long) {
            rows.replaceAll { if (it.id == id) it.copy(endedAt = endedAt) else it }
        }
        override suspend fun delete(id: Long) {
            rows.removeAll { it.id == id }
        }
        override suspend fun findOpen() = rows.filter { it.endedAt == null }.maxByOrNull { it.startedAt }
        override fun observeRange(from: Long, to: Long): Flow<List<ListeningGap>> = flowOf(rows)
        override suspend fun all() = rows.toList()
    }

    @Test fun `un hueco de menos de 10 s no se guarda y uno más largo se cierra`() = runTest {
        val gaps = FakeGaps()
        openGap(gaps, GapReason.STOPPED, at = 0)
        closeGap(gaps, gaps.findOpen()!!, at = MIN_GAP_MS - 1) // detener y volver a escuchar enseguida
        assertEquals(emptyList<ListeningGap>(), gaps.all())

        openGap(gaps, GapReason.PAUSED, at = 100_000)
        openGap(gaps, GapReason.STOPPED, at = 105_000) // abrir otro cierra el anterior: 5 s, fuera
        closeGap(gaps, gaps.findOpen()!!, at = 105_000 + MIN_GAP_MS)
        assertEquals(listOf(Triple(GapReason.STOPPED, 105_000L, 105_000L + MIN_GAP_MS)), gaps.all().map { Triple(it.reason, it.startedAt, it.endedAt) })
    }

    @Test fun `falta el modelo pasa a detenida en cuanto hay uno instalado`() = runTest {
        val installs = MutableStateFlow<Map<String, ModelInstall>>(mapOf("a" to ModelInstall.NotInstalled))
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { ListeningController.leaveNeedsModelOnInstall(installs) }
        ListeningController.publish(ListeningStatus.NeedsModel)
        installs.value = mapOf("a" to ModelInstall.Downloading(0.5f))
        assertEquals(ListeningStatus.NeedsModel, ListeningController.status.value)
        installs.value = mapOf("a" to ModelInstall.Installed)
        assertEquals(ListeningStatus.Off, ListeningController.status.value)
        // Con un modelo instalado, otro estado no se toca.
        ListeningController.publish(ListeningStatus.Starting)
        installs.value = mapOf("a" to ModelInstall.Installed, "b" to ModelInstall.Installed)
        assertEquals(ListeningStatus.Starting, ListeningController.status.value)
        job.cancel()
        ListeningController.publish(ListeningStatus.Off)
    }
}

package dev.javieraguilera.mysone.capture

import dev.javieraguilera.mysone.data.ConvSource
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.Conversation
import dev.javieraguilera.mysone.data.ConversationDao
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val S = 1_000L
private const val MIN = 60 * S

/** Hora base arbitraria: 15-oct-2026 10:00 UTC. */
private const val T = 1_792_058_400_000L

class ConversationTrackerTest {
    private val dao = FakeConversationDao()
    private var gapMin = 4L
    private val ready = mutableListOf<Long>()
    private val tracker = ConversationTracker(dao, { gapMin * MIN }, { ready += it })

    private fun conv(id: Long) = dao.rows.getValue(id)

    @Test fun `el primer trozo abre una conversación del micro`() = runTest {
        val id = tracker.assign(T, T + 5 * S)
        val c = conv(id)
        assertEquals(ConvState.OPEN, c.state)
        assertEquals(ConvSource.MIC, c.source)
        assertEquals(T, c.startedAt)
        assertEquals(T + 5 * S, c.endedAt)
        assertTrue(ready.isEmpty())
    }

    @Test fun `un silencio menor que el corte sigue la misma conversación`() = runTest {
        val id = tracker.assign(T, T + 5 * S)
        assertEquals(id, tracker.assign(T + 3 * MIN, T + 3 * MIN + 10 * S))
        assertEquals(T + 3 * MIN + 10 * S, conv(id).endedAt)
        assertEquals(T, conv(id).startedAt)
        assertEquals(1, dao.rows.size)
    }

    @Test fun `un silencio justo igual al corte todavía no corta`() = runTest {
        val id = tracker.assign(T, T + 5 * S)
        assertEquals(id, tracker.assign(T + 5 * S + 4 * MIN, T + 5 * S + 4 * MIN + S))
    }

    @Test fun `un silencio mayor que el corte abre otra y deja la anterior READY`() = runTest {
        val first = tracker.assign(T, T + 5 * S)
        val second = tracker.assign(T + 5 * S + 4 * MIN + 1, T + 6 * MIN)
        assertNotEquals(first, second)
        assertEquals(ConvState.READY, conv(first).state)
        assertEquals(ConvState.OPEN, conv(second).state)
        assertEquals(listOf(first), ready)
    }

    @Test fun `el temporizador no cierra antes del corte y sí al cumplirse`() = runTest {
        val id = tracker.assign(T, T + 5 * S)
        tracker.closeIfIdle(T + 5 * S + 4 * MIN - 1)
        assertEquals(ConvState.OPEN, conv(id).state)
        tracker.closeIfIdle(T + 5 * S + 4 * MIN)
        assertEquals(ConvState.READY, conv(id).state)
        assertEquals(listOf(id), ready)
        // Cerrada por el temporizador: lo siguiente, aunque esté cerca, es otra conversación.
        assertNotEquals(id, tracker.assign(T + 5 * S + 4 * MIN + S, T + 5 * MIN))
    }

    @Test fun `si el reloj retrocede tras la abierta el temporizador la cierra sin esperar a la hora vieja`() = runTest {
        val id = tracker.assign(T + 29 * MIN, T + 29 * MIN + 10 * S) // reloj adelantado 29 min
        tracker.closeIfIdle(T + 29 * MIN + 10 * S - 4 * S) // deriva de pocos segundos entre relojes: no es un salto
        assertEquals(ConvState.OPEN, conv(id).state)
        tracker.closeIfIdle(T + 30 * S) // puesto en hora
        assertEquals(ConvState.READY, conv(id).state)
        assertEquals(listOf(id), ready)
    }

    @Test fun `el temporizador sin conversación abierta no hace nada`() = runTest {
        tracker.closeIfIdle(T)
        tracker.close()
        assertTrue(dao.rows.isEmpty())
        assertTrue(ready.isEmpty())
    }

    @Test fun `cerrar al detener la deja READY una sola vez`() = runTest {
        val id = tracker.assign(T, T + 5 * S)
        tracker.close()
        tracker.close()
        tracker.closeIfIdle(T + 60 * MIN)
        assertEquals(ConvState.READY, conv(id).state)
        assertEquals(listOf(id), ready)
        assertNotEquals(id, tracker.assign(T + 10 * S, T + 20 * S))
    }

    @Test fun `al arrancar las OPEN huérfanas del micro pasan a READY y las importaciones no`() = runTest {
        val orphan = dao.insert(Conversation(startedAt = T, endedAt = T + S, source = ConvSource.MIC, state = ConvState.OPEN))
        val importing = dao.insert(Conversation(startedAt = T, endedAt = T + S, source = ConvSource.IMPORT, state = ConvState.OPEN))
        val done = dao.insert(Conversation(startedAt = T, endedAt = T + S, source = ConvSource.MIC, state = ConvState.DONE))
        tracker.recoverOrphans()
        assertEquals(ConvState.READY, conv(orphan).state)
        assertEquals(ConvState.OPEN, conv(importing).state)
        assertEquals(ConvState.DONE, conv(done).state)
        assertEquals(listOf(orphan), ready)
        // La huérfana no se reutiliza aunque el trozo nuevo esté pegado a ella.
        assertNotEquals(orphan, tracker.assign(T + 2 * S, T + 3 * S))
    }

    @Test fun `un trozo algo anterior al inicio se queda y adelanta el inicio`() = runTest {
        val id = tracker.assign(T + 10 * MIN, T + 10 * MIN + 30 * S)
        assertEquals(id, tracker.assign(T + 8 * MIN, T + 8 * MIN + 20 * S))
        assertEquals(T + 8 * MIN, conv(id).startedAt)
        assertEquals(T + 10 * MIN + 30 * S, conv(id).endedAt) // el final no retrocede
        assertTrue(ready.isEmpty())
    }

    @Test fun `un trozo solapado dentro de la conversación no cambia nada`() = runTest {
        val id = tracker.assign(T, T + 60 * S)
        val before = conv(id)
        assertEquals(id, tracker.assign(T + 10 * S, T + 20 * S))
        assertEquals(before, conv(id))
    }

    @Test fun `un trozo muy anterior (reloj atrasado) abre otra conversación`() = runTest {
        val id = tracker.assign(T + 60 * MIN, T + 60 * MIN + 5 * S)
        val earlier = tracker.assign(T, T + 5 * S)
        assertNotEquals(id, earlier)
        assertEquals(ConvState.READY, conv(id).state)
        assertEquals(T, conv(earlier).startedAt)
        // Lo siguiente sigue el reloj nuevo.
        assertEquals(earlier, tracker.assign(T + MIN, T + MIN + S))
    }

    @Test fun `si la abierta se borra por fuera se abre otra sin avisar de la borrada`() = runTest {
        val id = tracker.assign(T, T + 5 * S)
        dao.rows.remove(id) // "Olvidar los últimos N min"
        val next = tracker.assign(T + 10 * S, T + 15 * S)
        assertNotEquals(id, next)
        assertTrue(ready.isEmpty())
        tracker.closeIfIdle(T + 60 * MIN)
        assertEquals(listOf(next), ready)
    }

    @Test fun `si la abierta cambia de estado por fuera no se reutiliza`() = runTest {
        val id = tracker.assign(T, T + 5 * S)
        dao.setState(id, ConvState.READY)
        assertNotEquals(id, tracker.assign(T + 10 * S, T + 15 * S))
        assertTrue(ready.isEmpty())
    }

    @Test fun `el corte se lee en cada trozo`() = runTest {
        val id = tracker.assign(T, T + 5 * S)
        gapMin = 2
        val next = tracker.assign(T + 5 * S + 3 * MIN, T + 4 * MIN)
        assertNotEquals(id, next)
        gapMin = 15
        assertEquals(next, tracker.assign(T + 4 * MIN + 14 * MIN, T + 19 * MIN))
    }
}

/** Solo lo que usa el tracker; el resto del DAO lanza con el nombre del método si algún día se usa. */
private class FakeConversationDao : ConversationDao by unsupported() {
    val rows = linkedMapOf<Long, Conversation>()
    private var nextId = 1L

    override suspend fun insert(c: Conversation): Long {
        val id = nextId++
        rows[id] = c.copy(id = id)
        return id
    }

    override suspend fun update(c: Conversation) { if (c.id in rows) rows[c.id] = c }
    override suspend fun get(id: Long): Conversation? = rows[id]
    override suspend fun byState(state: String) = rows.values.filter { it.state == state }.sortedBy { it.startedAt }
    override suspend fun setState(id: Long, state: String) { rows[id]?.let { rows[id] = it.copy(state = state) } }
}

private inline fun <reified T> unsupported(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        throw UnsupportedOperationException(m.name)
    } as T

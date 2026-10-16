package dev.javieraguilera.mysone.data

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** DAOs y FTS5 sobre Room en memoria con el mismo driver que la app (§11). */
@RunWith(AndroidJUnit4::class)
class DaosTest {
    private lateinit var db: AppDatabase

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(InstrumentationRegistry.getInstrumentation().targetContext)
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @After
    fun close() = db.close()

    private suspend fun conversation(state: String = ConvState.READY, title: String? = null, at: Long = T0) =
        db.conversations().insert(
            Conversation(startedAt = at, endedAt = at + 60_000, source = ConvSource.MIC, state = state, title = title),
        )

    private suspend fun segment(conversationId: Long, text: String, at: Long = T0) =
        db.segments().insert(Segment(conversationId = conversationId, startedAt = at, endedAt = at + 5_000, text = text))

    private suspend fun search(q: String) = db.segments().search(FtsQuery.all(q)!!, 0, Long.MAX_VALUE, 10)

    /** Falla si el índice FTS5 no cuadra con su tabla de contenido (p. ej. triggers que no borran). */
    private suspend fun ftsIntegrity() = db.useWriterConnection {
        it.executeSQL("INSERT INTO segment_fts(segment_fts, rank) VALUES('integrity-check', 1)")
        it.executeSQL("INSERT INTO conversation_fts(conversation_fts, rank) VALUES('integrity-check', 1)")
    }

    @Test
    fun insertsConversationWithSegments() = runTest {
        val id = conversation(state = ConvState.OPEN)
        segment(id, "segundo", T0 + 10_000)
        segment(id, "primero", T0)

        assertEquals(listOf("primero", "segundo"), db.segments().textForConversation(id).map { it.text })
        assertEquals(2, db.segments().countForConversation(id))
        assertEquals(id, db.conversations().findOpen()?.id)
        assertEquals(ConvSource.MIC, db.conversations().get(id)?.source)
        ftsIntegrity()
    }

    @Test
    fun searchIgnoresAccentsAndCase() = runTest {
        val id = conversation()
        segment(id, "Recuérdame mañana a las diez llamar al dentista")
        segment(id, "La reunión con el cliente de Alicante es el jueves", T0 + 10_000)

        assertEquals(1, search("dentista").size)
        assertEquals(1, search("reunion").size)
        assertEquals(1, search("Reunión").size)
        assertEquals(1, search("recuerdame manana").size)
        assertEquals(1, search("ALICANTE jueves").size)
        assertEquals(1, search("denti").size) // prefijo
        assertTrue(search("dentista jueves").isEmpty()) // AND: no hay un segmento con las dos
        assertTrue(search("Granada").isEmpty())
    }

    @Test
    fun snippetMarksTermsWithGuillemets() = runTest {
        val id = conversation()
        val segId = segment(id, "La reunión con el cliente de Alicante es el jueves a las cinco")

        val hit = search("reunion").single()
        assertEquals(segId, hit.segmentId)
        assertEquals(id, hit.conversationId)
        assertEquals(T0, hit.startedAt)
        assertTrue(hit.snippet, hit.snippet.contains("«reunión»"))
        assertTrue(hit.snippet, search("alicante").single().snippet.contains("«Alicante»"))
    }

    @Test
    fun bm25PutsBestMatchFirst() = runTest {
        val id = conversation()
        // El peor va primero: sin ORDER BY bm25 saldría delante por rowid.
        val weak = segment(id, "Hay que comprar pan y leche y pasar por la farmacia antes de las ocho de la tarde")
        val strong = segment(id, "Pan, pan y más pan", T0 + 10_000)

        assertEquals(listOf(strong, weak), search("pan").map { it.segmentId })
    }

    @Test
    fun searchRespectsTimeRange() = runTest {
        val id = conversation()
        segment(id, "llamar al dentista", T0)
        segment(id, "otra vez el dentista", T0 + 3_600_000)

        val q = FtsQuery.all("dentista")!!
        assertEquals(1, db.segments().search(q, T0, T0 + 3_600_000, 10).size)
        assertEquals(2, db.segments().search(q, 0, Long.MAX_VALUE, 10).size)
    }

    @Test
    fun conversationFtsFollowsUpdates() = runTest {
        val id = conversation(title = "Reunión con cliente")
        val q = FtsQuery.all("reunion")!!
        assertEquals(listOf(id), db.conversations().searchFts(q, 0, Long.MAX_VALUE, 10).map { it.id })

        val c = db.conversations().get(id)!!
        db.conversations().update(c.copy(title = "Hotel en Granada", summary = "Alhambra Palace del 12 al 14"))
        assertTrue(db.conversations().searchFts(q, 0, Long.MAX_VALUE, 10).isEmpty())
        val alhambra = FtsQuery.all("alhambra")!!
        assertEquals(listOf(id), db.conversations().searchFts(alhambra, 0, Long.MAX_VALUE, 10).map { it.id })

        db.conversations().setState(id, ConvState.DONE) // otra UPDATE: el índice no debe duplicarse
        assertEquals(1, db.conversations().searchFts(alhambra, 0, Long.MAX_VALUE, 10).size)
        ftsIntegrity()
    }

    @Test
    fun deletingConversationCascades() = runTest {
        val id = conversation()
        val other = conversation(at = T0 + 3_600_000)
        segment(id, "llamar al dentista")
        segment(other, "comprar pan")
        db.tasks().insert(TaskItem(text = "Llamar al dentista", state = TaskState.OPEN, conversationId = id, createdAt = T0))
        db.facts().insert(Fact(text = "Va al dentista", state = FactState.SUGGESTED, conversationId = id, createdAt = T0))
        db.pendingAudio().insert(PendingAudio(conversationId = id, startedAt = T0, endedAt = T0 + 1_000, path = "x.pcm"))
        val manual = db.tasks().insert(TaskItem(text = "Comprar leche", state = TaskState.OPEN, createdAt = T0))

        db.conversations().delete(id)

        assertNull(db.conversations().get(id))
        assertEquals(0, db.segments().countForConversation(id))
        assertEquals(listOf(manual), db.tasks().all().map { it.id })
        assertTrue(db.facts().all().isEmpty())
        assertEquals(0, db.pendingAudio().countForConversation(id))
        assertTrue(search("dentista").isEmpty())
        assertEquals(1, search("pan").size) // la otra conversación sigue intacta
        ftsIntegrity()
    }

    @Test
    fun readyWithoutPendingSkipsQueuedAudioAndOtherStates() = runTest {
        val ready = conversation(ConvState.READY, at = T0)
        val queued = conversation(ConvState.READY, at = T0 + 1_000)
        conversation(ConvState.OPEN, at = T0 + 2_000)
        conversation(ConvState.DONE, at = T0 + 3_000)
        val p = db.pendingAudio().insert(PendingAudio(conversationId = queued, startedAt = T0, endedAt = T0 + 1, path = "x.pcm"))

        assertEquals(listOf(ready), db.conversations().readyWithoutPending().map { it.id })

        db.pendingAudio().delete(p)
        assertEquals(listOf(ready, queued), db.conversations().readyWithoutPending().map { it.id })
    }

    private companion object {
        const val T0 = 1_760_000_000_000L
    }
}

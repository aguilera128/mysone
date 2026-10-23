package dev.javieraguilera.mysone.data

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Migraciones de Room con los esquemas de app/schemas (§3). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // El helper no borra la base de una ejecución anterior (la app no se desinstala entre ejecuciones): sin esto,
    // createDatabase(1) la abre y falla con «A migration should never occur while creating a new database».
    init {
        instrumentation.targetContext.deleteDatabase("migration-test.db")
    }

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation,
        instrumentation.targetContext.getDatabasePath("migration-test.db"),
        BundledSQLiteDriver(),
        AppDatabase::class,
    )

    @Test
    fun v1aV2ConservaLaTranscripcionYSuBusquedaYAnadeElHablante() = runTest {
        helper.createDatabase(1).use { c ->
            c.execSQL("INSERT INTO conversation (id, startedAt, endedAt, source, state, isNoise, processAttempts) VALUES (1, 0, 60000, 'CALL', 'READY', 0, 0)")
            c.execSQL("INSERT INTO segment (id, conversationId, startedAt, endedAt, text) VALUES (1, 1, 0, 5000, 'el presupuesto de la cocina')")
        }
        // Valida contra 2.json: columnas, índices, FTS y sus triggers.
        helper.runMigrationsAndValidate(2).use { c ->
            c.prepare("SELECT text, speaker FROM segment WHERE id = 1").use {
                assertTrue(it.step())
                assertEquals("el presupuesto de la cocina", it.getText(0))
                assertTrue(it.isNull(1))
            }
            c.execSQL("INSERT INTO segment (conversationId, startedAt, endedAt, text, speaker) VALUES (1, 6000, 8000, 'y el fontanero', 2)")
            c.prepare("SELECT rowid FROM segment_fts WHERE segment_fts MATCH 'presupuesto OR fontanero' ORDER BY rowid").use {
                assertTrue(it.step())
                assertEquals(1L, it.getLong(0))
                assertTrue(it.step())
                assertEquals(2L, it.getLong(0))
            }
            c.execSQL("INSERT INTO segment_fts(segment_fts, rank) VALUES('integrity-check', 1)")
        }
    }

    @Test
    fun v2aV3ConservaLasTareasYLasDejaComoNoRapidas() = runTest {
        helper.createDatabase(2).use { c ->
            c.execSQL("INSERT INTO conversation (id, startedAt, endedAt, source, state, isNoise, processAttempts) VALUES (1, 0, 60000, 'MIC', 'DONE', 0, 0)")
            c.execSQL("INSERT INTO task (id, text, state, remindAt, conversationId, sourceQuote, createdAt) VALUES (1, 'llamar a Marta', 'OPEN', 90000, 1, 'recuérdame llamar a Marta', 10)")
        }
        // Valida contra 3.json; las tareas de antes no son recordatorios rápidos (DEFAULT 0).
        helper.runMigrationsAndValidate(3).use { c ->
            c.prepare("SELECT text, state, remindAt, sourceQuote, quick FROM task WHERE id = 1").use {
                assertTrue(it.step())
                assertEquals("llamar a Marta", it.getText(0))
                assertEquals("OPEN", it.getText(1))
                assertEquals(90000L, it.getLong(2))
                assertEquals("recuérdame llamar a Marta", it.getText(3))
                assertEquals(0L, it.getLong(4))
            }
            c.execSQL("INSERT INTO task (text, state, conversationId, createdAt, quick) VALUES ('comprar pan', 'OPEN', 1, 20, 1)")
            c.prepare("SELECT quick FROM task WHERE text = 'comprar pan'").use {
                assertTrue(it.step())
                assertEquals(1L, it.getLong(0))
            }
        }
    }

    @Test
    fun v1aV3EnCadena() = runTest {
        helper.createDatabase(1).use { c ->
            c.execSQL("INSERT INTO conversation (id, startedAt, endedAt, source, state, isNoise, processAttempts) VALUES (1, 0, 60000, 'MIC', 'DONE', 0, 0)")
            c.execSQL("INSERT INTO task (id, text, state, conversationId, createdAt) VALUES (1, 'pedir cita', 'OPEN', 1, 10)")
        }
        helper.runMigrationsAndValidate(3).use { c ->
            c.prepare("SELECT quick FROM task WHERE id = 1").use {
                assertTrue(it.step())
                assertEquals(0L, it.getLong(0))
            }
        }
    }
}

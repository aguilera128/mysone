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
}

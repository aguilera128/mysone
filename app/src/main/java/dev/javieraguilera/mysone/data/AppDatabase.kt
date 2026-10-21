package dev.javieraguilera.mysone.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        Conversation::class, Segment::class, SegmentFts::class, ConversationFts::class, PendingAudio::class,
        TaskItem::class, Fact::class, DailyDigest::class, ChatMessage::class, ListeningGap::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun segments(): SegmentDao
    abstract fun pendingAudio(): PendingAudioDao
    abstract fun tasks(): TaskDao
    abstract fun facts(): FactDao
    abstract fun digests(): DigestDao
    abstract fun chat(): ChatDao
    abstract fun gaps(): GapDao

    companion object {
        /** SQLite propio (BundledSQLiteDriver): el del sistema no garantiza FTS5. */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder<AppDatabase>(context, context.getDatabasePath("mysone.db").absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addCallback(SecureDeleteFts)
                .build()

        /**
         * FTS5 no quita del índice los términos de lo borrado: apunta el borrado y los conserva hasta fusionar
         * segmentos, así que lo olvidado se podría leer en `*_fts_data`. Con `secure-delete` se quitan al borrar
         * (06 §6.1.11). La opción se guarda en la propia BD; repetirla al abrir no cambia nada.
         */
        private object SecureDeleteFts : RoomDatabase.Callback() {
            override suspend fun onOpen(connection: SQLiteConnection) {
                for (table in listOf("segment_fts", "conversation_fts")) {
                    connection.execSQL("INSERT INTO $table($table, rank) VALUES('secure-delete', 1)")
                }
            }
        }
    }
}

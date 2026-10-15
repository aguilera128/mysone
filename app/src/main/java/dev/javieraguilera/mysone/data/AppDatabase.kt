package dev.javieraguilera.mysone.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
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
                .build()
    }
}

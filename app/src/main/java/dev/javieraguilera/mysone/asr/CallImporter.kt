package dev.javieraguilera.mysone.asr

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.javieraguilera.mysone.container
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Grabaciones de llamadas importadas solas (§5.5). Android no deja a ninguna app de la tienda captar el audio de una
 * llamada; la grabadora del propio teléfono sí (Samsung: Teléfono › Grabar llamadas, en Recordings/Call). El dueño
 * elige esa carpeta una vez y cada grabación nueva pasa por la misma importación que un audio elegido a mano, como
 * conversación CALL, y se borra al terminar si así lo tiene en Ajustes.
 */
object CallImporter {
    private const val TAG = "MysoneAsr"
    private const val PREFS = "calls"
    private const val KEY_SEEN = "seen" // documentId de las ya encoladas
    private const val KEY_SINCE = "since" // «Solo las nuevas»: las modificadas hasta aquí no entran nunca
    private const val KEY_TOKEN = "token" // una por cada vez que se elige la carpeta: lo encolado con otra ya no vale
    private const val WORK_WATCH = "call-watch"
    private const val WORK_LATER = "call-scan-later"

    /** Una grabación que ha cambiado hace menos es una llamada en curso, o una que aún se está cerrando. */
    internal const val SETTLE_MS = 60_000L

    /** Lo que el selector propone de entrada: la carpeta de la grabadora de Samsung. */
    val SAMSUNG_FOLDER: Uri get() = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Recordings/Call")

    internal data class Recording(val documentId: String, val mime: String?, val name: String?, val modified: Long)

    private val lock = Mutex() // escaneos de MaintenanceWorker, del disparador y de Ajustes a la vez: una sola vez cada fichero

    /** "Recordings/Call" a partir del árbol elegido, para enseñarlo en Ajustes. */
    fun folderName(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "/" }

    /** Grabaciones que hay ahora en [tree], o null si ya no se puede leer (permiso retirado, carpeta borrada). */
    internal suspend fun recordings(context: Context, tree: Uri): List<Recording>? = withContext(Dispatchers.IO) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        try {
            context.contentResolver.query(
                children,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED),
                null, null, null,
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Recording(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) 0L else c.getLong(3)))
                    }
                }.filter(::isCallAudio)
            }
        } catch (e: Exception) { // SecurityException, IllegalArgumentException, FileNotFoundException del proveedor
            Log.w(TAG, "Carpeta de llamadas ilegible: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Las de [tree] que aún no tienen conversación (para preguntar al elegirla), o null si no se puede leer. */
    suspend fun pendingCount(context: Context, tree: Uri): Int? = recordings(context, tree)?.count { !imported(context, it.modified) }

    /**
     * Si ya hay una llamada importada de una grabación con esta fecha de modificación: la conversación termina justo
     * en ella (ImportWorker), también si se guardó solo en parte, y en ms no se repite entre grabaciones.
     */
    internal suspend fun imported(context: Context, modified: Long): Boolean =
        modified > 0 && context.container.db.conversations().importsEndingAt(modified).isNotEmpty() // CALL o a mano (IMPORT)

    /** La importación de [uri] falló sin tocarla (no cargó el modelo): que el próximo escaneo la vuelva a encolar. */
    suspend fun unsee(context: Context, uri: Uri) {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return
        lock.withLock {
            val p = prefs(context)
            p.edit { putStringSet(KEY_SEEN, p.getStringSet(KEY_SEEN, emptySet()).orEmpty() - id) }
        }
    }

    /** Si [token] (el de una llamada encolada) es el de la carpeta elegida ahora. */
    fun isCurrent(context: Context, token: String): Boolean = prefs(context).getString(KEY_TOKEN, null) == token

    /**
     * El dueño ha elegido [tree] (OpenDocumentTree) y ha dicho qué hacer con lo que ya hay: se guarda el permiso de
     * leer y borrar y se empieza a vigilar. Con [importExisting] = false, las grabaciones que ya hay quedan como vistas
     * y solo entran las nuevas. False si el permiso temporal del selector ya no está o la carpeta no se puede leer.
     */
    suspend fun setFolder(context: Context, tree: Uri, importExisting: Boolean): Boolean {
        val c = context.container
        lock.withLock {
            val all = recordings(context, tree) ?: return false // ilegible no es vacía: «solo las nuevas» se las llevaría todas
            try {
                context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: SecurityException) {
                return false
            }
            prefs(context).edit {
                clear()
                putString(KEY_TOKEN, UUID.randomUUID().toString())
                if (!importExisting) putLong(KEY_SINCE, minOf(all.maxOfOrNull { it.modified } ?: 0L, System.currentTimeMillis()))
            }
            val old = c.settings.current().callFolder
            c.settings.update { it.copy(callFolder = tree.toString()) }
            if (old != null && old != tree.toString()) release(context, old.toUri()) // se ha cambiado de carpeta
        }
        watch(context)
        scan(context)
        return true
    }

    /**
     * Deja de importar: suelta el permiso de la carpeta y para el vigilante. Lo ya importado se queda; lo que estaba en
     * cola, ImportWorker lo da por cancelado ([isCurrent]). También al empezar "Borrar todo".
     */
    suspend fun clearFolder(context: Context) {
        val c = context.container
        lock.withLock { // tras un escaneo en marcha, que podría volver a encolar y escribir la lista
            val folder = c.settings.current().callFolder
            c.settings.update { it.copy(callFolder = null) }
            folder?.let { release(context, it.toUri()) }
            WorkManager.getInstance(context).run {
                cancelUniqueWork(WORK_WATCH)
                cancelUniqueWork(WORK_LATER)
            }
            prefs(context).edit { clear() }
        }
    }

    /** Encola las grabaciones nuevas y ya cerradas de la carpeta elegida. Sin carpeta o sin modelo, nada. */
    suspend fun scan(context: Context) {
        val c = context.container
        lock.withLock { // los ajustes se leen dentro: «Dejar de importar» puede haber pasado mientras se esperaba
            val settings = c.settings.current()
            val tree = settings.callFolder?.toUri() ?: return
            // Sin modelo la importación fallaría y la grabación quedaría como vista: mejor esperar a que lo haya.
            if (c.models.installs.value[settings.asrModelId] !is ModelInstall.Installed) return
            val all = recordings(context, tree) ?: return
            val prefs = prefs(context)
            // Las que ya no están (borradas tras importarlas, o a mano) sobran en la lista.
            val present = all.map { it.documentId }.toSet()
            val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().filter { it in present }.toMutableSet()
            val now = System.currentTimeMillis()
            val token = prefs.getString(KEY_TOKEN, null) ?: return
            for (r in newRecordings(all, seen, prefs.getLong(KEY_SINCE, 0L), now)) {
                seen += r.documentId
                // Ya transcrita: carpeta elegida otra vez, o grabación recuperada de la papelera.
                if (!imported(context, r.modified)) {
                    val op = AudioImporter.enqueue(context, DocumentsContract.buildDocumentUriUsingTree(tree, r.documentId), callToken = token)
                    withContext(Dispatchers.IO) { op.result.get() } // ya en la BD de WorkManager: ahora sí, vista
                }
                prefs.edit { putStringSet(KEY_SEEN, seen.toSet()) } // si muere antes, se repite y ImportWorker la descarta
            }
            prefs.edit { putStringSet(KEY_SEEN, seen.toSet()) } // también poda las que ya no están
            // Una llamada que acaba de terminar aún no está quieta: se vuelve a mirar cuando lo esté.
            if (all.any { it.documentId !in seen && now - it.modified in 0 until SETTLE_MS }) scanLater(context)
        }
    }

    /**
     * Vigila MediaStore: la grabadora del teléfono indexa cada grabación al cerrarla. MaintenanceWorker cubre lo que
     * se escape. KEEP al arrancar la app o elegir carpeta (puede haber uno esperando); el propio trabajo, al acabar,
     * se pone detrás de sí mismo con APPEND_OR_REPLACE (con KEEP no haría nada mientras sigue en marcha).
     */
    fun watch(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = OneTimeWorkRequestBuilder<CallScanWorker>()
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_WATCH, policy, request)
    }

    private fun scanLater(context: Context) {
        val request = OneTimeWorkRequestBuilder<CallScanWorker>()
            .setInitialDelay(SETTLE_MS + 10_000, TimeUnit.MILLISECONDS)
            .addTag(TAG_LATER)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_LATER, ExistingWorkPolicy.REPLACE, request)
    }

    internal const val TAG_LATER = "call-later" // el escaneo diferido no vuelve a armar el vigilante

    internal fun release(context: Context, tree: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "amr", "3gp", "aac", "wav", "ogg", "opus")

/** Audio por tipo o, si el proveedor no lo da, por extensión; nunca una subcarpeta ni un fichero oculto. */
internal fun isCallAudio(r: CallImporter.Recording): Boolean =
    r.name?.startsWith('.') != true && // .trashed-… y .pending-… que deja MediaStore en la carpeta
        r.mime != Document.MIME_TYPE_DIR &&
        (r.mime?.startsWith("audio/") == true || r.name?.substringAfterLast('.', "")?.lowercase() in AUDIO_EXTENSIONS)

/** Las de [all] sin encolar, posteriores a [since] y quietas desde hace [CallImporter.SETTLE_MS], de la más antigua a la más reciente. */
internal fun newRecordings(all: List<CallImporter.Recording>, seen: Set<String>, since: Long, now: Long): List<CallImporter.Recording> =
    all.filter { it.documentId !in seen && it.modified > since && now - it.modified >= CallImporter.SETTLE_MS }.sortedBy { it.modified }

/** Escanea la carpeta de llamadas cuando cambia MediaStore (y vuelve a quedarse esperando) o un rato después de una llamada. */
class CallScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CallImporter.scan(applicationContext)
        if (CallImporter.TAG_LATER !in tags && applicationContext.container.settings.current().callFolder != null) {
            CallImporter.watch(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }
}

package dev.javieraguilera.mysone.asr

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.format.Formatter
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.javieraguilera.mysone.R
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AsrModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val recommendedMinRamGb: Int,
    val license: String,
    val files: List<ModelFile>,
)

data class ModelFile(val name: String, val url: String, val sizeBytes: Long, val sha256: String?)

enum class DownloadWait { WIFI, NETWORK }

sealed interface ModelInstall {
    data object NotInstalled : ModelInstall
    /** [waiting]: no avanza porque falta Wi-Fi o red (o espera para reintentar); ver [downloadWait]. */
    data class Downloading(val progress: Float, val waiting: DownloadWait? = null) : ModelInstall
    data object Verifying : ModelInstall
    data object Installed : ModelInstall
    data class Failed(val message: String) : ModelInstall
}

/**
 * Descarga, verificación y borrado de modelos ASR (§5.4). DownloadManager baja cada fichero a
 * `getExternalFilesDir("models-tmp")/<id>/`; al terminar se copia a `filesDir/models/<id>/` calculando
 * el SHA-256 al vuelo y solo se da por bueno si cuadran tamaño y hash. Los IDs de descarga se guardan en
 * SharedPreferences para retomar el seguimiento si el proceso muere mientras DownloadManager sigue bajando.
 */
class ModelManager(private val context: Context) {

    val catalog: List<AsrModel> = listOf(
        // HF csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8 (docs/investigacion/03 §4.2)
        model(
            id = "canary-180m-es",
            name = R.string.model_canary_name,
            description = R.string.model_canary_desc,
            minRamGb = 4,
            repo = "csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8",
            commit = "9077164e0d3dd1d5353743e89ceaa1d3a770838c",
            files = listOf(
                Triple("encoder.int8.onnx", 132_678_643L, "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
                Triple("decoder.int8.onnx", 74_437_848L, "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
                Triple("tokens.txt", 53_555L, null), // no va por LFS: HF no publica su SHA-256, solo el tamaño
            ),
        ),
        // HF csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 (docs/investigacion/03 §4.2)
        model(
            id = "parakeet-v3",
            name = R.string.model_parakeet_name,
            description = R.string.model_parakeet_desc,
            minRamGb = PARAKEET_MIN_RAM_GB,
            repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            commit = "2bda32ec70b097a55adaa07d9a7173915b43cc78",
            files = listOf(
                Triple("encoder.int8.onnx", 652_184_281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                Triple("decoder.int8.onnx", 11_845_275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                Triple("joiner.int8.onnx", 6_355_277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                Triple("tokens.txt", 93_939L, null),
            ),
        ),
    )

    /**
     * Huellas de voz para saber quién habla en lo importado (§5.6): ERes2Net de 3D-Speaker entrenado con VoxCeleb, el
     * único de los probados que separa bien voces en español también con calidad de teléfono (docs/investigacion/09).
     * Va aparte de [catalog] e [installs]: no transcribe, y la escucha y la elección de modelo no deben contarlo.
     */
    val speakerModel: AsrModel = model(
        id = SPEAKER_MODEL_ID,
        name = R.string.model_speaker_name,
        description = R.string.model_speaker_desc,
        minRamGb = 0,
        repo = "csukuangfj/speaker-embedding-models",
        commit = "0743f301363dec56491a490f6d6cbc9d67f9a3bf",
        files = listOf(
            Triple("3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx", 26_485_263L, "c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5"),
        ),
        license = "Apache-2.0",
    )

    private val all = catalog + speakerModel

    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("asr_downloads", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    // sherpa-onnx lee los ficheros del modelo uno a uno al crear el reconocedor: si se borran a medias, la sesión
    // de ONNX lanza una excepción nativa que cruza la JNI y mata el proceso (con la escucha). Borrar espera.
    // ponytail: un cerrojo para todos los modelos; uno por modelo si alguna vez se cargan dos a la vez.
    private val files = Mutex()

    private val _installs = MutableStateFlow(
        catalog.associate { m ->
            m.id to when {
                isInstalled(m.id) -> ModelInstall.Installed
                prefs.contains(m.id) -> ModelInstall.Downloading(0f)
                else -> ModelInstall.NotInstalled
            }
        },
    )
    val installs: StateFlow<Map<String, ModelInstall>> = _installs.asStateFlow()

    private val _speakerInstall = MutableStateFlow(
        when {
            isInstalled(SPEAKER_MODEL_ID) -> ModelInstall.Installed
            prefs.contains(SPEAKER_MODEL_ID) -> ModelInstall.Downloading(0f)
            else -> ModelInstall.NotInstalled
        },
    )
    val speakerInstall: StateFlow<ModelInstall> = _speakerInstall.asStateFlow()

    init {
        all.filter { prefs.contains(it.id) }.forEach(::watch)
    }

    fun download(id: String, allowMetered: Boolean) {
        val model = all.find { it.id == id } ?: return
        if (jobs[id]?.isActive == true || isInstalled(id)) return
        val tmp = tmpDir(id) ?: return fail(id, context.getString(R.string.asr_error_storage))
        val missing = model.files.filter { File(modelDir(id), it.name).length() != it.sizeBytes }
        val needed = missing.sumOf { it.sizeBytes } * 2 // temporal + definitivo
        if (context.filesDir.usableSpace < needed) {
            return fail(id, context.getString(R.string.asr_error_space, Formatter.formatShortFileSize(context, needed)))
        }
        val ids = try {
            missing.map { f ->
                File(tmp, f.name).delete() // si existe, DownloadManager renombra el destino
                downloads.enqueue(
                    DownloadManager.Request(f.url.toUri())
                        .setDestinationInExternalFilesDir(context, TMP_DIR, "$id/${f.name}")
                        .setAllowedOverMetered(allowMetered)
                        .setTitle(context.getString(if (id == SPEAKER_MODEL_ID) R.string.asr_download_title_speaker else R.string.asr_download_title, model.name)),
                )
            }
        } catch (e: RuntimeException) { // DownloadManager desactivado, almacenamiento no disponible…
            Log.w(TAG, "No se pudo encolar la descarga: ${e.javaClass.simpleName}")
            return fail(id, context.getString(R.string.asr_error_download_start))
        }
        prefs.edit {
            putString(id, ids.joinToString(","))
            putBoolean(meteredKey(id), allowMetered)
        }
        setState(id, ModelInstall.Downloading(0f))
        watch(model)
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        val ids = downloadIds(id)
        if (ids.isNotEmpty()) downloads.remove(*ids)
        forget(id)
        tmpDir(id)?.deleteRecursively()
        setState(id, if (isInstalled(id)) ModelInstall.Installed else ModelInstall.NotInstalled)
    }

    /** Espera a que acabe quien esté cargando el modelo (autoprueba, escucha, importación). */
    suspend fun delete(id: String) {
        cancel(id)
        files.withLock { withContext(Dispatchers.IO) { modelDir(id).deleteRecursively() } }
        setState(id, ModelInstall.NotInstalled)
    }

    /** Reconocedor nuevo con los ficheros de [model], sin que nadie los borre mientras se leen. */
    internal suspend fun openRecognizer(model: AsrModel): OfflineRecognizer = files.withLock { createRecognizer(model, modelDir(model.id)) }

    /**
     * Extractor de huellas de voz (§5.6), igual que [openRecognizer]: nadie borra el modelo mientras se lee. null si no
     * está instalado. Se comprueba el tamaño antes: un modelo roto en sherpa-onnx mata el proceso en vez de lanzar.
     */
    internal suspend fun openSpeakerExtractor(): SpeakerEmbeddingExtractor? = files.withLock {
        val dir = modelDir(SPEAKER_MODEL_ID)
        if (!speakerModel.hasFilesIn(dir)) return@withLock null
        SpeakerEmbeddingExtractor(
            assetManager = null,
            config = SpeakerEmbeddingExtractorConfig(model = File(dir, speakerModel.files.single().name).absolutePath, numThreads = 2),
        )
    }

    /** Todos los ficheros en su sitio y con su tamaño. El hash se comprueba al instalar: solo llegan aquí verificados. */
    fun isInstalled(id: String): Boolean = all.find { it.id == id }?.hasFilesIn(modelDir(id)) == true

    /** Ficheros borrados por fuera de la app: el estado deja de decir "Instalado" (si no, «Falta el modelo» no se ve). */
    fun recheck(id: String) {
        if (_installs.value[id] == ModelInstall.Installed && !isInstalled(id)) setState(id, ModelInstall.NotInstalled)
    }

    fun modelDir(id: String): File = File(context.filesDir, "models/$id")

    /** RAM total redondeada hacia arriba: `totalMem` sale algo por debajo de la nominal (8 GB → ~7,4). */
    fun deviceRamGb(): Int {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return ceil(info.totalMem / 1_073_741_824.0).toInt()
    }

    /** Transcribe assets/selftest_es.wav con el modelo [id]. */
    // ponytail: crea su propio reconocedor; si el Transcriber tiene cargado el mismo modelo, dobla la RAM
    // mientras dura la prueba. Si molesta, que la UI pruebe a través de container.transcriber.
    suspend fun selfTest(id: String): Result<String> = withContext(Dispatchers.Default) {
        val model = catalog.find { it.id == id }
        if (model == null || !isInstalled(id)) {
            return@withContext Result.failure(IllegalStateException(context.getString(R.string.asr_error_not_installed)))
        }
        val text = try {
            val rec = openRecognizer(model)
            try {
                rec.transcribeText(selfTestSamples(context), SAMPLE_RATE, id)
            } finally {
                rec.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Autoprueba de $id fallida: ${e.javaClass.simpleName}")
            return@withContext Result.failure(IllegalStateException(context.getString(R.string.asr_error_load)))
        }
        if (text.isEmpty()) Result.failure(IllegalStateException(context.getString(R.string.asr_error_empty)))
        else Result.success(text)
    }

    /** Sigue las descargas de [model] hasta que acaban, fallan o se cancelan (ver [pollDelayMs]). */
    private fun watch(model: AsrModel) {
        jobs[model.id] = scope.launch {
            while (isActive) {
                val ids = downloadIds(model.id)
                if (ids.isEmpty()) break
                val rows = query(ids)
                val failed = rows.firstOrNull { it.status == DownloadManager.STATUS_FAILED }
                when {
                    failed != null || rows.size < ids.size -> {
                        downloads.remove(*ids)
                        forget(model.id)
                        fail(model.id, failureMessage(failed?.reason))
                        break
                    }
                    rows.all { it.status == DownloadManager.STATUS_SUCCESSFUL } -> {
                        install(model, ids)
                        break
                    }
                    else -> {
                        val done = model.files.filter { File(modelDir(model.id), it.name).length() == it.sizeBytes }
                            .sumOf { it.sizeBytes } + rows.sumOf { it.bytes }
                        val waiting = downloadWait(rows.map { it.status to it.reason }, prefs.getBoolean(meteredKey(model.id), true), network())
                        setState(model.id, ModelInstall.Downloading((done.toFloat() / model.sizeBytes).coerceIn(0f, 1f), waiting))
                        delay(pollDelayMs(waiting, observed = _installs.subscriptionCount.value > 0))
                    }
                }
            }
        }
    }

    private suspend fun install(model: AsrModel, ids: LongArray) {
        setState(model.id, ModelInstall.Verifying)
        val tmp = tmpDir(model.id)
        val dir = modelDir(model.id).apply { mkdirs() }
        val error = try {
            val bad = model.files.firstOrNull { f ->
                currentCoroutineContext().ensureActive()
                val dst = File(dir, f.name)
                // Los que ya están con su tamaño se instalaron (verificados) en un intento anterior.
                dst.length() != f.sizeBytes && (tmp == null || !copyVerified(File(tmp, f.name), dst, f.sizeBytes, f.sha256))
            }
            bad?.let { context.getString(R.string.asr_error_corrupt, it.name) }
        } catch (e: IOException) {
            Log.w(TAG, "Error al instalar ${model.id}: ${e.javaClass.simpleName}")
            context.getString(R.string.asr_error_install)
        } finally {
            downloads.remove(*ids) // también borra los temporales que queden
            forget(model.id)
            tmp?.deleteRecursively()
        }
        setState(model.id, error?.let(ModelInstall::Failed) ?: ModelInstall.Installed)
    }

    private class Row(val status: Int, val reason: Int, val bytes: Long)

    private fun query(ids: LongArray): List<Row> =
        downloads.query(DownloadManager.Query().setFilterById(*ids))?.use { c ->
            val status = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val reason = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
            val bytes = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            buildList { while (c.moveToNext()) add(Row(c.getInt(status), c.getInt(reason), c.getLong(bytes))) }
        }.orEmpty()

    private fun network(): Net {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Net.NONE
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) Net.UNMETERED else Net.METERED
    }

    private fun forget(id: String) = prefs.edit {
        remove(id)
        remove(meteredKey(id))
    }

    private fun meteredKey(id: String) = "$id.metered"

    private fun failureMessage(reason: Int?): String = when (reason) {
        null -> context.getString(R.string.asr_error_download_lost)
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> context.getString(R.string.asr_error_no_space)
        else -> context.getString(R.string.asr_error_download, reason)
    }

    private fun downloadIds(id: String): LongArray =
        prefs.getString(id, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toLongArray() ?: LongArray(0)

    private fun tmpDir(id: String): File? = context.getExternalFilesDir(TMP_DIR)?.let { File(it, id).apply { mkdirs() } }

    private fun fail(id: String, message: String) = setState(id, ModelInstall.Failed(message))

    private fun setState(id: String, state: ModelInstall) {
        if (id == SPEAKER_MODEL_ID) _speakerInstall.value = state else _installs.update { it + (id to state) }
    }

    private fun model(
        id: String,
        name: Int,
        description: Int,
        minRamGb: Int,
        repo: String,
        commit: String,
        files: List<Triple<String, Long, String?>>,
        license: String = "CC-BY-4.0",
    ) = AsrModel(
        id = id,
        name = context.getString(name),
        description = context.getString(description),
        sizeBytes = files.sumOf { it.second },
        recommendedMinRamGb = minRamGb,
        license = license,
        files = files.map { (file, size, sha) ->
            ModelFile(file, "https://huggingface.co/$repo/resolve/$commit/$file", size, sha)
        },
    )

    private companion object {
        const val TAG = "MysoneAsr"
        const val TMP_DIR = "models-tmp"
    }
}

/**
 * Desde esta RAM se recomienda Parakeet, el más preciso (docs/investigacion/08): pica en ~1,1 GB y el
 * Memory Limiter de Android 17 deja 2 GiB a un servicio en primer plano en un móvil de 6 GB (1 GiB con 4 GB).
 */
internal const val PARAKEET_MIN_RAM_GB = 6

/** Modelo de huellas de voz ([ModelManager.speakerModel]). */
internal const val SPEAKER_MODEL_ID = "eres2net-voxceleb"

/** Modelo que se propone para [ramGb] (0 = no se sabe): Parakeet si hay RAM de sobra; si no, Canary, el ligero. */
internal fun recommendedModelId(ramGb: Int): String = if (ramGb >= PARAKEET_MIN_RAM_GB) "parakeet-v3" else "canary-180m-es"

internal enum class Net { NONE, METERED, UNMETERED }

/**
 * Por qué no avanza una descarga, a partir de (estado, motivo) de sus ficheros en DownloadManager. Una recién
 * encolada sin la red que pide no llega a PAUSED: se queda en PENDING (su trabajo de JobScheduler no arranca)
 * sin decir por qué, así que se deduce de la red que hay ahora. null = avanzando o a punto de empezar.
 */
internal fun downloadWait(rows: List<Pair<Int, Int>>, allowMetered: Boolean, net: Net): DownloadWait? {
    if (rows.any { it.first == DownloadManager.STATUS_RUNNING }) return null
    val paused = rows.filter { it.first == DownloadManager.STATUS_PAUSED }
    return when {
        paused.any { it.second == DownloadManager.PAUSED_QUEUED_FOR_WIFI } -> DownloadWait.WIFI
        paused.isNotEmpty() -> DownloadWait.NETWORK // sin red, esperando para reintentar o motivo desconocido
        rows.none { it.first == DownloadManager.STATUS_PENDING } -> null
        net == Net.NONE -> DownloadWait.NETWORK
        net == Net.METERED && !allowMetered -> DownloadWait.WIFI
        else -> null
    }
}

/**
 * Cada segundo mientras baja o alguien mira la pantalla de modelos. Esperando Wi-Fi o red (pueden ser días), cada
 * 30 s: es lo que tarda como mucho en verse que ha empezado o acabado, y no despierta al proveedor de descargas cada
 * segundo con la escucha manteniendo el proceso vivo.
 */
internal fun pollDelayMs(waiting: DownloadWait?, observed: Boolean): Long = if (waiting == null || observed) 1_000 else 30_000

/**
 * Modelo que hay que elegir porque el elegido no está ni se está bajando y hay otro instalado (se borró el elegido,
 * se bajó otro, "Ahora no" en la bienvenida con otro marcado); null si el elegido vale. La escucha y la importación
 * usan solo el elegido.
 */
internal fun fallbackModelId(installs: Map<String, ModelInstall>, chosen: String): String? {
    if (installs[chosen].let { it is ModelInstall.Installed || it is ModelInstall.Downloading || it == ModelInstall.Verifying }) return null
    return installs.entries.firstOrNull { it.value is ModelInstall.Installed }?.key
}

internal fun AsrModel.hasFilesIn(dir: File): Boolean = files.all { File(dir, it.name).length() == it.sizeBytes }

/**
 * Copia [src] a [dst] calculando el SHA-256 al vuelo (una sola lectura de cientos de MB). Escribe en
 * `<dst>.part`, lo lleva al disco y solo renombra si cuadran tamaño y hash; si no, no deja nada y devuelve false.
 */
internal fun copyVerified(src: File, dst: File, size: Long, sha256: String?): Boolean {
    if (src.length() != size) return false
    val part = File(dst.path + ".part")
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        src.inputStream().use { input ->
            part.outputStream().use { output ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    output.write(buf, 0, n)
                }
                // En disco antes de renombrar: si no, un apagón justo después puede dejar el nombre definitivo con su
                // tamaño y los datos sin escribir, y el modelo se daría por instalado (solo se miran tamaños).
                output.fd.sync()
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return (sha256 == null || hex.equals(sha256, ignoreCase = true)) && part.renameTo(dst)
    } finally {
        part.delete() // tras renombrar ya no existe; si algo ha fallado, limpia
    }
}

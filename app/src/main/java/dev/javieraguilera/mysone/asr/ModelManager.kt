package dev.javieraguilera.mysone.asr

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.text.format.Formatter
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
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

sealed interface ModelInstall {
    data object NotInstalled : ModelInstall
    data class Downloading(val progress: Float) : ModelInstall
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
            minRamGb = 8,
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

    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("asr_downloads", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

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

    init {
        catalog.filter { prefs.contains(it.id) }.forEach(::watch)
    }

    fun download(id: String, allowMetered: Boolean) {
        val model = catalog.find { it.id == id } ?: return
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
                        .setTitle(context.getString(R.string.asr_download_title, model.name)),
                )
            }
        } catch (e: RuntimeException) { // DownloadManager desactivado, almacenamiento no disponible…
            Log.w(TAG, "No se pudo encolar la descarga: ${e.javaClass.simpleName}")
            return fail(id, context.getString(R.string.asr_error_download_start))
        }
        prefs.edit { putString(id, ids.joinToString(",")) }
        _installs.update { it + (id to ModelInstall.Downloading(0f)) }
        watch(model)
    }

    fun cancel(id: String) {
        jobs.remove(id)?.cancel()
        val ids = downloadIds(id)
        if (ids.isNotEmpty()) downloads.remove(*ids)
        prefs.edit { remove(id) }
        tmpDir(id)?.deleteRecursively()
        setState(id, if (isInstalled(id)) ModelInstall.Installed else ModelInstall.NotInstalled)
    }

    suspend fun delete(id: String) {
        cancel(id)
        withContext(Dispatchers.IO) { modelDir(id).deleteRecursively() }
        setState(id, ModelInstall.NotInstalled)
    }

    /** Todos los ficheros en su sitio y con su tamaño. El hash se comprueba al instalar: solo llegan aquí verificados. */
    fun isInstalled(id: String): Boolean = catalog.find { it.id == id }?.hasFilesIn(modelDir(id)) == true

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
            val rec = createRecognizer(model, modelDir(id))
            try {
                rec.transcribeText(selfTestSamples(context), SAMPLE_RATE)
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

    /** Sigue las descargas de [model] cada segundo hasta que acaban, fallan o se cancelan. */
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
                        prefs.edit { remove(model.id) }
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
                        setState(model.id, ModelInstall.Downloading((done.toFloat() / model.sizeBytes).coerceIn(0f, 1f)))
                        delay(1_000)
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
            prefs.edit { remove(model.id) }
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

    private fun failureMessage(reason: Int?): String = when (reason) {
        null -> context.getString(R.string.asr_error_download_lost)
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> context.getString(R.string.asr_error_no_space)
        else -> context.getString(R.string.asr_error_download, reason)
    }

    private fun downloadIds(id: String): LongArray =
        prefs.getString(id, null)?.split(',')?.mapNotNull { it.toLongOrNull() }?.toLongArray() ?: LongArray(0)

    private fun tmpDir(id: String): File? = context.getExternalFilesDir(TMP_DIR)?.let { File(it, id).apply { mkdirs() } }

    private fun fail(id: String, message: String) = setState(id, ModelInstall.Failed(message))

    private fun setState(id: String, state: ModelInstall) = _installs.update { it + (id to state) }

    private fun model(
        id: String,
        name: Int,
        description: Int,
        minRamGb: Int,
        repo: String,
        commit: String,
        files: List<Triple<String, Long, String?>>,
    ) = AsrModel(
        id = id,
        name = context.getString(name),
        description = context.getString(description),
        sizeBytes = files.sumOf { it.second },
        recommendedMinRamGb = minRamGb,
        license = "CC-BY-4.0",
        files = files.map { (file, size, sha) ->
            ModelFile(file, "https://huggingface.co/$repo/resolve/$commit/$file", size, sha)
        },
    )

    private companion object {
        const val TAG = "MysoneAsr"
        const val TMP_DIR = "models-tmp"
    }
}

internal fun AsrModel.hasFilesIn(dir: File): Boolean = files.all { File(dir, it.name).length() == it.sizeBytes }

/**
 * Copia [src] a [dst] calculando el SHA-256 al vuelo (una sola lectura de cientos de MB). Escribe en
 * `<dst>.part` y solo renombra si cuadran tamaño y hash; si no, no deja nada y devuelve false.
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
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return (sha256 == null || hex.equals(sha256, ignoreCase = true)) && part.renameTo(dst)
    } finally {
        part.delete() // tras renombrar ya no existe; si algo ha fallado, limpia
    }
}

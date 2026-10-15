package dev.javieraguilera.mysone.asr

import android.content.Context
import dev.javieraguilera.mysone.R
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** Descarga, verificación y borrado de modelos ASR (§5.4). Lo implementa B2. */
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
                Triple("tokens.txt", 53_555L, "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
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
                Triple("tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            ),
        ),
    )

    private val _installs = MutableStateFlow<Map<String, ModelInstall>>(catalog.associate { it.id to ModelInstall.NotInstalled })
    val installs: StateFlow<Map<String, ModelInstall>> = _installs.asStateFlow()

    fun download(id: String, allowMetered: Boolean) {}

    fun cancel(id: String) {}

    suspend fun delete(id: String) {}

    fun isInstalled(id: String): Boolean = false

    fun modelDir(id: String): File = File(context.filesDir, "models/$id")

    fun deviceRamGb(): Int = 0

    /** Transcribe assets/selftest_es.wav con el modelo [id]. */
    suspend fun selfTest(id: String): Result<String> = Result.failure(UnsupportedOperationException())

    private fun model(
        id: String,
        name: Int,
        description: Int,
        minRamGb: Int,
        repo: String,
        commit: String,
        files: List<Triple<String, Long, String>>,
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
}

package dev.javieraguilera.mysone.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.data.SettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Estado del reconocedor en memoria. NoModel = no hay ninguno cargado (no instalado o ya liberado). */
sealed interface TranscriberState {
    data object NoModel : TranscriberState
    data object Loading : TranscriberState
    data class Ready(val modelId: String) : TranscriberState
    data class Error(val message: String) : TranscriberState
}

/** Reconocedor offline compartido (una sola instancia, en AppContainer). */
class Transcriber(
    private val context: Context,
    private val models: ModelManager,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow<TranscriberState>(TranscriberState.NoModel)
    val state: StateFlow<TranscriberState> = _state.asStateFlow()

    private val mutex = Mutex() // protege todo lo de abajo y serializa el trabajo nativo
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recognizer: OfflineRecognizer? = null
    private var modelId = "" // el de [recognizer]
    private var failedId = "" // el último que no cargó en caliente: no se reintenta en cada trozo
    private var refs = 0

    /**
     * Carga el modelo elegido si hace falta (refcount); false si no hay modelo o falla.
     * Si ya hay uno cargado se comparte; si en Ajustes han elegido otro, [transcribe] lo cambia en caliente.
     */
    suspend fun acquire(): Boolean = mutex.withLock {
        if (recognizer == null && !load(settings.current().asrModelId)) return@withLock false
        refs++
        true
    }

    /** Al llegar a 0 libera la memoria nativa. No bloquea: espera a que acabe la transcripción en curso. */
    fun release() {
        scope.launch {
            mutex.withLock {
                if (refs > 0) refs--
                if (refs == 0) free()
            }
        }
    }

    /**
     * Serializado con Mutex; devuelve texto limpio ("" si no se ha dicho nada).
     * Lanza IllegalStateException sin [acquire] previo: así el trozo no se da por vacío y se reintenta.
     */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): String = mutex.withLock {
        switchToChosen()
        val rec = checkNotNull(recognizer) { "Transcriber sin modelo: falta acquire()" }
        withContext(Dispatchers.Default) { rec.transcribeText(samples, sampleRate, modelId) }
    }

    /**
     * La escucha retiene el reconocedor toda la sesión (días): si en Ajustes han elegido otro modelo instalado, se
     * carga entre dos trozos y sustituye al de ahora solo si carga y pasa la autoprueba. Si no, sigue el de antes.
     */
    // ponytail: mientras carga el nuevo están los dos en memoria; soltar antes el viejo ahorraría RAM a cambio
    // de quedarse sin reconocedor si el nuevo falla.
    private suspend fun switchToChosen() {
        val wanted = settings.current().asrModelId
        if (recognizer == null || wanted == modelId || wanted == failedId || !models.isInstalled(wanted)) return
        if (!load(wanted)) {
            failedId = wanted
            _state.value = TranscriberState.Ready(modelId)
        }
    }

    private suspend fun load(id: String): Boolean {
        val model = models.catalog.find { it.id == id }
        if (model == null || !models.isInstalled(id)) {
            _state.value = TranscriberState.NoModel
            return false
        }
        _state.value = TranscriberState.Loading
        // NonCancellable: cancelado a medias, el reconocedor nativo se quedaría sin liberar hasta el GC.
        val error: Int? = withContext(NonCancellable + Dispatchers.Default) {
            var rec: OfflineRecognizer? = null
            try {
                rec = models.openRecognizer(model)
                // Autoprueba (§5.4): hay móviles en los que el modelo carga pero no devuelve nada.
                if (rec.transcribeText(selfTestSamples(context), SAMPLE_RATE, id).isEmpty()) {
                    R.string.asr_error_empty
                } else {
                    recognizer?.release() // el anterior, si se cambia en caliente
                    recognizer = rec
                    modelId = id
                    failedId = ""
                    rec = null
                    null
                }
            } catch (e: Throwable) { // también UnsatisfiedLinkError si la librería nativa no carga
                Log.w(TAG, "No se pudo cargar el modelo $id: ${e.javaClass.simpleName}")
                R.string.asr_error_load
            } finally {
                rec?.release() // solo si no se ha quedado
            }
        }
        if (error != null) {
            _state.value = TranscriberState.Error(context.getString(error))
            return false
        }
        _state.value = TranscriberState.Ready(id)
        return true
    }

    private fun free() {
        recognizer?.release()
        recognizer = null
        if (_state.value is TranscriberState.Ready) _state.value = TranscriberState.NoModel
    }

    private companion object {
        const val TAG = "MysoneAsr"
    }
}

/** Construye el reconocedor desde ficheros (assetManager = null). Comprueba antes ficheros y tamaños:
 *  un modelo roto en sherpa-onnx mata el proceso en vez de lanzar excepción (03 §3.2). */
internal fun createRecognizer(model: AsrModel, dir: File): OfflineRecognizer {
    check(model.hasFilesIn(dir)) { "faltan ficheros del modelo ${model.id}" }
    val path = { name: String -> File(dir, name).absolutePath }
    val modelConfig = when (model.id) {
        // modelType vacío, como el ejemplo Canary de getOfflineModelConfig() en el AAR 1.13.8 (comprobado con javap)
        "canary-180m-es" -> OfflineModelConfig(
            canary = OfflineCanaryModelConfig(
                encoder = path("encoder.int8.onnx"),
                decoder = path("decoder.int8.onnx"),
                srcLang = "es",
                tgtLang = "es",
                usePnc = true,
            ),
            tokens = path("tokens.txt"),
            numThreads = 2,
        )
        "parakeet-v3" -> OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = path("encoder.int8.onnx"),
                decoder = path("decoder.int8.onnx"),
                joiner = path("joiner.int8.onnx"),
            ),
            tokens = path("tokens.txt"),
            modelType = "nemo_transducer",
            numThreads = 2,
        )
        else -> error("modelo sin configuración: ${model.id}")
    }
    // FeatureConfig por defecto (16 kHz, 80): Canary y NeMo lo sobrescriben con los metadatos del modelo.
    return OfflineRecognizer(assetManager = null, config = OfflineRecognizerConfig(modelConfig = modelConfig))
}

/** [modelId]: el modelo con el que se ha creado el reconocedor (la «ñ» solo se repara con Canary). */
internal fun OfflineRecognizer.transcribeText(samples: FloatArray, sampleRate: Int, modelId: String): String {
    if (samples.size < sampleRate / 10) return "" // < 0,1 s: nada que reconocer
    val stream = createStream()
    try {
        stream.acceptWaveform(samples, sampleRate)
        decode(stream)
        return cleanText(getResult(stream).text, fixEnye = modelId == "canary-180m-es")
    } finally {
        stream.release()
    }
}

private val WHITESPACE = Regex("\\s+")
private val LETTER_OR_DIGIT = Regex("[\\p{L}\\p{N}]")

/** Espacios normalizados y, con [fixEnye], «ñ» recuperadas. Sin letras ni cifras (".", "¿?" sobre ruido) cuenta como nada dicho. */
internal fun cleanText(raw: String, fixEnye: Boolean = false): String {
    val spaced = raw.trim().replace(WHITESPACE, " ")
    val text = if (fixEnye) restoreEnye(spaced) else spaced
    return if (LETTER_OR_DIGIT.containsMatchIn(text)) text else ""
}

// Canary 180M int8 a veces cambia la «ñ» por un principio de palabra: emite «▁es|pa|▁o|l» en vez de
// «▁es|pa|ñ|ol» (tokens comprobados en el emulador con la frase de prueba anterior), y sale «espa ol», «ma ana».
// Solo se aplica con Canary: en el diagnóstico de escritorio (docs/investigacion/08-diagnostico-asr.md) pasa
// sobre todo con voces TTS (Helena) en trozos cortos; con voz humana (MLS-es, MInDS-14) acierta casi todas
// las «ñ», y Parakeet no partió ninguna de 208.
// ponytail: lista cerrada de palabras frecuentes (y apellidos); una que falte se queda partida. Si se ve
// alguna más en las pruebas, añadirla aquí. Faltan adrede «niña», «seña» y «leña»: «ni a», «se a» y «le a»
// también son dos palabras de verdad.
private val ENYE_WORDS: Set<String> = """
    año años mañana mañanas niño niños niñas señor señores señora señoras señorita señoritas
    españa español española españoles españolas pequeño pequeña pequeños pequeñas cumpleaños
    compañero compañera compañeros compañeras compañía compañías acompañar acompaño acompaña acompañas
    baño baños bañera sueño sueños soñar soñé sueña dueño dueña dueños dueñas diseño diseños diseñar
    diseñador diseñadora otoño montaña montañas enseñar enseño enseña enseñanza cariño daño daños
    extraño extraña extraños extrañas extrañar tamaño tamaños campaña campañas señal señales
    añadir añade añadido añadida muñeca muñeco pañuelo pañal pañales puño desempeño empeño albañil
    albañiles riñón riñones cuñado cuñada cuñados
    muñoz núñez ibáñez peña ordóñez yáñez logroño coruña cataluña begoña iñaki íñigo
""".trim().split(Regex("\\s+")).toSet()

private val WORD_OR_GAP = Regex("\\p{L}+|[^\\p{L}]+")

/** Une «ma ana» → «mañana» solo si el resultado es una palabra de [ENYE_WORDS]: así no pega dos palabras reales. */
internal fun restoreEnye(text: String): String {
    if (' ' !in text) return text
    val parts = WORD_OR_GAP.findAll(text).map { it.value }.toList()
    val out = StringBuilder(text.length + 4)
    var i = 0
    while (i < parts.size) {
        val left = parts[i]
        val right = parts.getOrNull(i + 2)
        if (right != null && parts[i + 1] == " " && left[0].isLetter() && right[0].isLetter() &&
            (left + "ñ" + right).lowercase() in ENYE_WORDS
        ) {
            out.append(left).append(if (left.length > 1 && left.all(Char::isUpperCase)) 'Ñ' else 'ñ').append(right)
            i += 3
        } else {
            out.append(left)
            i++
        }
    }
    return out.toString()
}

/** assets/selftest_es.wav: «Hola, esto es una prueba de transcripción de voz.» (TTS, sin «ñ»), PCM16 mono 16 kHz. */
internal fun selfTestSamples(context: Context): FloatArray =
    PcmFiles.readWav(context.assets.open("selftest_es.wav").use { it.readBytes() })

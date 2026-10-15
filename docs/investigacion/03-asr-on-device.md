# 03 — Motor de reconocimiento de voz en el dispositivo (Android, español)

Fecha de la investigación: 15-oct-2026. Todo lo que aparece como "verificado" se ha leído en la fuente
original (API de GitHub, código fuente del tag `v1.13.8`, API de Hugging Face, JitPack, documentación
oficial). Lo que no se ha podido medir o confirmar va marcado como **NO VERIFICADO**.

Contexto: foreground service nativo en Kotlin (sin JS) que captura con `AudioRecord` a 16 kHz mono,
segmenta con VAD y transcribe cada segmento con un reconocedor no-streaming (offline).

---

## 1. Decisión (resumen)

| Rol | Elección | Por qué |
|---|---|---|
| Runtime | **sherpa-onnx v1.13.8** (última release, 10-sep-2026), AAR oficial | API Kotlin completa (VAD + ASR + speaker ID) en un solo artefacto; 4 ABIs incluidos (arm64-v8a, armeabi-v7a, x86, x86_64); onnxruntime 1.28.2 |
| VAD | **silero_vad.onnx** (643 854 B) de k2-fsa | Por defecto en todos los ejemplos; 16 kHz; ventana 512 |
| ASR por defecto | **NeMo Parakeet TDT 0.6B v3 int8** (~670 MB en disco) | Mejor WER publicado en español (FLEURS 3,45 %, MLS 4,39 %, CoVoST 3,41 %), puntuación y mayúsculas nativas, timestamps por token, CC-BY-4.0, funciona en un móvil de 2 GB de RAM según un usuario (issue #3913) |
| ASR ligero | **NeMo Canary 180M flash int8** (~207 MB) | Idioma forzado `srcLang="es"` (evita que se cambie a inglés), PnC, MLS-es 3,17 %; unas 3× menos peso. Sin timestamps por token en sherpa-onnx |
| A evaluar | Nemotron 3.5 ASR streaming 0.6B int8 (2026-06) | Multilingüe con idioma forzable `es-ES`, PnC, FLEURS-es 4,11–4,87 %; mismo peso que Parakeet; se usa con `OnlineRecognizer` |
| Ultraligero | Moonshine base-es (~64 MB) | Licencia **no comercial** (vale para uso personal); WER-es solo de fuente secundaria |
| Identificación del dueño | `SpeakerEmbeddingExtractor` + `wespeaker_en_voxceleb_resnet34_LM.onnx` (26,5 MB) o `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx` (29,6 MB) | Mismo AAR, API `SpeakerEmbeddingManager.verify/search` |

Descartados para este caso: Android `SpeechRecognizer` on-device (la propia documentación dice que no
está pensado para reconocimiento continuo, exige hilo principal y depende del fabricante), whisper.cpp
(JNI propio que compilar con NDK, ventanas de 30 s, más lento y propenso a alucinar en silencio para la
misma calidad), Vosk (WER-es 16 % en el modelo pequeño, sin puntuación, modelos de 2022).

---

## 2. sherpa-onnx: versión, artefactos e integración en Gradle

### 2.1 Versión

- Última release: **v1.13.8**, publicada el 2026-09-10T14:00:23Z (`/releases/latest` lo confirma a
  15-oct-2026). Master va 26 commits por delante (cambios en `OfflineRecognizer.kt` y en la JNI): no
  mezclar ficheros Kotlin de master con binarios de 1.13.8.
- Notas de la release: entre otros, "Update onnxruntime to v1.28.2" (PR #3935), "Fix Canary empty
  transcript when the first decoded token is eos" (#3920), arreglos de alucinación en silencio para
  Moonshine v2, Cohere Transcribe y FunASR-Nano.
- `build-android-arm64-v8a.sh` del tag: `onnxruntime_version=${SHERPA_ONNX_ONNXRUNTIME_VERSION:-1.28.2}`,
  `ANDROID_PLATFORM=android-21`, `BUILD_SHARED_LIBS=ON` (→ `libsherpa-onnx-jni.so` + `libonnxruntime.so`).
- `CMakeLists.txt` enlaza con `-Wl,-z,max-page-size=16384` en Android (compatible con páginas de 16 KB,
  verificado para `libsherpa-onnx-jni.so`; para `libonnxruntime.so` **NO VERIFICADO**).

### 2.2 Artefactos Android de la release v1.13.8 (tamaños en bytes, de la API de GitHub)

| Fichero | Bytes | SHA-256 (campo `digest`) |
|---|---|---|
| `sherpa-onnx-1.13.8.aar` | 50 129 134 | `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` |
| `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` | 38 691 998 | `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471` |
| `sherpa-onnx-v1.13.8-android.tar.bz2` (solo `jniLibs/`) | 46 093 321 | `2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44` |

URL: `https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar`

Contenido del AAR (deducido del workflow `.github/workflows/android.yaml` y del proyecto
`android/SherpaOnnxAar`, sin descargar el binario):
- `jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/` con `libsherpa-onnx-jni.so` + `libonnxruntime.so`
  (+ `libsherpa-onnx-c-api.so`, ya que el workflow exporta `SHERPA_ONNX_ENABLE_C_API=ON`).
- Las clases Kotlin del paquete `com.k2fsa.sherpa.onnx` (el módulo `sherpa_onnx/src/main/java` son
  enlaces simbólicos a `sherpa-onnx/kotlin-api/*.kt`): `Vad`, `OfflineRecognizer`, `OnlineRecognizer`,
  `SpeakerEmbeddingExtractor`, `OfflinePunctuation`, `SpokenLanguageIdentification`, `Tts`, etc.
- `minSdk = 21`, `compileSdk = 34`, Kotlin 1.7.20, AGP 8.4.0, JVM target 1.8.
- **`consumer-rules.pro` está vacío** (verificado). La JNI lee los campos de los `data class` por
  nombre, así que con R8/minify activado hay que añadir en la app:
  `-keep class com.k2fsa.sherpa.onnx.** { *; }`.

Tamaño por ABI: las únicas cifras publicadas son de una versión antigua de la documentación
(onnxruntime 1.17.1): arm64-v8a `libonnxruntime.so` 15 MB + `libsherpa-onnx-jni.so` 3,7 MB;
x86_64 17 MB + 4,0 MB. Para 1.13.8 **NO VERIFICADO** (estimación: ~12 MB comprimido por ABI, a
partir de los 46 MB del tarball de 4 ABIs). Con `abiFilters` en arm64-v8a + x86_64 el APK lleva solo
esos dos.

### 2.3 Tres formas de integrarlo

**A) JitPack (lo que usan los ejemplos oficiales del propio repo)** — verificado en
`android/SherpaOnnxSimulateStreamingAsrWearOs/app/build.gradle.kts` y
`android/SherpaOnnxJavaDemo/app/build.gradle` del tag:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
// app/build.gradle.kts
dependencies {
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
}
android {
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
}
```

JitPack sirve el AAR en
`https://jitpack.io/com/github/k2-fsa/sherpa-onnx/sherpa-onnx/v1.13.8/sherpa-onnx-v1.13.8.aar`
(HEAD 200, `Content-Length: 50129134`, mismo tamaño que el de GitHub). JitPack no compila nada: su
`jitpack.yml` hace `wget` del AAR de la release y `mvn install:install-file`. Ojo: **no** usar la
coordenada agregada `com.github.k2-fsa:sherpa-onnx:v1.13.8`, cuyo POM arrastra además el JAR de JVM y
las librerías nativas de escritorio (linux/osx/win).

**B) AAR vendorizado (recomendado para fijar la cadena de suministro)**: descargar el AAR de la release
de GitHub, comprobar el SHA-256 de la tabla y declararlo con
`implementation(files("libs/sherpa-onnx-1.13.8.aar"))`. Sin repositorio externo en tiempo de build.

**C) Tarball de `jniLibs` + copiar los `.kt`**: `sherpa-onnx-v1.13.8-android.tar.bz2` en
`app/src/main/jniLibs/` y copiar a mano los ficheros de `sherpa-onnx/kotlin-api/` **del mismo tag**
(`FeatureConfig.kt`, `HomophoneReplacerConfig.kt`, `QnnConfig.kt`, `Vad.kt`, `OfflineStream.kt`,
`OfflineRecognizer.kt`, `OnlineStream.kt`, `Speaker.kt`, `SpeakerEmbeddingExtractorConfig.kt`). Es lo
que hacen los ejemplos `SherpaOnnxVadAsr` y `SherpaOnnxSimulateStreamingAsr` (sus `.kt` son symlinks,
modo git `120000`). Solo compensa si se quiere recortar código o recompilar sin TTS/diarización.

---

## 3. API Kotlin real (copiada del tag v1.13.8)

Paquete `com.k2fsa.sherpa.onnx`. Todas las clases cargan `System.loadLibrary("sherpa-onnx-jni")`.
Constructor con `assetManager != null` → carga desde `assets/`; con `assetManager = null` → las rutas
son de fichero (`newFromFile`).

### 3.1 `Vad.kt`

```kotlin
data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0F,
)

data class TenVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5F,
    var minSilenceDuration: Float = 0.25F,
    var minSpeechDuration: Float = 0.25F,
    var windowSize: Int = 256,
    var maxSpeechDuration: Float = 5.0F,
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false,
)

class SpeechSegment(val start: Int, val samples: FloatArray)

class Vad(assetManager: AssetManager? = null, var config: VadModelConfig) {
    fun release()
    fun compute(samples: FloatArray): Float
    fun acceptWaveform(samples: FloatArray)
    fun empty(): Boolean
    fun pop()
    fun front(): SpeechSegment
    fun clear()
    fun isSpeechDetected(): Boolean
    fun reset()
    fun flush()
}
```

Semántica verificada en `sherpa-onnx/csrc/voice-activity-detector.cc` y `silero-vad-model-config.cc`:
- `maxSpeechDuration` **no corta en seco**: al superarlo sube el umbral a 0,9 hasta que acaba el
  segmento y luego lo restaura. Puede salir un segmento más largo; hay que trocearlo antes del ASR.
- Búfer interno de 60 s por defecto.
- `SpeechSegment.start` es un índice lineal en muestras desde el último `reset()`. En
  `circular-buffer.h` es `int32_t` "always increasing; never wraps around": **desborda tras
  2³¹/16 000 ≈ 37,3 h de audio** sin `reset()`. En un servicio de todo el día hay que llamar a
  `reset()` periódicamente en silencio (ver snippet).
- `TenVad` existe (`ten-vad.onnx`, 332 211 B; `ten-vad.int8.onnx`, 129 534 B).

### 3.2 `OfflineRecognizer.kt` (lo relevante)

```kotlin
data class OfflineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
    val lang: String,
    val emotion: String,
    val event: String,
    // valid only for TDT models
    val durations: FloatArray,
)

data class OfflineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
    var qnnConfig: QnnConfig = QnnConfig(),
)

data class OfflineNemoEncDecCtcModelConfig(var model: String = "")

data class OfflineWhisperModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var language: String = "en", // Used with multilingual model
    var task: String = "transcribe", // transcribe or translate
    var tailPaddings: Int = 1000,
    var enableTokenTimestamps: Boolean = false,
    var enableSegmentTimestamps: Boolean = false,
)

data class OfflineCanaryModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var srcLang: String = "en",
    var tgtLang: String = "en",
    var usePnc: Boolean = true,
)

data class OfflineMoonshineModelConfig(
    var preprocessor: String = "",
    var encoder: String = "",
    var uncachedDecoder: String = "",
    var cachedDecoder: String = "",
    var mergedDecoder: String = "",
)

data class OfflineCohereTranscribeModelConfig(
    var encoder: String = "", var decoder: String = "",
    var language: String = "", var usePunct: Boolean = true, var useItn: Boolean = true,
)

data class OfflineModelConfig(
    var transducer: OfflineTransducerModelConfig = OfflineTransducerModelConfig(),
    var paraformer: OfflineParaformerModelConfig = OfflineParaformerModelConfig(),
    var whisper: OfflineWhisperModelConfig = OfflineWhisperModelConfig(),
    var fireRedAsr: OfflineFireRedAsrModelConfig = OfflineFireRedAsrModelConfig(),
    var moonshine: OfflineMoonshineModelConfig = OfflineMoonshineModelConfig(),
    var nemo: OfflineNemoEncDecCtcModelConfig = OfflineNemoEncDecCtcModelConfig(),
    var senseVoice: OfflineSenseVoiceModelConfig = OfflineSenseVoiceModelConfig(),
    var dolphin: OfflineDolphinModelConfig = OfflineDolphinModelConfig(),
    var zipformerCtc: OfflineZipformerCtcModelConfig = OfflineZipformerCtcModelConfig(),
    var wenetCtc: OfflineWenetCtcModelConfig = OfflineWenetCtcModelConfig(),
    var omnilingual: OfflineOmnilingualAsrCtcModelConfig = OfflineOmnilingualAsrCtcModelConfig(),
    var medasr: OfflineMedAsrCtcModelConfig = OfflineMedAsrCtcModelConfig(),
    var funasrNano: OfflineFunAsrNanoModelConfig = OfflineFunAsrNanoModelConfig(),
    var qwen3Asr: OfflineQwen3AsrModelConfig = OfflineQwen3AsrModelConfig(),
    var fireRedAsrCtc: OfflineFireRedAsrCtcModelConfig = OfflineFireRedAsrCtcModelConfig(),
    var canary: OfflineCanaryModelConfig = OfflineCanaryModelConfig(),
    var cohereTranscribe: OfflineCohereTranscribeModelConfig = OfflineCohereTranscribeModelConfig(),
    var teleSpeech: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var tokens: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class OfflineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),   // (sampleRate=16000, featureDim=80, dither=0f)
    var modelConfig: OfflineModelConfig = OfflineModelConfig(),
    var hr: HomophoneReplacerConfig = HomophoneReplacerConfig(),
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

class OfflineRecognizer(assetManager: AssetManager? = null, val config: OfflineRecognizerConfig) {
    fun release()
    fun createStream(): OfflineStream
    fun createStream(hotwords: String): OfflineStream
    fun getResult(stream: OfflineStream): OfflineRecognizerResult
    fun decode(stream: OfflineStream)
    fun setConfig(config: OfflineRecognizerConfig)
    companion object { @JvmStatic external fun prependAdspLibraryPath(newPath: String) } // QNN
}
```

`OfflineStream.kt`:

```kotlin
class OfflineStream(var ptr: Long) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun setOption(key: String, value: String)
    fun getOption(key: String): String
    fun release()
    fun use(block: (OfflineStream) -> Unit)
}
```

Detalles comprobados en C++:
- Para modelos NeMo transducer (Parakeet), `PostInit()` **sobrescribe `featureDim` con el de los
  metadatos del modelo** (Parakeet usa 128 mel); pasar `FeatureConfig(16000, 80)` no rompe nada.
- Canary: `setConfig()` permite cambiar `srcLang/tgtLang` en caliente; si el idioma no está en
  `lang2id` cae a inglés (issue #3961, arreglado en master con aviso).
- Whisper: "Only waves less than 30 seconds are supported" (descarta lo que pase de 30 s).
- Muchos errores de modelo (tokens que no cuadran, `<blk>` ausente, etc.) llaman a
  `SHERPA_ONNX_EXIT(-1)`: **matan el proceso**, no lanzan excepción. Validar existencia y tamaño de
  los ficheros antes de construir el reconocedor.
- Carga desde fichero: ORT abre la ruta directamente. Carga desde assets: `ReadFile(mgr, ...)` copia
  el fichero entero a memoria antes de crear la sesión (pico de RAM ≈ 2× durante la carga). Con un
  encoder de 652 MB, **usar rutas de fichero** (`assetManager = null`) con el modelo descargado a
  `filesDir`, no assets.

### 3.3 `OnlineRecognizer.kt` (para Nemotron 3.5)

```kotlin
data class OnlineTransducerModelConfig(var encoder: String = "", var decoder: String = "",
    var joiner: String = "", var qnnConfig: QnnConfig = QnnConfig())
data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    /* paraformer, zipformer2Ctc, neMoCtc, toneCtc */
    var tokens: String = "", var numThreads: Int = 1, var debug: Boolean = false,
    var provider: String = "cpu", var modelType: String = "", var modelingUnit: String = "", var bpeVocab: String = "",
)
data class OnlineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(), var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var lmConfig: OnlineLMConfig = OnlineLMConfig(), var ctcFstDecoderConfig: OnlineCtcFstDecoderConfig = OnlineCtcFstDecoderConfig(),
    var hr: HomophoneReplacerConfig = HomophoneReplacerConfig(), var endpointConfig: EndpointConfig = EndpointConfig(),
    var enableEndpoint: Boolean = true, var decodingMethod: String = "greedy_search", var maxActivePaths: Int = 4,
    var hotwordsFile: String = "", var hotwordsScore: Float = 1.5f, var ruleFsts: String = "", var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)
data class OnlineRecognizerResult(val text: String, val tokens: Array<String>,
    val timestamps: FloatArray, val ysProbs: FloatArray)
class OnlineRecognizer(assetManager: AssetManager? = null, val config: OnlineRecognizerConfig) {
    fun createStream(hotwords: String = ""): OnlineStream
    fun reset(stream: OnlineStream); fun decode(stream: OnlineStream)
    fun isEndpoint(stream: OnlineStream): Boolean; fun isReady(stream: OnlineStream): Boolean
    fun getResult(stream: OnlineStream): OnlineRecognizerResult
}
class OnlineStream(var ptr: Long = 0) {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int); fun inputFinished()
    fun setOption(key: String, value: String); fun getOption(key: String): String; fun release()
}
```

Nemotron multilingüe: idioma por stream con `stream.setOption("language", "es")` (comprobado en
`online-recognizer-transducer-nemo-impl.h`: `GetLanguagePromptId(ss[i]->GetOption("language"))`;
`online-transducer-nemo-model.cc` acepta locales `es-ES` y el alias base `es`; vacío = auto).
Nemotron 3.5 no está en el `getModelConfig()` de ejemplo del Kotlin de 1.13.8 (solo las variantes
`-en`), pero el soporte está en C++ desde el PR #3671 (jun-2026).

### 3.4 `Speaker.kt` / `SpeakerEmbeddingExtractorConfig.kt`

```kotlin
data class SpeakerEmbeddingExtractorConfig(
    val model: String = "", var numThreads: Int = 1, var debug: Boolean = false, var provider: String = "cpu",
)
class SpeakerEmbeddingExtractor(assetManager: AssetManager? = null, config: SpeakerEmbeddingExtractorConfig) {
    fun createStream(): OnlineStream
    fun isReady(stream: OnlineStream): Boolean
    fun compute(stream: OnlineStream): FloatArray
    fun dim(): Int
    fun release()
}
class SpeakerEmbeddingManager(val dim: Int) {
    fun add(name: String, embedding: FloatArray): Boolean
    fun add(name: String, embedding: Array<FloatArray>): Boolean
    fun remove(name: String): Boolean
    fun search(embedding: FloatArray, threshold: Float): String   // "" si nadie supera el umbral
    fun verify(name: String, embedding: FloatArray, threshold: Float): Boolean
    fun contains(name: String): Boolean
    fun numSpeakers(): Int
    fun allSpeakerNames(): Array<String>
    fun release()
}
```

Uso en el ejemplo `SherpaOnnxSpeakerIdentification` (screens/Home.kt): `createStream()` →
`acceptWaveform(samples, 16000)` → `inputFinished()` → `if (isReady) compute()` →
`manager.search(embedding, threshold)`; umbral inicial del ejemplo 0,5. El `SpeakerEmbeddingManager`
vive en memoria nativa: hay que **persistir los embeddings** (FloatArray) en la base de datos de la app
y volver a añadirlos al arrancar.

---

## 4. Modelos con español

Todos se descargan de `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/<fichero>` o,
fichero a fichero (mejor en el móvil: no hay que descomprimir un `.tar.bz2`), de Hugging Face con
`https://huggingface.co/<repo>/resolve/<commit>/<fichero>`. Tamaños en bytes de la API de GitHub/HF.

### 4.1 Tabla comparativa

| Modelo (sherpa-onnx) | Tipo / API Kotlin | Tarball (B) | Ficheros en disco | WER español publicado | PnC | Timestamps | Idioma forzable | Licencia |
|---|---|---|---|---|---|---|---|---|
| **parakeet-tdt-0.6b-v3-int8** | Offline transducer, `modelType="nemo_transducer"` | 487 170 055 | 670,5 MB | FLEURS 3,45 · MLS 4,39 · CoVoST 3,41 (sin PnC) | Sí | Sí, por token (+ `durations` TDT) | **No** (auto entre 25 idiomas) | CC-BY-4.0 |
| **canary-180m-flash-en-es-de-fr-int8** | Offline `canary` | 153 692 328 | 207,2 MB | MLS-es 3,17 · MCV-16.1-es 4,90 (tabla) / 3,31 (model-index) | Sí (`usePnc`) | No en sherpa-onnx | **Sí** `srcLang="es"` | CC-BY-4.0 |
| nemotron-3.5-asr-streaming-0.6b-{80,160,320,560,1120}ms-int8 | Online transducer + `setOption("language","es")` | 475 271 763 (560 ms) | 682,2 MB | FLEURS es-ES/es-US: 4,87 (80 ms) … 4,11 (1,12 s) | Sí | Por token | **Sí** | OpenMDW-1.1 |
| moonshine-base-es-quantized-2026-02-27 | Offline `moonshine` v2 (`encoder` + `mergedDecoder`, `.ort`) | 50 846 902 | 65,1 MB | 4,33 FLEURS (solo fuente secundaria, **NO VERIFICADO**) | **NO VERIFICADO** | — | Monolingüe | Moonshine Community License (**no comercial**) |
| whisper-small (multilingüe) | Offline `whisper`, `language="es"` | 639 387 718 | int8: 374,7 MB | FLEURS-es 5,6 · CV9-es 10,3 (paper) | Sí | Opcional | Sí | MIT |
| whisper-base | Offline `whisper` | 207 557 382 | int8: 159,8 MB | FLEURS-es 9,9 · CV9-es 19,6 | Sí | Opcional | Sí | MIT |
| whisper-tiny | Offline `whisper` | 116 204 861 | int8: 102,8 MB | FLEURS-es 15,9 · CV9-es 30,3 | Sí | Opcional | Sí | MIT |
| whisper-turbo | Offline `whisper` | 563 790 207 | int8: ~1,04 GB | (large-v2 FLEURS-es 3,0) | Sí | Opcional | Sí | MIT |
| nemo-fast-conformer-ctc-es-1424-int8 | Offline `nemo` CTC | 103 489 263 | 131,7 MB | **NO VERIFICADO** | **NO VERIFICADO** | — | Monolingüe | CC-BY-4.0 (NeMo) |
| nemo-fast-conformer-transducer-es-1424-int8 | Offline transducer | 107 246 685 | — | **NO VERIFICADO** | **NO VERIFICADO** | — | Monolingüe | — |
| streaming-zipformer-es-kroko-2025-08-06 | Online transducer | 124 394 665 | 155,8 MB (fp32) | **NO VERIFICADO** | **NO VERIFICADO** | — | Monolingüe | "other" (ver Banafo/Kroko-ASR) |
| omnilingual-asr-1600-languages-300M-ctc-int8 | Offline `omnilingual` CTC | 292 571 207 | 365,4 MB | **NO VERIFICADO** | — | — | — | ver LICENSE del repo |
| qwen3-asr-0.6B-int8-2026-03-25 | Offline `qwen3Asr` (LLM) | 878 702 423 | — | **NO VERIFICADO** | Sí | — | — | — |
| cohere-transcribe-14-lang-int8-2026-04-01 | Offline `cohereTranscribe` | 1 699 791 751 | ~2,89 GB | **NO VERIFICADO** | Sí | — | Sí | — |

Notas:
- Las WER de Parakeet, Canary y Nemotron son de las fichas oficiales de NVIDIA en Hugging Face; las de
  Whisper, de las tablas 11 (Common Voice 9) y 13 (FLEURS) del paper (arXiv 2212.04356). No son
  comparables entre sí al decimal (normalización y conjuntos distintos), y ninguna es audio
  conversacional de campo lejano como el de un móvil en el bolsillo.
- Qwen3-ASR y Cohere Transcribe quedan descartados por tamaño; un usuario reporta que los modelos
  basados en LLM (FunASR-Nano, ~914 MB) ni arrancan en 2 GB de RAM (issue #3913).

### 4.2 Ficheros exactos de los candidatos

**Parakeet TDT 0.6B v3 int8** — HF `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`, commit
`2bda32ec70b097a55adaa07d9a7173915b43cc78`:

| Fichero | Bytes | SHA-256 (LFS) |
|---|---|---|
| `encoder.int8.onnx` | 652 184 281 | `acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247` |
| `decoder.int8.onnx` | 11 845 275 | `179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e` |
| `joiner.int8.onnx` | 6 355 277 | `3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3` |
| `tokens.txt` | 93 939 | (no LFS; git blob `fc43e1c7…`) |

Tarball: `.../asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2`, SHA-256
`5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf`.
Ficha: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 (25 idiomas europeos, PnC, timestamps de
palabra y segmento, CC-BY-4.0).
RTF publicado por sherpa-onnx (2 hilos, hardware **no indicado**, probablemente escritorio): 0,325 en
un clip de 3,8 s y 0,028 en 334 s con VAD.
Dato de campo (issue #3913): el APK de Parakeet v3 (585 MB) funciona en un Android 13 con
Snapdragon 680 y **2 GB de RAM**. RTF en móvil y RAM residente: **NO VERIFICADO**; la única cifra
encontrada (RTF 0,12 en emulador arm64, de soniqo.audio, otro runtime) no sirve como referencia.
Riesgo conocido: sin idioma forzable, puede "pasarse al inglés" en fragmentos cortos o ambiguos (lo
reporta un usuario en francés, issue #3144).

**Canary 180M flash int8** — HF `csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8`,
commit `9077164e0d3dd1d5353743e89ceaa1d3a770838c`:

| Fichero | Bytes | SHA-256 (LFS) |
|---|---|---|
| `encoder.int8.onnx` | 132 678 643 | `7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f` |
| `decoder.int8.onnx` | 74 437 848 | `e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2` |
| `tokens.txt` | 53 555 | (no LFS) |

Tarball SHA-256: `7a38ed8b13f014ad632b09ff8d22e0c6f1359dd046af9235d281dfae841b9ab9`.
RTF publicado por sherpa-onnx (2 hilos, hardware no indicado, logs de macOS): 0,12–0,18 en clips de
2,7–3,8 s. Ficha: https://huggingface.co/nvidia/canary-180m-flash (182 M parámetros; español
entrenado con ~1,4 k horas).

**Nemotron 3.5 ASR streaming 0.6B int8 (560 ms)** — HF
`csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11`, commit
`ab43d895f5985b1bbab8b6eac8607fcdc05343f3`: `encoder.int8.onnx` 657 601 403, `decoder.int8.onnx`
14 978 075, `joiner.int8.onnx` 9 504 438, `tokens.txt` 131 440. Tarball SHA-256
`c6bf5e0df765f9d5b43bc9e0536d4b4b3e7d40bdf5ecf13e45f134c51c05ae3a`. Ficha:
https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b.

**Moonshine base-es** — HF `csukuangfj2/sherpa-onnx-moonshine-base-es-quantized-2026-02-27`, commit
`59e235a505b7372eb7f896471a74c776f6f6e061`: `encoder_model.ort` 20 964 320
(`331aafa2…72b3`), `decoder_model_merged.ort` 43 612 200 (`8e6513ad…2b5d`), `tokens.txt` 532 090.
Config Kotlin (entrada 58 de `getOfflineModelConfig`):
`OfflineModelConfig(moonshine = OfflineMoonshineModelConfig(encoder = "$d/encoder_model.ort", mergedDecoder = "$d/decoder_model_merged.ort"), tokens = "$d/tokens.txt")`.

**Whisper (sherpa-onnx)** — HF `csukuangfj/sherpa-onnx-whisper-{tiny,base,small,turbo}`:
tiny `tiny-encoder.int8.onnx` 12 937 772 + `tiny-decoder.int8.onnx` 89 855 401; base 29 120 534 +
130 672 026; small 112 442 483 + 262 226 114; turbo 674 716 297 + 361 080 764; `*-tokens.txt`
816 730. Config: `OfflineModelConfig(whisper = OfflineWhisperModelConfig(encoder=…, decoder=…, language="es"), tokens=…)`.

---

## 5. VAD

| Fichero | Bytes | SHA-256 | URL |
|---|---|---|---|
| `silero_vad.onnx` | 643 854 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx |
| `silero_vad.int8.onnx` | 212 860 | `c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20` | …/asr-models/silero_vad.int8.onnx |
| `ten-vad.onnx` | 332 211 | `718cb7eef47e3cf5ddbe7e967a7503f46b8b469c0706872f494dfa921b486206` | …/asr-models/ten-vad.onnx |
| `silero_vad_v5.onnx` (upstream) | 2 313 101 | — | …/asr-models/silero_vad_v5.onnx |

`silero_vad.onnx` es una exportación de k2-fsa con la interfaz de v4 (entradas `x,h,c`, salidas
`prob,new_h,new_c`; `IsExportedByK2Fsa()` en `silero-vad-model.cc`); solo 16 kHz. La versión exacta de
Silero de la que parte no está documentada.

Parámetros recomendados para conversación ambiente (punto de partida, a calibrar en dispositivo):
`threshold 0.5`, `minSilenceDuration 0.5–0.8 s` (frases completas, menos cortes), `minSpeechDuration
0.25 s`, `windowSize 512`, `maxSpeechDuration 20–25 s` para Parakeet/Canary (< 28 s si se usa
Whisper). El issue #3913 confirma que la segmentación la decide el VAD, no el ASR.

---

## 6. Identificación del hablante dueño

Release `speaker-recongition-models` (sic, así se llama el tag); checksums en `checksum.txt` del mismo
tag.

| Modelo | Bytes | SHA-256 |
|---|---|---|
| `wespeaker_en_voxceleb_resnet34_LM.onnx` | 26 530 550 | `e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012` |
| `3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx` | 29 596 978 | `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b` |
| `nemo_en_titanet_small.onnx` | 40 257 283 | `ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e` |
| `3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx` (el del ejemplo Android) | 39 593 761 | `1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b` |

URL: `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/<fichero>`.
Hay más (ResNet152/221/293, ERes2NetV2, TitaNet large de 101 MB).

Propuesta: registrar al dueño con 5–10 frases (varios embeddings → `manager.add(name, Array<FloatArray>)`),
y por cada segmento del VAD de ≥ 1,5 s calcular el embedding y `verify("owner", emb, umbral)`. Los
modelos están entrenados con VoxCeleb (multilingüe de facto) o datos chinos; la precisión con español
y con ruido de bolsillo **NO VERIFICADA**: calibrar el umbral con grabaciones reales (el ejemplo parte
de 0,5). Coste: un modelo de ~27 MB más una pasada corta por segmento.

---

## 7. Comparativa con alternativas

| | sherpa-onnx (Parakeet v3) | whisper.cpp | Android `SpeechRecognizer` on-device | Vosk |
|---|---|---|---|---|
| Versión actual | v1.13.8 (10-sep-2026) | v1.9.5 (6-oct-2026) | Plataforma (API 31+) | `com.alphacephei:vosk-android:0.3.75` en Maven Central (dic-2025) |
| Integración | AAR / JitPack, API Kotlin hecha | Sin AAR: compilar con NDK el ejemplo `examples/whisper.android` (JNI propio) | Nativa, sin dependencias | AAR en Maven Central |
| Español | WER 3,45 FLEURS, PnC, timestamps | Modelos GGML multilingües: tiny 77,7 MB, base 148 MB, small 488 MB (q5_1: 32 / 60 / 190 MB), large-v3-turbo-q5_0 574 MB; WER = las de Whisper (small FLEURS 5,6) | Depende del paquete de idioma de Google; sin cifras públicas | `vosk-model-small-es-0.42` 39 MB: WER 16,02 (CV), 11,21 (MLS); `vosk-model-es-0.42` 1,4 GB: 7,50 (CV) |
| Continuo en segundo plano | Sí (tú controlas `AudioRecord`) | Sí, pero ventanas de 30 s y coste alto | La doc: "not intended to be used for continuous recognition"; métodos "must be invoked only from the main application thread" | Sí |
| Puntuación | Nativa | Sí | Variable | No |
| Timestamps | Por token | Sí | `EXTRA_REQUEST_WORD_TIMING` (API 34), dependiente de la implementación | Por palabra |

Detalles verificados de `SpeechRecognizer`: `createOnDeviceSpeechRecognizer` (API 31),
`checkRecognitionSupport` y `triggerModelDownload` (API 33, pueden pedir interacción del usuario),
`EXTRA_AUDIO_SOURCE` + `EXTRA_SEGMENTED_SESSION` (API 33) permiten pasarle audio propio por
`ParcelFileDescriptor`. Aun así, el resultado depende del fabricante y de Google, y no se controla el
modelo. Descartado como motor principal.

whisper.cpp: viable, pero obliga a mantener una JNI propia y un build NDK, procesa en ventanas fijas de
30 s (más cómputo por segundo de voz que un transducer) y Whisper tiende a alucinar texto en
silencio y ruido. Para el mismo tamaño que Parakeet v3, Whisper small da peor WER en español
(5,6 frente a 3,45 en FLEURS). Sin ventaja para este caso.

---

## 8. Recomendación e implementación

1. **Dependencia**: AAR v1.13.8 vendorizado y comprobado con SHA-256 (opción B), o JitPack
   `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8` (opción A). `abiFilters` arm64-v8a + x86_64
   (emulador). Regla R8 `-keep class com.k2fsa.sherpa.onnx.** { *; }`.
2. **Modelos fuera del APK**: el primer arranque descarga de HF con URL fijada a commit
   (`/resolve/<sha>/…`) a `filesDir/models/…`, comprueba tamaño y SHA-256 y solo entonces construye
   los objetos con `assetManager = null`. `silero_vad.onnx` (644 KB) sí puede ir en assets.
3. **Por defecto Parakeet v3 int8; Canary 180M int8 como alternativa ligera** (selector en
   ajustes). Preparar un A/B en el dispositivo real con audio propio: si Parakeet se pasa a inglés en
   frases cortas, cambiar a Canary con `srcLang="es"` o probar Nemotron 3.5 con `language="es"`.
4. **Hilos**: un hilo de captura (AudioRecord + VAD) y un único hilo de ASR
   (`Executors.newSingleThreadExecutor()` con prioridad de background), `numThreads = 2`. El
   reconocedor se crea una vez por sesión del servicio, no por segmento.
5. **Batería** (diseño, no verificado): el VAD corre siempre, pero el ASR solo sobre voz. Si en el
   dispositivo el RTF de Parakeet es alto, encolar los segmentos y transcribir de forma diferida
   (cargando o con batería > X %), o usar Canary.

### Snippet Kotlin (verificado contra las firmas de v1.13.8)

```kotlin
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*
import java.util.concurrent.Executors

private const val SR = 16000

class SpeechPipeline(private val modelsDir: String, private val onText: (Long, Long, OfflineRecognizerResult) -> Unit) {

    private val vad = Vad(
        assetManager = null, // rutas de fichero
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$modelsDir/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.6f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 20f, // corte suave: sube el umbral a 0.9, no corta en seco
            ),
            sampleRate = SR,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    private val p = "$modelsDir/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SR, featureDim = 80), // NeMo lo sobrescribe desde metadatos
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$p/encoder.int8.onnx",
                    decoder = "$p/decoder.int8.onnx",
                    joiner = "$p/joiner.int8.onnx",
                ),
                tokens = "$p/tokens.txt",
                modelType = "nemo_transducer",
                numThreads = 2,
            ),
            decodingMethod = "greedy_search",
        ),
    )
    // Alternativa ligera:
    // modelConfig = OfflineModelConfig(
    //     canary = OfflineCanaryModelConfig(encoder = "$c/encoder.int8.onnx", decoder = "$c/decoder.int8.onnx",
    //                                       srcLang = "es", tgtLang = "es", usePnc = true),
    //     tokens = "$c/tokens.txt", numThreads = 2)

    private val asr = Executors.newSingleThreadExecutor()
    @Volatile var running = false

    private fun decode(samples: FloatArray): OfflineRecognizerResult {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SR)
            recognizer.decode(stream)
            return recognizer.getResult(stream) // text, tokens, timestamps (s, relativos al segmento), lang, durations
        } finally {
            stream.release()
        }
    }

    private fun submit(seg: SpeechSegment, epochMs: Long) {
        val startMs = epochMs + seg.start * 1000L / SR
        val maxLen = 25 * SR // maxSpeechDuration no es un tope duro
        var off = 0
        while (off < seg.samples.size) {
            val end = minOf(off + maxLen, seg.samples.size)
            val chunk = seg.samples.copyOfRange(off, end)
            val chunkStartMs = startMs + off * 1000L / SR
            asr.execute { onText(chunkStartMs, chunkStartMs + chunk.size * 1000L / SR, decode(chunk)) }
            off = end
        }
    }

    private fun drain(epochMs: Long) {
        while (!vad.empty()) {
            val seg = vad.front() // copia desde JNI: segura para pasarla a otro hilo
            vad.pop()
            submit(seg, epochMs)
        }
    }

    // Llamar desde el hilo de captura del foreground service (permiso RECORD_AUDIO ya concedido).
    fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 512 * 2) * 2)
        val buf = ShortArray(512)
        var epochMs = System.currentTimeMillis() // instante de la muestra 0 desde el último reset()
        var fed = 0L
        running = true
        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                vad.acceptWaveform(FloatArray(n) { buf[it] / 32768f })
                fed += n
                drain(epochMs)
                // ponytail: SpeechSegment.start es int32 en C++ y desborda a ~37 h; además el reloj de
                // muestras deriva respecto al de pared. Reset cada hora en silencio acota ambas cosas.
                if (fed >= SR * 3600L && !vad.isSpeechDetected()) {
                    vad.reset()
                    fed = 0
                    epochMs = System.currentTimeMillis()
                }
            }
        } finally {
            vad.flush()
            drain(epochMs)
            rec.stop()
            rec.release()
        }
    }

    fun close() {
        running = false
        asr.shutdown()
        // tras awaitTermination: recognizer.release(); vad.release()
    }
}
```

Notas del snippet:
- `vad.front()/pop()/acceptWaveform()` solo desde el hilo de captura; `recognizer` solo desde el
  executor. El ejemplo oficial `SherpaOnnxVadAsr` lanza un decode por segmento en paralelo en
  `Dispatchers.IO`; aquí se serializa para acotar RAM y CPU.
- `AudioSource.VOICE_RECOGNITION` suele desactivar AGC y procesado agresivo; `MIC` es lo que usa el
  ejemplo. Probar ambos en el dispositivo.
- Timestamp absoluto del segmento = `epochMs + seg.start/16 000`; de palabra/token =
  inicio del segmento + `result.timestamps[i]` (Parakeet). Canary devuelve `timestamps` vacío: solo
  hay hora de segmento.
- El check mínimo que hay que dejar es un test instrumentado que pase un WAV de prueba en español por
  `decode()` y compruebe que el texto no sale vacío y que `timestamps.size == tokens.size`.

---

## 9. Pendiente de verificar en dispositivo (no hay datos fiables publicados)

1. RTF y RAM residente de Parakeet v3 int8 y Canary int8 con 2 hilos en el móvil real
   (`adb shell dumpsys meminfo <paquete>`; tiempo de `decode` / duración del segmento).
2. Tiempo de carga del encoder de 652 MB (afecta al arranque del servicio).
3. Consumo de batería del bucle VAD 24/7 con la pantalla apagada.
4. Tasa de "salto a inglés" de Parakeet v3 en frases cortas en español frente a Canary (`es` forzado).
5. Umbral de `verify()` del speaker ID con voz del dueño en bolsillo y con terceros.
6. Si `libonnxruntime.so` 1.28.2 está alineado a 16 KB (Android 15+ con páginas de 16 KB).

---

## Fuentes

- Releases sherpa-onnx (API): https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/v1.13.8 · https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8
- Modelos ASR: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
- Modelos de hablante: https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-recongition-models
- Código Kotlin del tag: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/sherpa-onnx/kotlin-api (Vad.kt, OfflineRecognizer.kt, OfflineStream.kt, OnlineRecognizer.kt, OnlineStream.kt, Speaker.kt, SpeakerEmbeddingExtractorConfig.kt, FeatureConfig.kt)
- Ejemplos Android: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/android/SherpaOnnxVadAsr · https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/android/SherpaOnnxSimulateStreamingAsr · https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/android/SherpaOnnxSpeakerIdentification · https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8/android/SherpaOnnxAar
- Build y workflow: https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/build-android-arm64-v8a.sh · https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/.github/workflows/android.yaml · https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/jitpack.yml
- C++ consultado: `sherpa-onnx/csrc/voice-activity-detector.cc`, `circular-buffer.h`, `silero-vad-model.cc`, `offline-recognizer-transducer-nemo-impl.h`, `offline-transducer-nemo-model.cc`, `offline-recognizer-canary-impl.h`, `offline-recognizer-whisper-impl.h`, `online-recognizer-transducer-nemo-impl.h`, `online-transducer-nemo-model.cc`, `jni/voice-activity-detector.cc` (tag v1.13.8)
- JitPack: https://jitpack.io/com/github/k2-fsa/sherpa-onnx/v1.13.8/build.log · https://jitpack.io/api/builds/com.github.k2-fsa/sherpa-onnx/latest
- Docs sherpa-onnx: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html · https://k2-fsa.github.io/sherpa/onnx/nemo/canary.html · https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html · https://k2-fsa.github.io/sherpa/onnx/android/apk-simulate-streaming-asr.html · https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html
- Fichas de modelo: https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 · https://huggingface.co/nvidia/canary-180m-flash · https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b
- Repos HF de los modelos convertidos: https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 · https://huggingface.co/csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8 · https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11 · https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-es-quantized-2026-02-27 · https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small
- Issues con datos de campo: https://github.com/k2-fsa/sherpa-onnx/issues/3913 (2 GB de RAM) · https://github.com/k2-fsa/sherpa-onnx/issues/3144 (Parakeet v3 se pasa a inglés) · https://github.com/k2-fsa/sherpa-onnx/issues/3961 (Canary lang2id) · https://github.com/k2-fsa/sherpa-onnx/pull/3671 (Nemotron 3.5 multilingüe)
- Whisper paper (tablas 11 y 13): https://ar5iv.labs.arxiv.org/html/2212.04356
- whisper.cpp: https://github.com/ggml-org/whisper.cpp/releases · https://huggingface.co/ggerganov/whisper.cpp
- Vosk: https://alphacephei.com/vosk/models · https://repo1.maven.org/maven2/com/alphacephei/vosk-android/maven-metadata.xml
- Android: https://developer.android.com/reference/android/speech/SpeechRecognizer · https://developer.android.com/reference/android/speech/RecognizerIntent
- Secundarias (no usadas para decidir): https://soniqo.audio/benchmarks/android (RTF en emulador, otro runtime) · https://www.mintlify.com/moonshine-ai/moonshine/models/available-models (WER Moonshine es 4,33)

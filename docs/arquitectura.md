# Mysone — arquitectura v1

App Android personal que escucha en segundo plano, transcribe **en el móvil**, agrupa lo oído en
conversaciones con fecha y hora, genera notas, tareas y recordatorios con IA, y deja preguntar en
lenguaje natural sobre todo lo escuchado. Inspirada en Bee (Amazon), sin wearable.

Este documento es el **contrato** entre los módulos. Las fuentes y los datos verificados están en
`docs/investigacion/01..08`. Si algo de aquí contradice a la investigación, manda este documento
(las decisiones ya están tomadas); si contradice a la realidad del código o de la documentación
oficial, manda la realidad: corrige y deja nota.

---

## 1. Decisiones

| Tema | Decisión | Motivo (detalle en docs/investigacion) |
|---|---|---|
| Stack | Kotlin + Jetpack Compose nativo, un solo módulo `:app` | Todo el bucle siempre activo (servicio, audio, VAD, ASR, BD, alarmas) es nativo en cualquier opción; RN pausa los timers JS en segundo plano y duplicaría SQLite en el proceso (07) |
| Plataforma | Solo Android. iOS no: no se compila en Windows y no deja arrancar grabación en segundo plano (07) | — |
| SDK | `minSdk 31`, `compileSdk 37`, `targetSdk 36` | 37 obligatorio por core/Compose/Nav3; target 36 = el exigido por Play y el que podemos probar en el emulador API 36 (02, 04) |
| Toolchain | AGP **9.1.1** (último que soporta el Android Studio instalado, Panda 3; 9.4.1 compila pero el IDE no sincroniza), Gradle 9.8.1, Kotlin 2.4.21, KSP 2.3.12, JDK = JBR 21 de Android Studio | 04. Plantilla verificada: `scratchpad/tcheck` |
| ASR | sherpa-onnx **1.13.8** (JitPack `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8`), ABIs `arm64-v8a` + `x86_64` | 03 |
| Modelo ASR recomendado | **Según la RAM** (`recommendedModelId(deviceRamGb())`): **NeMo Parakeet TDT 0.6B v3 int8** (670 MB, multilingüe) con **≥ 6 GB**; con menos o si no se sabe, Canary | Parakeet es el más preciso en el diagnóstico de escritorio: WER 2,8 % frente a 9,2 % de Canary, 42/42 «ñ», números en cifras. Pica en ~1,1 GB y el Memory Limiter de Android 17 deja 2 GiB al servicio con 6 GB (1 GiB con 4 GB) (08, 07 P8) |
| Modelo ligero | **NeMo Canary 180M flash int8** (207 MB, `srcLang="es"` forzado, PnC, MLS-es 3,17 %). Valor por defecto de `AppSettings.asrModelId` | Menos RAM (~0,75–0,9 GB de pico) y descarga, idioma forzable (Parakeet se pasa al inglés con voz muy degradada o en monosílabos) (03, 07, 08) |
| VAD | `silero_vad.onnx` (643 854 B) incluido en `assets/` | 03 |
| BD | Room **3.0.3** (`androidx.room3`) + `BundledSQLiteDriver` + **FTS5** (`unicode61 remove_diacritics 2`, ranking `bm25`) | FTS5 no está garantizado en el SQLite del sistema; el bundled sí (04) |
| Navegación | Navigation 3 1.2.0 | 04 |
| DI | Manual: `AppContainer` creado en `MysoneApp` | Sin Hilt (07) |
| HTTP / IA | OkHttp 5.5.0 + kotlinx.serialization. **Anthropic por HTTP directo** (Messages API) y otro cliente para APIs compatibles con OpenAI | El SDK `anthropic-java` 2.72.0 añade un jar de 39 MB, Jackson con reflexión y kotlin-reflect, y arrastra OkHttp 4; para dos endpoints basta OkHttp, que ya está |
| IA por defecto | **Desactivada** hasta que el usuario elige proveedor, pone su clave y acepta el aviso de envío. Preset por defecto: Anthropic, `claude-haiku-5-5` (procesado en segundo plano, effort `low`) y `claude-opus-5-5` (respuestas, effort `medium`) | Coste ≈ 1 $/mes de procesado + respuestas; ver §7.6 (05) |
| Audio guardado | **No.** El audio de voz vive en una cola en disco (PCM16) solo hasta transcribirse; luego se borra | Legal y Bee (06, 01). Conservar audio N días queda para v2 |
| Monitorización | `sentry-android-core` 8.61.0, init manual solo si hay DSN en la compilación, sin PII ni breadcrumbs con contenido | Regla global + RGPD (04, 06) |
| Paquete | `dev.javieraguilera.mysone`, nombre visible **Mysone** | — |

Fuera de v1 (anotado para después): identificación de la voz del dueño (filtro "solo donde hablo
yo"), conservar audio cifrado N días, embeddings, widget y tile de ajustes rápidos, geovallas,
bloqueo biométrico, SQLCipher (de momento: cifrado de ficheros del sistema + `allowBackup=false`).

---

## 2. Estructura de paquetes y propiedad

`app/src/main/java/dev/javieraguilera/mysone/`. La columna "Dueño" indica qué fase/agente escribe
cada fichero. **Un agente solo modifica sus ficheros.** Los contratos (firmas públicas de §4) los
crea la fase A como stubs que compilan; la fase B rellena cuerpos sin cambiar firmas. Si una firma
es imposible, se cambia lo mínimo y se avisa en el informe del agente.

| Paquete / fichero | Dueño | Contenido |
|---|---|---|
| `MysoneApp.kt`, `AppContainer.kt`, `MainActivity.kt` | A | Application (Sentry, canales, workers), contenedor, actividad con `NavDisplay` |
| `core/` | A | `Time.kt` (formatos es-ES, zona, inicio/fin de día), `Json.kt` (instancia `Json` compartida), `AppScope` |
| `data/` | A | `Entities.kt`, `Daos.kt`, `AppDatabase.kt`, `SettingsStore.kt`, `SecretStore.kt` |
| `capture/` | B1 | `ListeningService.kt`, `AudioCapture.kt`, `PendingQueue.kt`, `ConversationTracker.kt`, `ListeningNotification.kt`, `ListeningController.kt` (contrato), `ResumeNotifier.kt` |
| `asr/` | B2 | `VadSegmenter.kt` (contrato), `Transcriber.kt` (contrato), `AsrModels.kt`, `ModelManager.kt` (contrato), `AudioDecoder.kt`, `Resampler.kt`, `AudioImporter.kt` (contrato), `ImportWorker.kt`, `PcmFiles.kt` |
| `ai/` | B3 | `AiPresets.kt` (A lo escribe completo), `LlmClient.kt`, `AnthropicClient.kt`, `OpenAiCompatClient.kt`, `Prompts.kt`, `Schemas.kt`, `AiProcessing.kt` (contrato), `ConversationProcessor.kt`, `QuickReminders.kt`, `AskEngine.kt` (contrato), `DigestBuilder.kt`, `ProcessWorker.kt` |
| `tasks/` | B4 | `ReminderScheduler.kt` (contrato), `ReminderReceiver.kt`, `SystemEventsReceiver.kt`, `MaintenanceWorker.kt`, `DataManager.kt` (contrato) |
| `ui/theme/`, `ui/components/`, `ui/nav/` | A | Tema, componentes compartidos mínimos, rutas |
| `ui/onboarding/`, `ui/today/`, `ui/conversation/`, `ui/search/` | B5a | Pantallas + ViewModels |
| `ui/ask/`, `ui/tasks/`, `ui/memory/`, `ui/settings/` | B5b | Pantallas + ViewModels |
| `res/values/strings.xml` | A | Cadenas comunes. Cada módulo B añade las suyas en `res/values/strings_<modulo>.xml` |
| `AndroidManifest.xml`, `build.gradle.kts`, `proguard-rules.pro` | A | Todo lo declarado de antemano (§9) |
| `tools/mock-llm.mjs` | B3 | Servidor falso de IA para pruebas (§11) |

Textos de la UI en **español de España**, tuteo. Nada de inglés visible.

---

## 3. Modelo de datos (Room 3, `mysone.db`)

Tiempos en **epoch ms UTC** (`Long`); se muestran en la zona del dispositivo. `state` como `String`
con constantes en un `object` (sin TypeConverters).

```kotlin
@Entity(tableName = "conversation", indices = [Index("startedAt"), Index("state")])
data class Conversation(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val startedAt: Long,
  val endedAt: Long,
  val source: String,            // ConvSource.MIC | IMPORT | CALL (grabación de llamada, §5.5)
  val state: String,             // ConvState.OPEN | READY | PROCESSING | DONE | FAILED
  val title: String? = null,
  val summary: String? = null,
  val keyPoints: String? = null, // JSON array de strings
  val isNoise: Boolean = false,  // TV, radio, ruido de fondo: se atenúa en la UI y no genera tareas
  val processAttempts: Int = 0,
  val processedAt: Long? = null,
  val lastError: String? = null, // mensaje corto, sin contenido de la conversación
)

@Entity(tableName = "segment",
  foreignKeys = [ForeignKey(Conversation::class, ["id"], ["conversationId"], onDelete = CASCADE)],
  indices = [Index("conversationId"), Index("startedAt")])
data class Segment(@PrimaryKey(autoGenerate = true) val id: Long = 0,
  val conversationId: Long, val startedAt: Long, val endedAt: Long, val text: String)

@Fts5(contentEntity = Segment::class, tokenizer/args → "unicode61 remove_diacritics 2")
@Entity(tableName = "segment_fts") data class SegmentFts(val text: String)

@Fts5(contentEntity = Conversation::class, mismo tokenizer)
@Entity(tableName = "conversation_fts") data class ConversationFts(val title: String?, val summary: String?)

@Entity(tableName = "pending_audio", foreignKeys = [→ conversation CASCADE], indices = [Index("conversationId")])
data class PendingAudio(@PrimaryKey(autoGenerate = true) val id: Long = 0,
  val conversationId: Long, val startedAt: Long, val endedAt: Long,
  val path: String,               // fichero PCM16 LE mono 16 kHz en filesDir/pending/
  val attempts: Int = 0)          // se incrementa ANTES de decodificar (evita bucles si el nativo mata el proceso)

@Entity(tableName = "task", foreignKeys = [→ conversation CASCADE], indices = [Index("conversationId"), Index("state"), Index("remindAt")])
data class TaskItem(@PrimaryKey(autoGenerate = true) val id: Long = 0,
  val text: String,
  val state: String,              // TaskState.SUGGESTED | OPEN | DONE | DISMISSED
  val dueAt: Long? = null,        // fecha límite (informativa)
  val remindAt: Long? = null,     // si no es null y state == OPEN → hay alarma
  val conversationId: Long? = null, // null = creada a mano
  val sourceQuote: String? = null,
  val createdAt: Long,
  val notifiedAt: Long? = null)

@Entity(tableName = "fact", foreignKeys = [→ conversation CASCADE], indices = [Index("conversationId")])
data class Fact(@PrimaryKey(autoGenerate = true) val id: Long = 0,
  val text: String, val state: String /* FactState.SUGGESTED | CONFIRMED */,
  val conversationId: Long? = null, val createdAt: Long)

@Entity(tableName = "daily_digest")
data class DailyDigest(@PrimaryKey val day: String /* "2026-10-15", zona local */,
  val summary: String, val highlights: String /* JSON array */, val createdAt: Long)

@Entity(tableName = "chat_message")
data class ChatMessage(@PrimaryKey(autoGenerate = true) val id: Long = 0,
  val role: String /* user | assistant */, val text: String,
  val citations: String? = null /* JSON array de conversationId */, val createdAt: Long)

@Entity(tableName = "listening_gap", indices = [Index("startedAt")])
data class ListeningGap(@PrimaryKey(autoGenerate = true) val id: Long = 0,
  val startedAt: Long, val endedAt: Long? = null,
  val reason: String /* GapReason.PAUSED | STOPPED | MIC_BUSY | BATTERY | ERROR | KILLED */)
```

Las FK de `task` y `fact` borran en cascada: borrar u olvidar una conversación borra lo derivado
(salvaguarda legal 06 §11). Las creadas a mano (`conversationId = null`) no se tocan.

DAOs (suspend/Flow; lista orientativa, la fase A puede añadir las que vea necesarias):
- `ConversationDao`: `insert`, `update`, `get(id)`, `observe(id): Flow`, `observeDay(from, to): Flow<List<Conversation>>`,
  `findOpen(): Conversation?`, `byState(state)`, `readyWithoutPending(): List<Conversation>`,
  `inRange(from, to, limit)`, `searchFts(q, limit)`, `delete(id)`, `countByState(): Flow`.
- `SegmentDao`: `insert`, `forConversation(id): Flow`, `textForConversation(id): List<Segment>`,
  `search(q, from, to, limit): List<SegmentHit>` con `snippet(segment_fts, 0, '«', '»', '…', 12)` y `ORDER BY bm25(segment_fts)`,
  `inRange(from, to)`, `deleteOlderThan(ts)`, `deleteSince(ts)`, `countSince(ts): Flow<Int>`.
- `PendingAudioDao`: `insert`, `next(): PendingAudio?` (más antiguo), `bumpAttempts(id)`, `delete(id)`,
  `countForConversation(id)`, `count(): Flow<Int>`, `totalDurationMs()`, `all()`.
- `TaskDao`, `FactDao`, `DigestDao`, `ChatDao`, `GapDao` con CRUD + Flows por estado.

`SegmentHit(segmentId, conversationId, startedAt, snippet)`. Sanear la consulta FTS: tokens
`[\p{L}\p{N}]+`, cada uno como `"token"*`; búsqueda del usuario = AND (espacio), recuperación de la IA = `OR`.

`schemas/` se versiona en git (`room3 { schemaDirectory(...) }`). v1 = `version = 1`, sin migraciones.

### Ajustes (DataStore Preferences, `SettingsStore`)

```kotlin
data class AppSettings(
  val onboardingDone: Boolean = false,
  val legalAcceptedAt: Long? = null,
  val listeningEnabled: Boolean = false,       // intención del usuario; sirve para "Toca para reanudar"
  val audioSource: String = "VOICE_RECOGNITION", // o "MIC" (voz lejana)
  val conversationGapMin: Int = 4,             // silencio que cierra una conversación (2–15)
  val asrModelId: String = "canary-180m-es",
  val downloadWifiOnly: Boolean = true,        // modelos ASR: descargar solo con Wi-Fi
  val batterySaver: Boolean = true,            // batería < 20 % y sin cargar → se encola sin transcribir
  val retentionDays: Int = 90,                 // transcripción literal; 0 = no borrar. Resúmenes, tareas y facts se quedan
  val secureScreen: Boolean = true,            // FLAG_SECURE
  val quickReminders: Boolean = true,          // detectar "recuérdame…" al momento
  val callFolder: String? = null,              // árbol SAF de la grabadora de llamadas del teléfono (§5.5)
  val callDeleteAfter: Boolean = true,         // borrar la grabación cuando ya está transcrita
  val ai: AiSettings = AiSettings(),
)
data class AiSettings(
  val enabled: Boolean = false, val consentAt: Long? = null,
  val presetId: String = "anthropic", val provider: String = "ANTHROPIC", // o "OPENAI_COMPAT"
  val baseUrl: String = "https://api.anthropic.com",
  val modelBackground: String = "claude-haiku-5-5", val modelAnswer: String = "claude-opus-5-5",
  val jsonMode: String = "schema",             // "schema" (json_schema estricto) | "object" (json_object, DeepSeek)
)
```
`SecretStore`: clave del proveedor cifrada con AES-256-GCM en Android Keystore (código verificado en
`scratchpad/tcheck/.../Secrets.kt`), sin autenticación de usuario (el servicio la lee con pantalla
bloqueada). Si el descifrado falla, se borra y la UI pide la clave de nuevo. **La clave nunca va a
logs, Sentry ni exportaciones.**

---

## 4. Contratos entre módulos

Las firmas son vinculantes; los nombres de parámetros, orientativos.

```kotlin
// capture/ListeningController.kt (B1) — API para la UI y los receivers
sealed interface ListeningStatus {
  data object Off : ListeningStatus
  data object Starting : ListeningStatus
  data class Listening(val level: Float /* 0..1, para el medidor */, val pendingChunks: Int) : ListeningStatus
  data class Paused(val untilMs: Long) : ListeningStatus
  data object MicBusy : ListeningStatus          // isClientSilenced: llamada, otra app grabando
  data object NeedsModel : ListeningStatus
  data object NeedsPermission : ListeningStatus
  data class Error(val message: String) : ListeningStatus
}
object ListeningController {
  val status: StateFlow<ListeningStatus>
  fun start(context: Context)               // solo desde UI visible o interacción con notificación
  fun stop(context: Context)
  fun pause(context: Context, minutes: Int)
  fun resume(context: Context)
  internal fun publish(s: ListeningStatus)  // lo usa el servicio
}

// asr/VadSegmenter.kt (B2) — lo usan la captura en vivo (B1) y la importación (B2)
class VadSegmenter(context: Context) : AutoCloseable {
  /** frame: float [-1,1] a 16 kHz (cualquier tamaño). Devuelve trozos de voz terminados, ≤ 25 s cada uno. */
  fun accept(frame: FloatArray): List<SpeechChunk>
  fun flush(): List<SpeechChunk>
  val isSpeaking: Boolean
  override fun close()
}
/** startSample: posición absoluta (Long) desde la creación del segmentador; el wrapper gestiona el
 *  desbordamiento int32 de sherpa (reset en silencio) y trocea a 25 s. */
class SpeechChunk(val startSample: Long, val samples: FloatArray)

// asr/Transcriber.kt (B2) — una sola instancia (en AppContainer), compartida
sealed interface TranscriberState { NoModel; Loading; Ready(modelId); Error(message) }
class Transcriber(context: Context, models: ModelManager, settings: SettingsStore) {
  val state: StateFlow<TranscriberState>
  suspend fun acquire(): Boolean   // carga el modelo elegido si hace falta (refcount); false si no hay modelo o falla
  fun release()                    // al llegar a 0 libera la memoria nativa
  suspend fun transcribe(samples: FloatArray, sampleRate: Int = 16_000): String // serializado con Mutex; texto limpio
}

// asr/ModelManager.kt (B2)
data class AsrModel(val id: String, val name: String, val description: String, val sizeBytes: Long,
  val recommendedMinRamGb: Int, val license: String, val files: List<ModelFile>)
data class ModelFile(val name: String, val url: String, val sizeBytes: Long, val sha256: String?)
sealed interface ModelInstall { NotInstalled; Downloading(progress: Float); Verifying; Installed; Failed(message) }
class ModelManager(context: Context) {
  val catalog: List<AsrModel>
  val installs: StateFlow<Map<String, ModelInstall>>
  fun download(id: String, allowMetered: Boolean)
  fun cancel(id: String)
  suspend fun delete(id: String)
  fun isInstalled(id: String): Boolean
  fun modelDir(id: String): File
  fun deviceRamGb(): Int
  suspend fun selfTest(id: String): Result<String>   // transcribe assets/selftest_es.wav
}

// asr/AudioImporter.kt (B2)
object AudioImporter {
  fun enqueue(context: Context, uri: Uri, callToken: String? = null)   // lee con SAF, ImportWorker decodifica, VAD, transcribe, crea conversación IMPORT (o CALL) → READY
  fun observe(context: Context): Flow<List<ImportProgress>>
}
data class ImportProgress(val id: UUID, val name: String, val progress: Float, val done: Boolean, val error: String?)

// ai/AiProcessing.kt (B3)
class AiProcessing(container: AppContainer) {
  val isConfigured: Flow<Boolean>                        // enabled + consentAt + clave
  fun onSegmentTranscribed(segment: Segment)             // no bloquea; recordatorio rápido si procede
  fun onConversationReady(conversationId: Long)          // no bloquea; procesa en appScope o encola ProcessWorker
  suspend fun processConversation(id: Long, force: Boolean = false): Result<Unit>
  suspend fun processPending(): Int                      // READY sin audio pendiente; devuelve cuántas
  suspend fun buildMissingDigests(): Int
  suspend fun testConnection(): Result<String>           // para Ajustes
}

// ai/AskEngine.kt (B3)
class AskEngine(container: AppContainer) {
  suspend fun ask(question: String): Result<ChatMessage>  // guarda pregunta y respuesta en chat_message
  suspend fun clearChat()
}
/** Citas en la respuesta: marcadores literales [c:123] (id de conversación). La UI los convierte en chips. */

// tasks/ReminderScheduler.kt (B4)
class ReminderScheduler(context: Context) {
  fun schedule(task: TaskItem)          // si state OPEN y remindAt futuro
  fun cancel(taskId: Long)
  suspend fun rescheduleAll()           // arranque, cambio de hora/zona, actualización
}

// tasks/DataManager.kt (B4)
class DataManager(container: AppContainer) {
  suspend fun forgetLast(minutes: Int)            // segmentos, cola pendiente y conversaciones vaciadas
  suspend fun deleteConversation(id: Long)
  suspend fun exportJson(target: Uri)             // todo menos la clave
  suspend fun deleteAll()                         // BD, cola, chat, ajustes de IA y clave; conserva modelos ASR
  suspend fun applyRetention()
}

// AppContainer (A)
class AppContainer(val app: Application) {
  val appScope: CoroutineScope        // SupervisorJob + Dispatchers.Default
  val db: AppDatabase
  val settings: SettingsStore; val secrets: SecretStore
  val models: ModelManager; val transcriber: Transcriber
  val ai: AiProcessing; val ask: AskEngine
  val reminders: ReminderScheduler; val data: DataManager
}
// Acceso: (context.applicationContext as MysoneApp).container
```

---

## 5. Captura y transcripción (B1 + B2)

### 5.1 Servicio `ListeningService`
- `foregroundServiceType="microphone"`, `exported=false`. Acciones por `Intent`: `START`, `STOP`,
  `PAUSE` (extra minutos), `RESUME`, `PAUSE_END` (solo la alarma de fin de pausa). `START_STICKY`.
- `START` y `RESUME` se ignoran con el móvil bloqueado (`KeyguardManager.isDeviceLocked`), comprobado ya en primer
  plano: el "Reanudar" de las notificaciones pide desbloquear solo en la interfaz del sistema, y quien guarde su
  `PendingIntent` (un oyente de notificaciones, un reloj) podría enviarlo sin hacerlo. Esos `PendingIntent` se
  cancelan al salir de la pausa y al quitar el aviso "La escucha se ha detenido" (al escuchar o detener).
- `onStartCommand`: **lo primero** `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)`
  dentro de `try/catch` (`ForegroundServiceStartNotAllowedException`, `SecurityException`, …). Si falla:
  estado `Error`, `ResumeNotifier.show()` ("La escucha se ha detenido" con la acción "Reanudar", que pide
  desbloquear el móvil) y `stopSelf()`.
- Intent nulo (reinicio sticky): si `listeningEnabled`, intentar reanudar; si el micro no abre,
  `ResumeNotifier`. Registrar hueco `KILLED` desde el último instante conocido.
- Comprobaciones antes de capturar: permiso `RECORD_AUDIO` (si no: `NeedsPermission`), modelo
  instalado (si no: `NeedsModel`, no se graba), `transcriber.acquire()`. Borrar el último modelo con la escucha en
  marcha o en pausa la para igual (`NeedsModel`, se suelta el micro). `NeedsModel` pasa a `Off` en cuanto hay un
  modelo instalado (`ListeningController.leaveNeedsModelOnInstall`).
- Huecos: como mucho uno abierto; uno de menos de 10 s no se guarda. Al quedar detenida sin modelo o sin permiso, el
  que siga abierto (la pausa al reanudar, un `KILLED`) pasa a `STOPPED`.
- **Pausar no llama a `stopForeground()`**: se libera `AudioRecord` (se apaga el indicador del micro)
  y el servicio sigue en primer plano con la notificación "En pausa hasta HH:MM". La reanudación la
  dispara una alarma exacta (`setExactAndAllowWhileIdle`, `ELAPSED_REALTIME_WAKEUP`) con
  `PendingIntent.getService(PAUSE_END)`, que solo reanuda si `elapsedRealtime` ha llegado al fin: adelantar el reloj
  del sistema (a mano o con la hora de red) no acorta la pausa.
- `STOP`: libera todo, `listeningEnabled=false`, hueco `STOPPED`, `stopForeground(REMOVE)` + `stopSelf()`.
- Notificación (canal `listening`, importancia baja, `setOngoing`, `FOREGROUND_SERVICE_IMMEDIATE`):
  título según estado, texto "N fragmentos hoy · M pendientes". Acciones: escuchando → *Pausar 15 min*,
  *Pausar 1 h*, *Detener*; en pausa → *Reanudar*, *Detener*. Actualizar como mucho cada 10 s.
  Nunca mostrar texto transcrito en la notificación.

### 5.2 Hilos
1. **Captura** (hilo dedicado, prioridad `THREAD_PRIORITY_URGENT_AUDIO`): `AudioRecord` 16 kHz mono
   PCM16, fuente `VOICE_RECOGNITION` (o `MIC` según ajuste; nunca `VOICE_COMMUNICATION`/`CAMCORDER`),
   lecturas de 512 muestras → float → `VadSegmenter.accept`. Nivel RMS al `StateFlow` 4 veces por
   segundo. `registerAudioRecordingCallback` antes de `startRecording`: `isClientSilenced` → estado
   `MicBusy` + hueco `MIC_BUSY` (y al volver, cerrar el hueco). Los `SpeechChunk` terminados se pasan
   por `Channel` al hilo 2; la captura nunca espera a disco ni a BD.
2. **Cola** (coroutine en IO): por cada trozo → `ConversationTracker.assign(chunkStartMs, chunkEndMs)`
   → escribe PCM16 en `filesDir/pending/<uuid>.pcm` → inserta `PendingAudio` → avisa al hilo 3.
   Tiempos absolutos: `t0Ms` (reloj de pared al arrancar la captura) + `startSample * 1000 / 16000`.
   Tope de cola: 4 h de audio; si se supera, se descartan los trozos nuevos y se registra hueco `BATTERY`.
3. **ASR** (un único dispatcher de un hilo): bucle `next()` → si `batterySaver` y batería < 20 % sin
   cargar, o estado térmico ≥ `SEVERE`, esperar a `ACTION_POWER_CONNECTED`/mejora (estado visible en
   la notificación). `bumpAttempts` **antes** de decodificar; si `attempts ≥ 3`, borrar el trozo (se
   registra en log sin contenido). `transcriber.transcribe` → si el texto no está vacío, insertar
   `Segment` y llamar `ai.onSegmentTranscribed`. Borrar fichero y fila. Si la conversación del trozo
   está `READY` y ya no le quedan pendientes → `ai.onConversationReady(id)`.

### 5.3 Conversaciones (`ConversationTracker`, lógica pura testeable)
- Un trozo cuyo inicio está a más de `conversationGapMin` del final del último trozo abre
  conversación nueva (`OPEN`); la anterior pasa a `READY`.
- Un temporizador (coroutine `delay`) cierra la conversación abierta tras `conversationGapMin` sin
  voz; también al pausar más de ese tiempo, al detener y al morir/arrancar el servicio (conversaciones
  `OPEN` huérfanas al arrancar → `READY`).
- `endedAt` se actualiza con cada trozo. Conversaciones sin ningún segmento tras transcribir se borran.

### 5.4 ASR (B2)
- Carga desde **ruta de fichero** (`assetManager = null`), salvo el VAD (asset). Antes de construir el
  reconocedor, comprobar que cada fichero existe y tiene el tamaño esperado (los fallos de modelo en
  sherpa-onnx matan el proceso en vez de lanzar excepción).
- Canary: `OfflineCanaryModelConfig(encoder, decoder, srcLang="es", tgtLang="es", usePnc=true)`,
  `modelType` el que diga el código de 1.13.8. Parakeet: `OfflineTransducerModelConfig` + `modelType="nemo_transducer"`.
  `numThreads = 2`. Firmas exactas en `docs/investigacion/03-asr-on-device.md` §3 y snippet §8.
- VAD Silero: `threshold 0.5`, `minSilenceDuration 0.5`, `minSpeechDuration 0.25`, `windowSize 512`;
  `maxSpeechDuration` no es tope duro → trocear a 25 s en el wrapper; `reset()` en silencio cada hora.
  No bajar `minSpeechDuration`: deja fuera «Sí.»/«No.» sueltos, pero si pasan Parakeet escribe «Yeah.» y
  Canary basura (08 §5).
- **Autoprueba** tras cargar: transcribir `assets/selftest_es.wav` («Hola, esto es una prueba de
  transcripción de voz.», TTS Helena, 16 kHz mono, **sin «ñ»** para que Canary no enseñe «espa ol»; cómo
  regenerarla en 08 §8); texto vacío → `TranscriberState.Error("El modelo no devuelve texto en este dispositivo…")`.
- **«ñ» partida** («espa ol», «ma ana»): solo con Canary, `cleanText(fixEnye = true)` las une con una lista
  cerrada de palabras. Es sobre todo un artefacto de las voces TTS; con voz humana Canary acierta la «ñ» y
  Parakeet no parte ninguna (08 §3).
- Descarga de modelos con `DownloadManager` a `getExternalFilesDir("models-tmp")`, verificación de
  tamaño y SHA-256 (los de `03` §4.2), y movido a `filesDir/models/<id>/`. Opción "solo Wi-Fi" por
  defecto. URLs de Hugging Face fijadas a commit (03 §4.2).
- Catálogo v1: `canary-180m-es` (ligero, valor por defecto de `AppSettings`) y `parakeet-v3`
  (`recommendedMinRamGb = PARAKEET_MIN_RAM_GB = 6`). El onboarding y Ajustes › Transcripción marcan
  «Recomendado» el de `recommendedModelId(deviceRamGb())` (salvo que no llegue a su RAM mínima); el onboarding
  lo preselecciona si el guardado no está instalado ni bajándose, y guarda `asrModelId` al elegir y al
  descargar (la escucha carga el de `asrModelId`). Licencia CC-BY-4.0: atribución en "Acerca de".

### 5.5 Importar audio (B2)
SAF (`OpenDocument`, `audio/*`) → `ImportWorker` (`CoroutineWorker` con `setForeground` tipo
`dataSync`) → `MediaExtractor` + `MediaCodec` a PCM16 → mono → remuestreo lineal a 16 kHz → VAD
propio (otra instancia de `VadSegmenter`) → `transcriber.acquire()` → segmentos → conversación
`source=IMPORT` (`startedAt` = fecha de modificación del fichero − duración, o ahora − duración) →
`READY` → `ai.onConversationReady`. Sirve también para probar la app en el emulador.

**Llamadas** (`CallImporter`). Android no deja a ninguna app de la tienda captar el audio de una llamada
(`VOICE_CALL` exige `CAPTURE_AUDIO_OUTPUT`, de sistema; durante la llamada el micro de las demás llega en
silencio y la escucha solo apunta el hueco `MIC_BUSY`). La grabadora del teléfono sí puede (Samsung:
`Recordings/Call`, `.m4a`). En Ajustes › Transcripción el dueño elige esa carpeta (`OpenDocumentTree`,
permiso persistente de leer y borrar, `AppSettings.callFolder`) y cada grabación pasa por la misma
`ImportWorker` como conversación `source=CALL`. Qué dispara el escaneo: un trabajo con disparador de MediaStore
(`addContentUriTrigger` sobre `MediaStore.Audio`, que se vuelve a armar al acabar), `MaintenanceWorker` cada
30 min y elegir la carpeta. Solo entran los ficheros de audio quietos desde hace 60 s (una llamada en curso aún
crece; si hay alguno sin asentar se vuelve a mirar al minuto), no encolados ya (documentId en las preferencias
`calls`, guardado cuando WorkManager ya tiene el trabajo) y sin conversación importada (CALL o a mano) que termine en
su fecha de modificación (así no se repite al volver a elegir la carpeta, ni aunque se guardara solo en parte;
ImportWorker lo comprueba otra vez antes de decodificar). Sin modelo instalado no se encola nada, y si el modelo
no carga, la llamada vuelve a quedar pendiente.
Cada elección de carpeta tiene su token: lo encolado con otro (tras «Dejar de importar» o al elegirla de nuevo)
acaba sin leerse ni borrarse. El borrado se decide al terminar, con `callDeleteAfter` (por defecto) y el token
vigentes: solo si la conversación ya está guardada entera (lo decodificado dura lo que dice la cabecera, ±2 s) y
el fichero no ha cambiado desde que se leyó; si falla o el final estaba dañado, se queda. Como cualquier importación,
no se guarda si cae dentro de un «Olvidar los últimos N min» hecho mientras se transcribía.
Al elegir una carpeta con grabaciones sin transcribir se pregunta si entran también o solo las nuevas (un corte
por fecha de modificación, que no se pierde aunque una salga y vuelva de la papelera). «Borrar todo» empieza
soltando el permiso, parando el vigilante y olvidando la carpeta. Las llamadas de WhatsApp y otras VoIP no las graba el
teléfono, así que quedan fuera.

---

## 6. Recordatorios, mantenimiento y arranque (B4)

- `ReminderScheduler`: `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, remindAt, pi)`; permisos
  `USE_EXACT_ALARM` (API 33+) y `SCHEDULE_EXACT_ALARM` con `maxSdkVersion=32`. Si
  `canScheduleExactAlarms()` es falso, `setWindow` de 10 min. `requestCode = taskId`.
- `ReminderReceiver`: notificación en el canal `reminders` (importancia alta) con el texto de la tarea
  (es lo único que se muestra; no hay texto de conversación), acciones *Hecho* y *Posponer 1 h*; tocar
  abre la tarea. Marca `notifiedAt`.
- `SystemEventsReceiver`: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` →
  `rescheduleAll()` (las vencidas en las últimas 24 h se notifican ya, marcadas "pasado") y, si
  `listeningEnabled`, `ResumeNotifier.show()` (no se puede abrir el micro desde el arranque, 02 §3).
- `MaintenanceWorker` periódico cada 30 min (`KEEP`): si `listeningEnabled` y el servicio no corre →
  `ResumeNotifier`; `ai.processPending()` si hay red; `ai.buildMissingDigests()`;
  `data.applyRetention()` una vez al día; limpiar ficheros huérfanos de `pending/`.
- `ResumeNotifier` (B1): notificación en el canal `alerts` "La escucha se ha detenido. Toca para
  reanudar" con `PendingIntent.getForegroundService(START)` (la interacción con la notificación da el
  permiso "mientras se usa"); acción secundaria abre la app.

---

## 7. IA (B3)

### 7.1 Clientes
```kotlin
data class LlmMessage(val role: String /* user|assistant */, val text: String)
data class LlmRequest(val system: String, val messages: List<LlmMessage>, val model: String,
  val maxTokens: Int, val schema: JsonObject? = null, val effort: String? = null, val cacheSystem: Boolean = true)
data class LlmResponse(val text: String, val inputTokens: Int, val outputTokens: Int, val cachedTokens: Int)
interface LlmClient { suspend fun complete(req: LlmRequest): LlmResponse } // lanza LlmException(kind: AUTH|RATE|NETWORK|SERVER|BAD_OUTPUT|REFUSAL, msg)
```
- **Anthropic** (`POST {baseUrl}/v1/messages`): cabeceras `x-api-key`, `anthropic-version: 2023-06-01`,
  `content-type: application/json`. Cuerpo: `model`, `max_tokens`, `system: [{type:"text", text, cache_control:{type:"ephemeral"}}]`,
  `messages`, `output_config: {effort, format: {type:"json_schema", schema}}` (format solo si hay esquema).
  Sin `temperature`/`top_p`/prefill/`thinking:disabled`. Para modelos `claude-opus-5-5`/`claude-sonnet-5-5`:
  `fallbacks: "default"` + cabecera `anthropic-beta: server-side-fallback-2026-07-01` (si la API
  devuelve 400 por ese campo, reintentar una vez sin él y recordarlo en memoria). Respuesta: primer
  bloque `content[].type=="text"`; `stop_reason`: `refusal` → `REFUSAL`; `max_tokens` → reintentar una
  vez con el doble. Uso: `usage.input_tokens/output_tokens/cache_read_input_tokens`.
- **OpenAI-compatible** (`POST {baseUrl}/chat/completions`, `Authorization: Bearer`): `system` como
  primer mensaje; `response_format` `{type:"json_schema", json_schema:{name, schema, strict:true}}` si
  `jsonMode=="schema"`, o `{type:"json_object"}` si `"object"` (el prompt ya incluye la palabra JSON y
  el esquema descrito). DeepSeek: `"thinking": {"type":"disabled"}`.
- Timeouts OkHttp: conexión 15 s, lectura 120 s. Reintentos: 2 con espera exponencial en `RATE`,
  `NETWORK`, `SERVER` (respetar `retry-after`).
- Nunca registrar cuerpos de petición ni de respuesta. Sentry solo recibe el tipo de error.

### 7.2 Presets (`AiPresets`, lo escribe A)
| id | Nombre | provider | baseUrl | Segundo plano | Respuestas | jsonMode | Aviso |
|---|---|---|---|---|---|---|---|
| `anthropic` | Claude (Anthropic) | ANTHROPIC | `https://api.anthropic.com` | `claude-haiku-5-5` | `claude-opus-5-5` | schema | EE. UU.; no entrena con tus datos; retención ≤ 30 días |
| `mistral` | Mistral (UE) | OPENAI_COMPAT | `https://api.mistral.ai/v1` | `mistral-small-latest` | `mistral-medium-latest` | schema | Empresa de la UE; revisa la opción de no entrenar |
| `deepseek` | DeepSeek | OPENAI_COMPAT | `https://api.deepseek.com` | `deepseek-flash` | `deepseek-flash` | object | **Datos en China, puede entrenar con ellos; desaconsejado con conversaciones de terceros (RGPD)** |
| `custom` | Personalizado (compatible con OpenAI) | OPENAI_COMPAT | (editable) | (editable) | (editable) | object | — |

### 7.3 Procesado de conversación (`ConversationProcessor`)
- Entrada al modelo (mensaje de usuario, variable): fecha y hora de inicio con día de la semana
  (`jueves 15 de octubre de 2026, 18:42`), zona, duración, tareas ya registradas de esa conversación
  (para no duplicar) y la transcripción `[HH:mm:ss] texto` por segmento. Si la transcripción supera
  ~60 000 caracteres, se trocea por segmentos y se procesa por partes, fusionando resultados.
- `system` **constante y versionado** (sin fechas ni IDs; ≥ 512 tokens para que cachee): rol, reglas,
  descripción del esquema, criterios de tareas/facts/ruido y 1–2 ejemplos breves.
- Esquema de salida (`additionalProperties:false`, todo `required`, nulos con `["string","null"]`):
```json
{ "title": "string (≤ 60 car.)", "summary": "string (2–4 frases)", "keyPoints": ["string"],
  "isNoise": false,
  "tasks": [{ "text": "string", "explicitReminder": true, "dueAt": "2026-10-16T10:00 | null",
              "remindAt": "2026-10-16T09:45 | null", "quote": "string" }],
  "facts": [{ "text": "string" }] }
```
- Reglas: tareas solo con acción concreta; `explicitReminder=true` solo si alguien pidió que se le
  recuerde algo ("recuérdame", "avísame", "no me dejes olvidar") → `state=OPEN` + alarma; el resto,
  `SUGGESTED` sin alarma. Fechas relativas resueltas respecto al inicio de la conversación, en hora
  local `yyyy-MM-ddTHH:mm`; si se da fecha sin hora, recordatorio a las 09:00. Máximo 3 facts por
  conversación, duraderos y útiles (personas, fechas, datos, preferencias), nunca de salud, ideología,
  religión, vida sexual ni emociones inferidas (06), siempre `SUGGESTED`. Si es ruido de TV/radio o
  no hay contenido útil → `isNoise=true`, sin tareas ni facts.
- Validación: parsear con kotlinx.serialization (`ignoreUnknownKeys`); fechas inválidas → null;
  textos recortados. Un fallo de formato reintenta una vez; luego `FAILED` con `lastError`.
- Estados: `READY → PROCESSING → DONE | FAILED`. `FAILED` se reintenta en `processPending()` hasta 3
  veces (`processAttempts`).
- Apagar la IA para los envíos siguientes (los ajustes se leen justo antes de cada llamada), no el que ya está en
  camino: su respuesta se guarda con sus tareas y alarmas, igual que la de Preguntar. El texto ya ha salido y la
  llamada está pagada; descartarla dejaría la conversación en `READY` y se volvería a enviar al reactivar la IA. Lo
  que sí se descarta es lo que llega después de olvidar o borrar (lo comprueba la transacción que lo guarda).

### 7.4 Recordatorio rápido (`QuickReminders`)
Si `quickReminders` y el segmento coincide con
`\b(recu[eé]rd(a|ame|amelo|ale)|avís(ame|ale)|no (me|te|se) (olvides|olvide|dejes olvidar)|ap[uú]nta(me|lo))\b`
(sin distinguir mayúsculas): se manda al modelo de segundo plano lo dicho en los últimos 3 min de esa
conversación con un esquema `{ "tasks": [...] }` (solo `explicitReminder`), se insertan como `OPEN` y se
programan. El procesado completo posterior recibe esas tareas como "ya registradas".

### 7.5 Preguntar (`AskEngine`)
1. **Planificador** (modelo de segundo plano, effort `low`, esquema): entrada = ahora (fecha, día,
   hora), últimas 4 intervenciones del chat y la pregunta → `{"keywords":[...], "from":"yyyy-MM-dd|null", "to":"yyyy-MM-dd|null"}`
   (keywords con sinónimos y variantes; rango null = últimos 30 días).
2. **Recuperación** (BD local): conversaciones del rango (título + resumen + hora, máx. 60, más
   recientes primero; si se pasa, las que casan por FTS), segmentos por FTS5 `OR` de keywords en el
   rango ordenados por `bm25` (máx. 40, con el segmento anterior y posterior), resúmenes diarios del
   rango (máx. 14), tareas abiertas (máx. 30) y facts confirmados (máx. 100).
3. **Respuesta** (modelo de respuestas, effort `medium`, texto libre): `system` constante (responder
   solo con el contexto; si no está, decirlo; español; breve; citar con `[c:ID]` tras cada afirmación;
   fechas relativas respecto a "ahora") + historial reciente + mensaje con el contexto y la pregunta.
4. Se guardan pregunta y respuesta en `chat_message`, con `citations` = IDs citados que existan, más las
   conversaciones de los resúmenes diarios del contexto (no llevan `[c:ID]`: lo que salga de ellos no se puede
   citar) y las de los fragmentos enviados (van literales y el modelo puede copiarlos sin citarlos o con la cita de
   otra). Tareas y datos llevan el `[c:ID]` de su conversación. Borrar u olvidar una conversación borra las
   respuestas cuyo `citations` la incluye y su pregunta; no todas las de su contexto, que con hasta 60 conversaciones
   del rango (y los 100 datos confirmados, que van siempre) vaciaría el chat casi siempre. Una sola gramática de
   citas (`Citations`) para guardar y para la pantalla: también `[c:12, 13]` o `[c: 12]`, que se guardan como
   `[c:12][c:13]`.
5. Si falla, la pregunta queda guardada para "Reintentar"; al cerrar el aviso o hacer otra pregunta, sale del chat.
   Si se olvida o borra algo mientras llega, la respuesta se descarta y el aviso lo dice.
Sin IA configurada: la pantalla lo explica y ofrece la búsqueda local.

### 7.6 Resumen diario (`DigestBuilder`)
Para cada día anterior a hoy con conversaciones `DONE` no ruido y sin `daily_digest`: entrada =
títulos + resúmenes + tareas del día → `{"summary": "...", "highlights": ["..."]}`. Máximo 7 días por
pasada (los más recientes).

### 7.7 Coste orientativo (05)
3 h de voz/día ≈ 30 000 palabras ≈ 50 000 tokens: Haiku 5.5 en segundo plano ≈ 1 $/mes; respuestas
con Opus 5.5 ≈ 0,5–1,5 céntimos cada una. Mostrar en Ajustes una línea con esta estimación.
Con `claude-opus-5-5` también en segundo plano el coste sube a ~35–45 $/mes: el ajuste existe, pero
avisando.

---

## 8. Interfaz (B5a / B5b)

Material 3, Compose, tema propio (sin color dinámico) con modo claro y oscuro; **A** define el tema:

| Rol | Claro | Oscuro |
|---|---|---|
| background | `#F7F5F0` | `#111315` |
| surface / surfaceContainer | `#FFFFFF` / `#EFEBE4` | `#191C1F` / `#22262A` |
| primary (tinta) | `#2E4A7A` | `#A9C1F0` |
| secondary (ámbar, recordatorios) | `#965A12` | `#F0B366` |
| tertiary (salvia, "escuchando") | `#36725F` | `#86C9AF` |
| error | `#B3261E` | `#F2B8B5` |

Navegación inferior: **Hoy · Preguntar · Tareas · Memoria**. En la barra superior de Hoy: buscar y
ajustes. Rutas Nav3 (A las declara con pantallas placeholder): `Onboarding`, `Today(day: String?)`,
`ConversationDetail(id, segmentId?)`, `Search`, `Ask`, `Tasks`, `Memory`, `Settings`,
`SettingsAi`, `SettingsModels`, `SettingsPrivacy`, `About`.

- **Onboarding** (B5a): 1) qué hace y qué no; 2) **aviso legal y de privacidad** (textos de
  `06` §7 resumidos: grabar conversaciones en las que participas es lícito, las ajenas no; avisar a
  quien grabes; no difundir; la app no guarda audio) con casilla y fecha de aceptación; 3) permisos de
  uno en uno con explicación previa (micrófono, notificaciones); 4) descargar modelo (recomendación
  según RAM, tamaño, Wi-Fi); 5) IA opcional ("puedes configurarla después"); 6) empezar a escuchar.
- **Hoy** (B5a): tarjeta de estado (escuchando con medidor de nivel / en pausa hasta / micrófono
  ocupado / detenida / falta modelo), botones Escuchar-Detener, Pausar 15 min, Pausar 1 h, "Olvidar…"
  (5/15/60 min con confirmación); selector de día (hoy y anteriores); resumen del día si existe;
  línea de tiempo de conversaciones (hora, título o "Transcribiendo…"/"Sin procesar", 2 líneas de
  resumen, chips de tareas, ruido atenuado) intercalando los **huecos** (pausa, micro ocupado,
  detenida, app cerrada por el sistema) con su motivo; contador de pendientes de transcribir.
- **Conversación** (B5a): título, franja horaria, duración, resumen, puntos clave, tareas y facts
  derivados (aceptar/descartar), transcripción por segmentos con hora (scroll al segmento citado);
  acciones: reprocesar, copiar transcripción, borrar (confirmación).
- **Buscar** (B5a): campo con búsqueda al escribir (debounce 300 ms), resultados con fragmento
  resaltado («») y fecha, agrupados por día; tocar abre la conversación en el segmento.
- **Preguntar** (B5b): chat; sugerencias iniciales ("¿Qué tengo pendiente?", "¿Qué hablé ayer?",
  "¿Qué dije del presupuesto?"); respuesta con chips de cita `HH:mm · título` que abren la
  conversación; estado "pensando"; errores claros (sin clave, sin red, cuota); borrar chat. Aviso fijo
  pequeño: "Se envía a {proveedor} un extracto de tus transcripciones".
- **Tareas** (B5b): pestañas Pendientes · Sugeridas · Hechas; recordatorio con fecha/hora editable
  (date/time pickers de M3); aceptar sugerida, marcar hecha, borrar; botón "+" para crear un
  recordatorio a mano; enlace a la conversación de origen.
- **Memoria** (B5b): facts confirmados y sugeridos (confirmar, editar, borrar), añadir a mano.
- **Ajustes** (B5b): Escucha (fuente de audio, minutos de silencio que cierran conversación,
  ahorro de batería, recordatorio rápido); Transcripción (`SettingsModels`: catálogo, estado,
  descargar/cancelar/borrar, probar, elegir; importar audio); IA (`SettingsAi`: preset, URL, modelos,
  clave —campo de contraseña, nunca se muestra entera—, **aviso de envío con aceptación**, probar
  conexión, coste estimado); Privacidad (`SettingsPrivacy`: retención, ocultar en capturas,
  exportar JSON con aviso, borrar todo con doble confirmación); Batería (abrir excepción de
  optimización, guía por fabricante con enlace a dontkillmyapp.com/<marca>); Acerca de (versión,
  licencias: sherpa-onnx Apache-2.0, Silero MIT, Canary/Parakeet CC-BY-4.0 de NVIDIA).

Accesibilidad: `contentDescription` en iconos, objetivos ≥ 48 dp, contraste AA, textos escalables.
`FLAG_SECURE` en `MainActivity` según ajuste.

---

## 9. Manifest y build (A)

Permisos: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
`FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `USE_EXACT_ALARM`,
`SCHEDULE_EXACT_ALARM` (`maxSdkVersion=32`), `INTERNET`, `ACCESS_NETWORK_STATE`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `WAKE_LOCK`.
Componentes: `ListeningService` (`microphone`), `ReminderReceiver`, `SystemEventsReceiver`,
`androidx.work.impl.foreground.SystemForegroundService` con `foregroundServiceType="dataSync"`
(`tools:node="merge"`). `allowBackup=false` + `dataExtractionRules` que excluyen todo.
`networkSecurityConfig`: en `release` sin texto plano; en `debug` (`src/debug/res/xml/`) se permite
`10.0.2.2` y `localhost` para el servidor falso de pruebas.

Build: `abiFilters += listOf("arm64-v8a", "x86_64")`; release con R8
(`proguard-android-optimize.txt`) y `-keep class com.k2fsa.sherpa.onnx.** { *; }`; firma de
release = clave debug (uso personal; ver AGENTS.md). Repositorio JitPack en `settings.gradle.kts`
limitado a `com.github.k2-fsa*`. `BuildConfig.SENTRY_DSN` desde la propiedad Gradle `mysone.sentryDsn`
o la variable `SENTRY_DSN` (vacío = Sentry apagado). Sin plugin de Sentry.

Compilar (Git Bash):
```bash
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk" ./gradlew assembleDebug testDebugUnitTest
```

---

## 10. Privacidad (resumen de salvaguardas v1, de 06)
Aviso inicial aceptado con fecha · notificación siempre visible · pausa en un toque y "olvidar los
últimos N min" · sin audio guardado · transcripción local · IA desactivada por defecto y con aviso de
qué se envía y a quién · DeepSeek marcado como desaconsejado · sin contenido en logs, Sentry ni
notificaciones (salvo el texto de la propia tarea en el recordatorio) · retención de la transcripción
literal (90 días por defecto) · borrado en cascada y borrado total · exportación con aviso ·
`allowBackup=false` · `FLAG_SECURE` por defecto · nada de facts sensibles (salud, ideología, etc.).

---

## 11. Pruebas
- **Unitarias JVM** (cada módulo las suyas, sin Android): `ConversationTracker`, sanitizado FTS,
  parseo/validación de salidas de IA y fechas, construcción de peticiones (forma JSON exacta, con
  `mockwebserver3`), parser de citas `[c:ID]`, remuestreo, troceo de VAD a 25 s, regex de
  recordatorio rápido, cálculo de alarmas.
- **Instrumentadas** (emulador): DAOs y FTS5 con `BundledSQLiteDriver` en memoria.
- **E2E en emulador** (`Pixel_7_Pro_API_34` y `10.1_WXGA_Tablet_API_36`): onboarding, descarga de
  Canary, importar `conv1.wav`/`conv2.wav` (TTS en español), transcripción correcta, procesado con el
  servidor falso (`node tools/mock-llm.mjs`, puerto 8787, en el emulador `http://10.0.2.2:8787`, clave
  `test-key`), tarea + recordatorio que suena, pregunta con citas, búsqueda, pausa/reanudar, matar el
  proceso (`adb shell am kill` / `kill -9`) y reinicio del emulador → "Toca para reanudar".

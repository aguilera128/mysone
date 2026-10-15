# 07 — Crítica del stack (abogado del diablo)

Fecha: 15-oct-2026. Alcance: app Android instalable por sideload, uso personal, que escucha todo el día,
transcribe en el dispositivo (español de España), guarda transcripciones con fecha y hora, genera
notas, tareas y recordatorios con un LLM y permite preguntar sobre lo escuchado.

Stack propuesto que se critica:
Kotlin + Jetpack Compose · foreground service (FGS) de tipo `microphone` con `AudioRecord` a 16 kHz ·
Silero VAD + ASR offline con sherpa-onnx dentro del servicio (sin JS) · Room (SQLite) con FTS4 ·
WorkManager para el LLM · AlarmManager y notificaciones para recordatorios · OkHttp · API key cifrada con Keystore.

Método: documentación oficial (developer.android.com, source.android.com, docs.expo.dev, pub.dev,
capacitorjs.com), código fuente (React Native, sherpa-onnx, flutter_foreground_task, androidx),
registros de paquetes (npm, pub.dev, Maven, Google Maven, JitPack) e incidencias de GitHub. Lo que
no se ha podido verificar está marcado **[no verificado]** y se repite en el §6.

---

## 0. Veredicto

**Mantener Kotlin + Compose nativo.** El stack propuesto es el correcto para este caso de uso.
Le haría siete cambios de diseño (§4). Los más importantes:

1. **El LLM urgente no va en WorkManager.** Los recordatorios que salen de lo escuchado se procesan
   dentro del propio FGS. Un proceso con FGS tiene red sin restricciones, mientras que los jobs se
   aplazan en Doze y, desde Android 16, cuentan contra la cuota aunque haya un FGS activo.
   WorkManager queda para lo diferible: resúmenes diarios y reprocesados.
2. **Cola persistente en disco entre VAD y ASR**, en vez de transcribir en línea. Cada segmento de
   voz se guarda en Opus y queda en una fila `PENDING` de la base de datos. Si el proceso muere, no
   se pierde nada, y se puede retranscribir con un modelo mejor más adelante.
3. **Room 3.0.3 + `BundledSQLiteDriver` + `@Fts5`** en vez de FTS4 sobre el SQLite del sistema.
   Esto da ranking bm25, la misma versión de SQLite en todos los dispositivos, y FTS5 está
   verificado en las flags de compilación. Alternativa conservadora: Room 2.8.5 + `@Fts4`.
4. **Ningún reinicio automático de la escucha.** Tras arrancar el móvil, tras una actualización o
   si el proceso muere, se publica una notificación «Toca para reanudar». Tocar una notificación es
   una de las excepciones documentadas; arrancar desde `BOOT_COMPLETED` está prohibido para el tipo
   `microphone`.
5. **Modelo ASR elegido según la RAM del dispositivo, más una autoprueba al arrancar.** Hay dos
   motivos:
   - El Memory Limiter de Android 17 trata un FGS como proceso «no visible» (límite de 1 GiB en un
     móvil de 4 GB).
   - sherpa-onnx carga el modelo entero en RAM, sin mmap.

   Además, ya hay un bug documentado de ONNX Runtime que devuelve texto vacío sin dar error en
   Snapdragon 8 Elite Gen 5.

**¿Por qué no Expo/React Native, aunque el desarrollador sepa TypeScript?** La ruta crítica tiene
que estar en Kotlin igualmente: el servicio, `AudioRecord`, VAD, ASR, la base de datos y las alarmas.
En Android, cuando la Activity se pausa, los timers de JS se paran salvo que haya una tarea Headless
JS activa (verificado en el código fuente). Lo único que aportaría RN es la UI en TS. A cambio
añade:
- un puente que mantener;
- una segunda cadena de build;
- el riesgo de corrupción por tener dos copias de SQLite en el mismo proceso (si se usa
  expo-sqlite sobre el mismo fichero);
- la regla de SDK 57 de que `expo prebuild` borra `android/` por defecto.

Con agentes de IA escribiendo el código, saber o no Kotlin pesa menos que el número de piezas que
pueden fallar. Además, Compose tiene un modelo mental muy parecido al de React
(`remember` ≈ `useState`, `LaunchedEffect` ≈ `useEffect`, estado elevado ≈ props).

**Flutter** es viable técnicamente (sherpa_onnx 1.13.8 oficial, `record` con stream PCM,
flutter_foreground_task con su propio FlutterEngine). Aun así, obliga a aprender Dart y a coordinar
dos motores y dos isolates con la base de datos. No mejora la ruta crítica.

**Capacitor** queda descartado: su Background Runner no permite servicios persistentes ni da acceso
al micrófono.

**KMP** no aporta nada hoy: iOS no se puede compilar en Windows y, además, iOS no permite empezar a
grabar en segundo plano.

**Versión perezosa** (la que haría): un solo módulo Gradle, sin Hilt ni capas de «Clean
Architecture», sin KMP y sin embeddings en la v1 (FTS5 + reescritura de la consulta por el LLM +
resúmenes diarios). Embeddings, diarización y un segundo pase de ASR, solo si el uso lo pide.

---

## 1. Hechos de plataforma que condicionan cualquier stack

Son reglas del sistema operativo, así que afectan igual a Kotlin, RN, Flutter y Capacitor. Por eso
el framework de UI pesa poco y lo que pesa es dónde corre el bucle de audio.

| # | Hecho verificado | Fuente |
|---|---|---|
| P1 | Si la app apunta a Android 14 o superior, un FGS de tipo `camera`, `location` o `microphone` creado con la app en segundo plano lanza `SecurityException`. Hay que llamar a `startForegroundService()` con una Activity visible, salvo excepción. | [fgs/restrictions-bg-start](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) |
| P2 | Excepciones del «while-in-use» relevantes aquí: lo arranca un componente del sistema, la interacción con un **widget**, la interacción con una **notificación**, un `PendingIntent` enviado desde otra app visible. «El usuario desactiva la optimización de batería» es una excepción del arranque en segundo plano, **no** del while-in-use. | ídem |
| P3 | Los receptores de `BOOT_COMPLETED` no pueden lanzar FGS `microphone` desde Android 14, ni `dataSync`, `camera`, `mediaPlayback`, `phoneCall` ni `mediaProjection` desde Android 15. Si lo intentan: `ForegroundServiceStartNotAllowedException`. | [behavior-changes-15](https://developer.android.com/about/versions/15/behavior-changes-15) |
| P4 | Android 15: los FGS `dataSync` y `mediaProcessing` tienen un tope de 6 h cada 24 h (`onTimeout`). El tipo `microphone` no tiene ese tope: **no mover el ASR a un FGS `dataSync` aparte**. | ídem |
| P5 | Android 16: la cuota de ejecución de jobs (JobScheduler/WorkManager) se aplica también a los jobs que corren a la vez que un FGS, y a los que empezaron con la app visible. | [16/behavior-changes-all](https://developer.android.com/about/versions/16/behavior-changes-all) |
| P6 | Doze suspende la red, ignora los wakelocks, no ejecuta JobScheduler/WorkManager y aplaza las alarmas normales. En la tabla de estados de la app, «App process is running a foreground service» aparece con red **«No restrictions»**, pero los jobs y las alarmas siguen limitados por el bucket. | [doze-standby](https://developer.android.com/training/monitoring-device-state/doze-standby), [power-details](https://developer.android.com/topic/performance/power/power-details) |
| P7 | Captura de audio concurrente (Android 10+): dos apps normales nunca capturan a la vez. Gana la que tiene UI visible, las fuentes sensibles a privacidad (`CAMCORDER`, `VOICE_COMMUNICATION`) tienen prioridad, y la otra app **recibe silencio**. Durante una llamada «the call always receives audio» y una app normal no puede capturarla. El silenciamiento se detecta con `registerAudioRecordingCallback` + `isClientSilenced()`. | [sharing-audio-input](https://developer.android.com/media/platform/sharing-audio-input) |
| P8 | Android 17 (estable en Pixel desde el 16-jun-2026 según la prensa [no verificado en fuente oficial]): el **Memory Limiter** pone límites por proceso según la RAM total. Un FGS cuenta como «not visible». Límites de memoria no visible: 1 GiB (móviles de 4 GB), 2 GiB (6 GB), 3 GiB (8 GB), 4 GiB (12 GB), 5 GiB (16 GB). Al superarlo, el kernel recupera memoria y hace swap con «temporary execution throttling». Solo mata en casos extremos. | [source.android.com memory-limiter](https://source.android.com/docs/core/perf/memory-limiter), [17/behavior-changes-all](https://developer.android.com/about/versions/17/behavior-changes-all) |
| P9 | Android 17, endurecimiento del audio en segundo plano: la reproducción, el foco de audio y el volumen en segundo plano exigen un FGS. Si la app apunta a 37, el FGS debe tener capacidades while-in-use (o la app tener el permiso de alarma exacta y usar `USAGE_ALARM`). Los fallos son **silenciosos**. Afecta a sonidos de recordatorio que reproduzca la propia app, no a la grabación. | [17/changes/bg-audio](https://developer.android.com/about/versions/17/changes/bg-audio) |
| P10 | Android 17 si la app apunta a 37: los `.so` cargados con `System.load()` deben ser de solo lectura; hay un permiso nuevo `ACCESS_LOCAL_NETWORK` (relevante si el LLM corre en la red local, p. ej. en el NAS); Certificate Transparency activado por defecto. | [17/behavior-changes-17](https://developer.android.com/about/versions/17/behavior-changes-17) |
| P11 | Alarmas exactas: `SCHEDULE_EXACT_ALARM` no viene concedido en instalaciones nuevas que apuntan a 33 o más y el usuario puede revocarlo. `USE_EXACT_ALARM` se concede automáticamente y no se puede revocar (está sujeto a la política de Play, irrelevante en sideload). Todas las alarmas se cancelan al apagar el móvil y hay que reprogramarlas en `BOOT_COMPLETED`. `setAlarmClock()` saca al sistema de Doze. | [alarms/schedule](https://developer.android.com/develop/background-work/services/alarms/schedule) |
| P12 | Verificación de desarrolladores: aplicación obligatoria desde el 30-sep-2026 en regiones seleccionadas, global «en 2027». **Las instalaciones por ADB están exentas.** Para usuarios avanzados hay un flujo con 24 h de espera, y existe una cuenta gratuita «limited distribution» (20 dispositivos, sin DNI). | [developer-verification FAQ](https://developer.android.com/developer-verification/guides/faq) |
| P13 | Opus: codificador desde Android 10 (decodificador desde 5.0). AAC-LC: codificador en todas las versiones. | [supported-formats](https://developer.android.com/media/platform/supported-formats) |
| P14 | `androidx.security:security-crypto` (EncryptedSharedPreferences): «Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore» (1.1.0-alpha07, abr-2025). | [releases/security](https://developer.android.com/jetpack/androidx/releases/security) |

**Consecuencia directa:** «grabar todo» es imposible durante las llamadas (P7), y también cuando
otra app visible usa el micrófono: una nota de voz de WhatsApp, una vídeollamada o la cámara. La
app tiene que **registrar esos huecos**, no esconderlos.

---

## 2. Alternativas evaluadas con rigor

### 2.1 Expo / React Native con módulo nativo Kotlin

Hechos verificados:

- **Timers JS en segundo plano (Android): se paran.** En
  [`JavaTimerManager.kt`](https://github.com/facebook/react-native/blob/main/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/modules/core/JavaTimerManager.kt),
  `onHostPause` hace `isPaused.set(true)` y quita el frame callback del Choreographer si
  `!headlessJsTaskContext.hasActiveTasks()`. `doFrame` sale sin volver a programarse si
  `isPaused && !isRunningTasks`. Los timers pendientes no se pierden, pero no se disparan hasta
  `onHostResume` o hasta que empieza una tarea Headless JS. Las promesas y los eventos nativos
  siguen llegando. Cualquier `setTimeout`/`setInterval` (debounce, reintentos, backoff, librerías
  que lo usan por dentro) se congela con la pantalla apagada.
- **Headless JS** ([docs](https://reactnative.dev/docs/headless-js-android)): solo Android. Se lanza
  con `HeadlessJsTaskService` y `HeadlessJsTaskConfig(nombre, datos, timeoutMs, allowedInForeground)`.
  Por defecto **la app se cuelga si la tarea corre con la app en primer plano**. Está pensado para
  tareas cortas («JS will spin up, run your task, then spin down»), no para un bucle de audio de
  16 h. La documentación no menciona la New Architecture ni bridgeless **[no verificado]**.
- **`expo-background-task`** (SDK 57, [docs](https://docs.expo.dev/versions/latest/sdk/background-task/)):
  usa WorkManager por debajo. Intervalo mínimo de **15 min en Android**, 12 h por defecto,
  «may not run immediately». En iOS el sistema decide cuándo. No sirve para escuchar continuamente.
- **`expo-audio` 57.0.5** ([docs](https://docs.expo.dev/versions/latest/sdk/audio/)):
  - `enableBackgroundRecording` añade un FGS de grabación con `FOREGROUND_SERVICE_MICROPHONE`.
  - `useAudioStream`/`AudioStream` entrega PCM a JS (`onBuffer`, `int16`/`float32`).
  - La documentación **no dice si `AudioStream` sigue funcionando en segundo plano**.
  - Aunque funcione, el PCM tendría que cruzar a JS (32 KB/s) y volver a nativo para el ASR.
- **`expo-sqlite` 57.0.4** ([docs](https://docs.expo.dev/versions/latest/sdk/sqlite/)): compila su
  propio SQLite (`customBuildFlags`, `useSQLCipher`, `useLibSQL`, FTS3/4/5 activados por defecto,
  `sqlite-vec` opcional).
  - Si el servicio Kotlin escribe con Room (SQLite del sistema o `sqlite-bundled` de androidx) y la
    UI lee con expo-sqlite **el mismo fichero en el mismo proceso**, hay dos copias de SQLite
    enlazadas. Es justo el caso del §2.3 de
    [sqlite.org/howtocorrupt](https://www.sqlite.org/howtocorrupt.html): cada copia tiene su propia
    lista global de ficheros abiertos, así que «a close() operation on one connection might
    unknowingly clear the locks on a different database connection, leading to database
    corruption». SQLite documenta al menos un producto comercial que salió con este bug.
  - Lo mismo pasaría con `@op-engineering/op-sqlite` 18.2.5, que también trae su propio SQLite.
- **whisper.rn 0.7.4** (27-ago-2026, MIT, [GitHub](https://github.com/mybigday/whisper.rn)):
  - `RealtimeTranscriber` con Silero VAD y soporte de Parakeet.
  - Necesita `@fugood/react-native-audio-pcm-stream`.
  - Avisa de que los resultados crecen sin límite en sesiones de horas o días (`maxResultsInMemory`).
  - No documenta el segundo plano.
  - El bucle sigue estando en JS, con el problema de los timers de arriba.
- **react-native-sherpa-onnx 0.4.4** (8-sep-2026, MIT, comunitario,
  [XDcobra](https://github.com/XDcobra/react-native-sherpa-onnx)): TurboModule que envuelve
  sherpa-onnx. Es un mantenedor individual y la versión es 0.x (0.3.0 rompió la API respecto a
  0.2.0).
- **Expo SDK 57** (30-jun-2026, [changelog](https://expo.dev/changelog/sdk-57)): RN 0.86.
  `expo prebuild` **ahora borra y regenera `android/` por defecto**, así que el servicio tiene que
  vivir en un módulo Expo local más un config plugin, nunca editado a mano en `android/`. La versión
  57.0.17 pasa a RN 0.86.3 y corrige una regresión de memoria de Hermes V1.

Arquitecturas posibles con Expo:

- **A. JS en el bucle** (audio → JS → ASR o lógica en JS): **descartada**. Los timers se congelan,
  Headless JS es para tareas cortas, el PCM cruza el puente y la lógica depende del ciclo de vida de
  la Activity.
- **B. Núcleo Kotlin (módulo Expo local) + UI en RN**: viable.
  - El módulo Kotlin es el único dueño de `AudioRecord`, VAD, ASR, la base de datos, las alarmas y
    las llamadas al LLM.
  - JS solo lee y manda órdenes mediante funciones del módulo; **nunca abre el mismo `.db` con
    expo-sqlite**.
  - El código Kotlin es *el mismo* que en la opción nativa. Se añaden un puente (cada consulta
    expuesta a mano), un config plugin para el manifiesto y una segunda cadena de build
    (Metro + Gradle).
  - Lo único que se gana es escribir la UI en TS.

Veredicto Expo: B vale la pena solo si la UI va a ser grande y cambiar mucho, o si iOS fuera un
objetivo real. Aquí ninguna de las dos cosas se cumple: la UI tiene 5 pantallas y no hay iOS.

### 2.2 Flutter

- `sherpa_onnx` **1.13.8** (11-sep-2026) en pub.dev: es oficial de k2-fsa y soporta Android, iOS,
  Linux, macOS, Web y Windows ([pub](https://pub.dev/packages/sherpa_onnx)). Las llamadas FFI de
  Dart son síncronas, así que el ASR necesita un isolate propio para no bloquear el de la tarea.
- `record` **7.1.2** (9-oct-2026): `startStream` con `pcm16bits` en Android
  ([pub](https://pub.dev/packages/record)). Lo que dice sobre el segundo plano está en un documento
  aparte, no en la página principal **[no verificado]**.
- `flutter_foreground_task` **11.0.3** (7-sep-2026; pide Flutter 3.44+, Dart 3.12+ y Kotlin 2.2.20+):
  - En Android crea **su propio `FlutterEngine`** dentro del servicio y ejecuta un callback de Dart
    ([`ForegroundTask.kt`](https://github.com/Dev-hwang/flutter_foreground_task/blob/master/android/src/main/kotlin/com/pravera/flutter_foreground_task/service/ForegroundTask.kt):
    `flutterEngine = FlutterEngine(context)` … `executeDartCallback`). Son dos motores y dos heaps
    de Dart.
  - La UI y la tarea solo se pasan tipos primitivos o JSON.
  - Soporta el tipo `microphone`. Su propia documentación recuerda los límites de `dataSync` y de
    `BOOT_COMPLETED`.
- Hay un precedente de que funciona: la app de Omi (wearable) está en Flutter (MIT), aunque
  transcribe en la nube con Deepgram ([repo](https://github.com/BasedHardware/omi)).

Pegas:
- Dart es nuevo para el desarrollador, igual que Kotlin.
- Hay que coordinar dos motores o isolates con la base de datos: un solo dueño, o un isolate de
  base de datos compartido.
- Depende de plugins que a veces dan por hecho que hay una Activity.
- Si algo falla en el servicio, se acaba depurando Kotlin de todas formas (el plugin está escrito
  en Kotlin).

Veredicto: técnicamente cerca de la opción nativa, pero sin ninguna ventaja en este caso.

### 2.3 Capacitor

- Background Runner ([docs](https://capacitorjs.com/docs/apis/background-runner)): intervalo
  mínimo de 15 min, «maximum of 10 minutes» por ejecución en Android, sin DOM ni micrófono.
  Literalmente: «It's not possible to run persistent, always running background services».
- La WebView no está hecha para trabajo continuo en segundo plano. Todo el núcleo tendría que ser
  un plugin nativo, y entonces Capacitor solo aporta una UI web. **Descartado.**

### 2.4 Kotlin Multiplatform / Compose Multiplatform

- Compose Multiplatform 1.12.0 (ago-2026); iOS estable desde la 1.8.0 (may-2025)
  ([JetBrains](https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/)).
  Room ya es KMP desde la 2.7 y Room 3 está orientado a KMP.
- Pero iOS exige macOS/Xcode para compilar (no hay en Windows) y no permite *empezar* a grabar en
  segundo plano. Hay respuestas de DTS de Apple y casos de rechazo en App Review por usar el modo
  `audio` para grabar ([Apple forums](https://developer.apple.com/forums/thread/120038)). Hoy KMP
  solo añadiría estructura que nadie usa (YAGNI).
- Si algún día hace falta iOS, el código Kotlin de dominio (base de datos, prompts, parseo) se puede
  pasar a `commonMain`. El audio y el ASR habría que reescribirlos en iOS de todas formas.

### 2.5 Matriz

| Criterio | Kotlin + Compose | Expo B (núcleo Kotlin) | Flutter | Capacitor | KMP |
|---|---|---|---|---|---|
| Ruta crítica en segundo plano | Nativa, sin puente | Nativa (Kotlin igualmente) | Dart en un engine propio del FGS + plugins | Plugin nativo obligatorio | Nativa |
| Copias de SQLite en el proceso | 1 | 1 si JS no abre el `.db`; **2 si usa expo-sqlite** | 1 (si solo un paquete trae SQLite) | 1–2 | 1 |
| Lenguaje que ya conoce | No | Sí (solo la UI) | No | Sí | No |
| ASR sherpa-onnx | API Kotlin oficial | API Kotlin oficial, en el módulo | Paquete Dart oficial | Plugin propio | Oficial (Android) |
| Piezas de build | Gradle | Gradle + Metro + config plugin + prebuild | Gradle + Flutter | Gradle + web | Gradle + KMP |
| iOS futuro | Reescritura | UI reutilizable, núcleo a reescribir | UI reutilizable, servicio a reescribir | Igual | Dominio reutilizable |
| Veredicto | **Elegido** | Plan B si se insiste en TS | Viable, sin ventaja | Descartado | Prematuro |

---

## 3. Modos de fallo del stack propuesto y cómo mitigarlos

| # | Modo de fallo | Por qué ocurre (fuente) | Mitigación |
|---|---|---|---|
| F1 | No vuelve a escuchar tras reiniciar el móvil | P3: `BOOT_COMPLETED` no puede lanzar un FGS `microphone` | En `BOOT_COMPLETED`, reprogramar las alarmas y publicar una notificación «Escucha detenida — toca para reanudar». Su `PendingIntent` arranca el FGS, y la interacción con una notificación es una excepción (P2). |
| F2 | No vuelve tras una actualización de la app, tras un LMK o tras un crash | P1: sin una Activity visible no se puede crear un FGS while-in-use. Que `START_STICKY` recupere el micrófono no está documentado **[no verificado]**: hay que suponer que falla | `MY_PACKAGE_REPLACED` y `onStartCommand` con intent nulo → capturar `ForegroundServiceStartNotAllowedException`/`SecurityException` → misma notificación. Widget de inicio y parada (excepción P2). Guardar un *heartbeat* (último frame de audio) y comprobarlo con un `PeriodicWorkRequest` de 15 min: si está viejo, se notifica. Leer `ApplicationExitInfo` para saber qué lo mató. |
| F3 | El fabricante mata el servicio | [dontkillmyapp](https://dontkillmyapp.com/): los peores son Huawei, Xiaomi, OnePlus y Samsung (5/5), seguidos de Meizu, Asus y Oppo (4/5). Pixel/AOSP: 0 | Onboarding con una lista y enlaces directos: batería «Sin restricciones», `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (aceptable en sideload personal; en Play estaría limitado), autoinicio en Xiaomi y «nunca en suspensión» en Samsung. Mostrar en la UI los **minutos escuchados frente a los huecos de hoy** para que un fallo se vea. |
| F4 | Huecos silenciosos (llamadas, notas de voz, cámara, otra grabadora) | P7 | `registerAudioRecordingCallback` antes de `startRecording()`. Cuando `isClientSilenced()` sea true, insertar una fila `gap(motivo)`. Fuente `VOICE_RECOGNITION`. **No** usar `setPrivacySensitive(true)`: quitaría el micrófono a las otras apps del usuario, que recibirían silencio. Detectar también un RMS ≈ 0 durante mucho tiempo. |
| F5 | Memoria del modelo ASR | sherpa-onnx `ReadFile` lee el `.onnx` **entero en un `std::vector<char>`**, sin mmap ([file-utils.cc](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/file-utils.cc)), así que es memoria anónima. Parakeet-TDT-0.6b-v3 int8 = encoder 622 MB + decoder 12 MB + joiner 6,1 MB. El Memory Limiter (P8) da al FGS 1 GiB en un móvil de 4 GB y 2 GiB en uno de 6 GB. Pico al cargar ≈ buffer + sesión, posiblemente ~2× el modelo **[estimación, no medido]** | Elegir el modelo por `ActivityManager.MemoryInfo.totalMem`: ≥8 GB → Parakeet v3 int8; 4–6 GB → Canary-180M-Flash (182 M parámetros; ~273 MB en una exportación int8 de terceros, falta comprobar el paquete de sherpa) o Whisper base/small. **Una sola instancia** del reconocedor por proceso. Medir con `dumpsys meminfo` y `am memory-limiter status` en Android 17. `largeHeap` no sirve: es memoria nativa. |
| F6 | Se pierde audio por bloqueo o backpressure | Si el hilo de captura se bloquea (GC, I/O, base de datos), `AudioRecord` desborda su buffer. En móviles modestos el ASR puede ir más lento que el tiempo real | Hilo de captura con `THREAD_PRIORITY_URGENT_AUDIO` que solo lee, pasa el VAD (Silero tarda <1 ms por chunk en un hilo, según su README) y escribe en un ring buffer. Codificación y escritura en un hilo aparte. **Un único hilo de ASR** a prioridad `BACKGROUND`, con `numThreads=2` en sherpa. Cola persistente en Room (`PENDING`) para que el atraso aguante muertes del proceso. |
| F7 | Calor y batería | El ASR continuo es CPU sostenida. Parakeet v3: RTF 0,028 en CPU de escritorio con 2 hilos (audio de 334 s); en móvil no hay cifras **[no verificado]** | El VAD filtra: solo se transcribe voz. `PowerManager.addThermalStatusListener` (pausar la cola en `SEVERE` o más) y `getThermalHeadroom`. Si la batería baja de X% y no está cargando, encolar sin transcribir y procesar al cargar. Medir con `dumpsys batterystats`. |
| F8 | Bug de ONNX Runtime según el SoC: texto vacío sin ningún error | [sherpa-onnx #3845](https://github.com/k2-fsa/sherpa-onnx/issues/3845) (abierto): ORT 1.27.0 calcula mal el encoder zipformer2 en Snapdragon 8 Elite Gen 5 (Xiaomi 17). Sale vacío, sin error. El arreglo pide recompilar el JNI contra ORT 1.28.0 | **Autoprueba al arrancar**: transcribir un WAV corto incluido en la app y comparar con el texto esperado. Si falla, avisar y cambiar a otra familia de modelo. Vigilar el porcentaje de segmentos vacíos. Fijar la versión de sherpa-onnx y actualizar solo a propósito. |
| F9 | Las librerías nativas no cargan en dispositivos con páginas de 16 KB | Android 15+ admite páginas de 16 KB. sherpa-onnx lo soporta desde [PR #2520](https://github.com/k2-fsa/sherpa-onnx/pull/2520) (25-ago-2025, v1.12.10); #3291 lo cerró al migrar a un ORT más nuevo | Comprobar la alineación de los `.so` del APK final (APK Analyzer, o `zipalign -c -P 16 -v 4`) y probar en el emulador de 16 KB. No mezclar `.so` de terceros sin alinear. |
| F10 | APK enorme y modelos | El AAR `sherpa-onnx-1.13.8.aar` pesa **47,8 MB** e incluye varias ABIs. Parakeet v3 son ~640 MB | `abiFilters += "arm64-v8a"`. Los modelos **fuera del APK**: se descargan la primera vez a almacenamiento privado con SHA-256, o se copian con `adb push`, que vale para uso personal. **No descargar `.so`** en tiempo de ejecución (P10). |
| F11 | Licencias de modelos | sherpa-onnx Apache-2.0; Silero VAD MIT (el README tiene un badge CC BY-NC incoherente); Parakeet v3 **CC-BY-4.0**; Canary-180M-Flash **CC-BY-4.0**; Whisper MIT; Kroko (zipformer streaming `es`) posiblemente con licencia dual no comercial **[no verificado]** | Para uso personal no hay problema. Poner una pantalla de «Atribuciones» (CC-BY lo exige). Si algún día se distribuye, revisar Kroko. |
| F12 | Whisper se inventa texto en silencio o ruido | Comportamiento conocido: con silencio genera frases tipo «créditos de subtítulos» ([ejemplo](https://kieranhealy.org/blog/archives/2025/07/22/the-sound-of-silence)) | VAD siempre delante. Preferir Parakeet o Canary para español (WER en MLS: Parakeet 4,39 %, Canary 3,17 %, según sus model cards). Filtro de frases basura conocidas. Segmentos de ≤30 s (Canary: «smaller than 40 seconds»). |
| F13 | WorkManager no ejecuta a tiempo | P5 y P6: Doze no ejecuta jobs, hay cuotas por bucket y, desde Android 16, los jobs cuentan contra la cuota aunque haya FGS | Lo urgente (extraer recordatorios, p. ej. «recuérdame en 20 min…») se hace **en el FGS**, con red sin restricciones. WorkManager solo para lo diferible, con `setRequiresCharging`/`UNMETERED`. Registrar `WorkInfo.getStopReason()`. |
| F14 | El recordatorio no salta | P11: falta el permiso exacto, el móvil se reinició, cambió la hora o la zona | `USE_EXACT_ALARM` (sideload) y `setExactAndAllowWhileIdle`, o `setAlarmClock` para lo crítico. Room es la fuente de verdad y se reprograma en `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` y `MY_PACKAGE_REPLACED`. Pedir `POST_NOTIFICATIONS` (Android 13+). Si la app reproduce un sonido propio, usar `USAGE_ALARM` (P9). |
| F15 | El almacenamiento se llena | PCM de 16 kHz, mono, 16 bits = 16 000 × 2 × 3600 = **115,2 MB/h** (≈1,8 GB en 16 h) | Nunca guardar PCM crudo de forma persistente. Opus a ~24 kbps ≈ **10,8 MB/h** (`MediaCodec` + `MediaMuxer` OGG, API 29+) o AAC-LC. Retención configurable del audio (p. ej. 7 días) y el texto para siempre. |
| F16 | La búsqueda falla con acentos, o el SQLite cambia entre dispositivos | La versión de SQLite del sistema depende del nivel de API y del fabricante. Room 2.x solo tiene `@Fts3/@Fts4` | Usar `BundledSQLiteDriver`: `-DSQLITE_ENABLE_FTS5`, FTS4, JSON1, RTREE y `THREADSAFE=2` están verificados en [sqlite-bundled/build.gradle](https://github.com/androidx/androidx/blob/androidx-main/sqlite/sqlite-bundled/build.gradle). Tokenizador `unicode61 remove_diacritics 2`. |
| F17 | La API key se pierde o se filtra | P14. Las claves de Keystore no van en las copias de seguridad, así que un texto cifrado restaurado no se puede descifrar | Clave AES-GCM en Keystore (sin `setUserAuthenticationRequired`) + texto cifrado en DataStore. Si salta `KeyPermanentlyInvalidatedException`, pedir la key otra vez. **Excluir la base de datos y las preferencias de Auto Backup** (`dataExtractionRules`): por privacidad y porque la key restaurada no sirve. |
| F18 | Cambios de Android 17 al apuntar a 37 | P9 y P10 | Sonidos de recordatorio con `USAGE_ALARM`. Si el LLM corre en la red local, pedir `ACCESS_LOCAL_NETWORK`. Nada de cargar `.so` dinámicos. |
| F19 | Instalación | P12 | Instalar por **ADB** (exento) o con la cuenta «limited distribution». Guardar el keystore de firma: sin él no se puede actualizar sin desinstalar, y eso borra los datos. |
| F20 | Legal y RGPD | Grabar conversaciones en las que *participas* es lícito (STC 114/1984), pero grabar conversaciones ajenas puede caer en el art. 197 CP ([resumen divulgativo](https://www.recordinglaw.com/es/world-laws/world-recording-laws/spain-recording-laws/)). Una app que escucha todo el día capta conversaciones de terceros. Enviar esas transcripciones a un LLM en la nube es una cesión; DeepSeek, según su política y fuentes secundarias, guarda los datos en servidores de China ([VentureBeat](https://venturebeat.com/ai/is-deepseek-really-sending-data-to-china-lets-decode/)) | Ver `06-legal-privacidad.md`. A nivel de stack: botón de «modo privado» en la notificación del FGS, proveedor de LLM configurable (preferir uno con tratamiento en la UE), mandar al LLM solo texto y nunca audio. En v2, **filtrar por la voz del dueño** con el extractor de embeddings de hablante de sherpa-onnx, para quedarse solo con conversaciones donde participa. |

Ojo con Sentry (regla global del usuario): Sentry Android con integración NDK captura los crashes
nativos de sherpa-onnx y de ORT, que de otra forma solo se ven en logcat. **No meter
transcripciones en breadcrumbs ni en extras** (RGPD). Versión del SDK **[no verificada]**.

---

## 4. Piezas del diseño que cambiaría

1. **Pipeline con una cola duradera en vez de ASR en línea**:
   ```
   AudioRecord(VOICE_RECOGNITION, 16 kHz, mono, PCM16)   [hilo URGENT_AUDIO]
     → Silero VAD (sherpa-onnx, ventana de 512 muestras)
     → segmento de voz (≤30 s) → .ogg Opus + fila segment(PENDING, t_inicio, t_fin, ruta)
   [hilo ASR único, BACKGROUND] segment PENDING → transcript + FTS → DONE
   [corrutina en el FGS] transcript nuevo → LLM "extraer tareas/recordatorios" → AlarmManager
   [WorkManager, cargando/Wi-Fi] resumen diario, reindexado, retención
   ```
   Ventajas: se puede retranscribir con un modelo mejor, se puede procesar «solo cargando» y no se
   pierde nada si el proceso muere. *Perezoso:* sin segundo pase ni modelo streaming en la v1;
   añadirlos si la latencia en vivo importa.
2. **El LLM urgente fuera de WorkManager** (F13). WorkManager solo para lo diferible.
3. **Room 3.0.3 + `BundledSQLiteDriver` + `@Fts5`** (bm25, tokenizador con
   `remove_diacritics 2`).
   - Riesgo: Room 3 salió en julio de 2026, solo funciona con KSP y Kotlin, y obliga a DAOs
     `suspend`, así que los agentes tienen poco entrenamiento con él. Hay que leer la documentación
     (Context7 o las release notes) antes de escribir los DAOs.
   - Alternativa conservadora y válida: Room 2.8.5 + `@Fts4(tokenizer = UNICODE61)` sobre el SQLite
     del sistema.
4. **Recuperación de la escucha por interacción del usuario** (F1, F2): notificación «reanudar»,
   widget y heartbeat con un watchdog de 15 min. Nunca reintentar en bucle desde segundo plano.
5. **Modelo según la RAM + autoprueba** (F5, F8), con la familia de modelo configurable en Ajustes.
6. **«Preguntar» sin embeddings en la v1**:
   - El LLM convierte la pregunta en palabras clave y un rango de fechas.
   - FTS5 recupera los top-k.
   - Se añaden los resúmenes diarios del rango.
   - Se responde citando la marca de tiempo.
   - Prefijo del prompt estable (instrucciones y esquema primero, contenido variable al final) para
     acertar la caché del proveedor.

   *Perezoso:* embeddings (sqlite-vec o coseno en Kotlin) solo si el recall de FTS se queda corto.
7. **Huecos visibles** (F4) y **métrica de cobertura diaria** (F3): sin eso el usuario cree que la
   app «lo recuerda todo» cuando no es verdad.

Lo que **no** cambiaría:
- Kotlin + Compose.
- FGS `microphone` (es el único tipo correcto: sin tope de 6 h y con while-in-use).
- `AudioRecord` a 16 kHz.
- Silero VAD.
- sherpa-onnx (API Kotlin oficial, AAR en releases y JitPack con `v1.13.8`).
- OkHttp.
- Keystore.

Descarto como reconocedor principal el `SpeechRecognizer` del sistema: está pensado para frases
cortas y compite por el micrófono con nuestro `AudioRecord` (P7).

---

## 5. Versiones verificadas (15-oct-2026)

| Pieza | Versión | Fuente |
|---|---|---|
| Kotlin | 2.4.21 (8-oct-2026) | [kotlinlang releases](https://kotlinlang.org/docs/releases.html) |
| Android Gradle Plugin | 9.4.0 (sep-2026); Gradle 9.6.0, JDK 17, API máx. 37, NDK por defecto 28.2.13676358 | [AGP releases](https://developer.android.com/build/releases/gradle-plugin) |
| Compose BOM | 2026.09.00 (Compose UI 1.12.1; Material3 1.4.0 leído por alineación de la tabla **[comprobar]**) | [bom-mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) |
| compileSdk / targetSdk | 37 (Android 17) | AGP + [17/setup-sdk](https://developer.android.com/about/versions/17/setup-sdk) |
| Room 2 | 2.8.5 (9-sep-2026), estable | [Google Maven](https://dl.google.com/android/maven2/androidx/room/room-runtime/maven-metadata.xml) |
| Room 3 | 3.0.3 estable (9-sep-2026); 3.1.0-alpha01; `@Fts5` desde 3.0.0-alpha02 | [releases/room3](https://developer.android.com/jetpack/androidx/releases/room3) |
| androidx.sqlite (bundled) | 2.7.1 estable; 2.8.0-alpha01 | [releases/sqlite](https://developer.android.com/jetpack/androidx/releases/sqlite) |
| WorkManager | 2.12.0 (23-sep-2026), minSdk 24 | [releases/work](https://developer.android.com/jetpack/androidx/releases/work) |
| OkHttp | 5.5.0 (metadatos de Maven Central, `lastUpdated` 16-ago-2026) | [maven-metadata](https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml) |
| sherpa-onnx | 1.13.8 (10/11-sep-2026); AAR de 47,8 MB; JitPack `com.github.k2-fsa:sherpa-onnx:v1.13.8` | [releases](https://github.com/k2-fsa/sherpa-onnx/releases) |
| Silero VAD en sherpa | v4/v5; `silero_vad.int8.onnx` 208 KB, `silero_vad.onnx` 629 KB, v5 2,22 MB | [sherpa VAD](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) |
| Parakeet-TDT-0.6b-v3 int8 | encoder 622 MB / decoder 12 MB / joiner 6,1 MB; 25 idiomas europeos; CC-BY-4.0 | [sherpa NeMo](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html), [HF](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) |
| Canary-180M-Flash | 182 M parámetros; en, de, fr, es; <40 s; CC-BY-4.0 | [HF](https://huggingface.co/nvidia/canary-180m-flash) |
| Expo SDK | 57 (RN 0.86; 57.0.17 → RN 0.86.3) | [changelog](https://expo.dev/changelog/sdk-57) |
| expo-audio / expo-sqlite | 57.0.5 / 57.0.4 | registro npm |
| whisper.rn | 0.7.4 (27-ago-2026), MIT | registro npm |
| react-native-sherpa-onnx | 0.4.4 (8-sep-2026), MIT | registro npm |
| op-sqlite | 18.2.5 (20-sep-2026) | registro npm |
| Flutter: sherpa_onnx / record / flutter_foreground_task | 1.13.8 / 7.1.2 / 11.0.3 | API de pub.dev |
| Compose Multiplatform | 1.12.0 (ago-2026) | fuentes secundarias |

---

## 6. Lo que no he podido verificar

- Si un `START_STICKY` tras la muerte del proceso recupera un FGS `microphone` en Android 14+. No
  está documentado; se ha diseñado suponiendo que no.
- Si `expo-audio` `AudioStream` sigue entregando PCM en segundo plano.
- Cómo se comporta Headless JS con la New Architecture o bridgeless.
- RTF y consumo de Parakeet, Canary o Whisper en móviles concretos. Solo hay RTF de CPU de escritorio.
- Tamaño real del paquete sherpa-onnx de Canary-180M int8 (solo hay una exportación de terceros:
  ~273 MB).
- Tamaño exacto del AAR filtrado a arm64-v8a: hay que medirlo con APK Analyzer.
- Licencia exacta de los modelos Kroko en español.
- La fecha de aplicación en España de la verificación de desarrolladores («global en 2027» según
  Google; no hay fecha por país).
- La versión actual de SQLite del sistema por nivel de API, y si el SQLite del framework tiene FTS5.
- El pico exacto de memoria al cargar el modelo (estimado en ~2× el tamaño del fichero).
- Si los datos de la API de DeepSeek se tratan en China: solo hay fuentes secundarias; leer su
  política directamente.

---

## 7. Fuentes

Android (oficiales):
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/about/versions/15/behavior-changes-15
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/about/versions/17/behavior-changes-all
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://source.android.com/docs/core/perf/memory-limiter
- https://developer.android.com/media/platform/sharing-audio-input
- https://developer.android.com/media/platform/supported-formats
- https://developer.android.com/develop/background-work/services/alarms/schedule
- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://developer.android.com/topic/performance/power/power-details
- https://developer.android.com/developer-verification/guides/faq
- https://developer.android.com/jetpack/androidx/releases/security
- https://developer.android.com/jetpack/androidx/releases/room
- https://developer.android.com/jetpack/androidx/releases/room3
- https://developer.android.com/jetpack/androidx/releases/sqlite
- https://github.com/androidx/androidx/blob/androidx-main/sqlite/sqlite-bundled/build.gradle
- https://developer.android.com/jetpack/androidx/releases/work
- https://developer.android.com/build/releases/gradle-plugin
- https://developer.android.com/develop/ui/compose/bom/bom-mapping
- https://kotlinlang.org/docs/releases.html
- https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml

SQLite: https://www.sqlite.org/howtocorrupt.html

React Native / Expo:
- https://github.com/facebook/react-native/blob/main/packages/react-native/ReactAndroid/src/main/java/com/facebook/react/modules/core/JavaTimerManager.kt
- https://reactnative.dev/docs/headless-js-android
- https://docs.expo.dev/versions/latest/sdk/background-task/
- https://docs.expo.dev/versions/latest/sdk/audio/
- https://docs.expo.dev/versions/latest/sdk/sqlite/
- https://expo.dev/changelog/sdk-57
- https://github.com/mybigday/whisper.rn
- https://github.com/XDcobra/react-native-sherpa-onnx

Flutter / Capacitor / KMP / iOS:
- https://pub.dev/packages/sherpa_onnx · https://pub.dev/packages/record · https://pub.dev/packages/flutter_foreground_task
- https://github.com/Dev-hwang/flutter_foreground_task/blob/master/android/src/main/kotlin/com/pravera/flutter_foreground_task/service/ForegroundTask.kt
- https://github.com/BasedHardware/omi
- https://capacitorjs.com/docs/apis/background-runner
- https://blog.jetbrains.com/kotlin/2025/05/compose-multiplatform-1-8-0-released-compose-multiplatform-for-ios-is-stable-and-production-ready/
- https://developer.apple.com/forums/thread/120038

sherpa-onnx y modelos:
- https://github.com/k2-fsa/sherpa-onnx · https://github.com/k2-fsa/sherpa-onnx/releases
- https://github.com/k2-fsa/sherpa-onnx/pull/2520 · https://github.com/k2-fsa/sherpa-onnx/issues/3291 · https://github.com/k2-fsa/sherpa-onnx/issues/3845
- https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/file-utils.cc
- https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html
- https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html · https://k2-fsa.github.io/sherpa/onnx/android/apk.html
- https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 · https://huggingface.co/nvidia/canary-180m-flash
- https://github.com/snakers4/silero-vad
- https://kieranhealy.org/blog/archives/2025/07/22/the-sound-of-silence

Otros: https://dontkillmyapp.com/ ·
https://www.recordinglaw.com/es/world-laws/world-recording-laws/spain-recording-laws/ ·
https://venturebeat.com/ai/is-deepseek-really-sending-data-to-china-lets-decode/ ·
https://www.androidauthority.com/android-17-release-date-3639790/

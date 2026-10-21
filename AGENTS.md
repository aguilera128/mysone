# Mysone

App Android personal que escucha en segundo plano, transcribe **en el móvil**, agrupa lo oído en
conversaciones con fecha y hora, y con IA genera resúmenes, tareas, recordatorios y facts, y responde
preguntas con citas a la conversación de origen. Inspirada en Bee (Amazon), sin wearable.

Contrato completo (decisiones, modelo de datos, contratos entre módulos) en `docs/arquitectura.md`;
investigación verificada que lo justifica en `docs/investigacion/01..08`.

## Stack

Kotlin + Jetpack Compose nativo, un solo módulo `:app` (paquete `dev.javieraguilera.mysone`).
minSdk 31, compileSdk 37, targetSdk 36. AGP 9.1.1 (el último que sincroniza el Android Studio
instalado), Kotlin 2.4.21, Compose BOM 2026.09.00, Room 3.0.3 + `BundledSQLiteDriver` (FTS5),
WorkManager 2.12.0, OkHttp 5.5.0, sherpa-onnx 1.13.8 (JitPack), Sentry 8.61.0. Sin Hilt: `AppContainer`.

| Paquete | Qué hace |
|---|---|
| `capture/` | Foreground service de micrófono, `AudioRecord` 16 kHz, cola en disco PCM16, conversaciones, huecos, notificación |
| `asr/` | VAD Silero + reconocedor sherpa-onnx (Canary 180M / Parakeet v3), descarga y verificación de modelos, importar audio |
| `ai/` | Clientes Anthropic (Messages API por HTTP) y compatibles con OpenAI, procesado, recordatorio rápido, Preguntar, resumen diario |
| `tasks/` | Alarmas de recordatorios, eventos del sistema, mantenimiento periódico, exportar/olvidar/borrar |
| `data/`, `core/`, `ui/` | Room, ajustes (DataStore) y clave cifrada (Keystore); utilidades; pantallas Compose |

Por qué no Expo/Flutter/Capacitor y por qué el SDK de Anthropic no: `docs/investigacion/07` y
`docs/arquitectura.md` §1.

## Compilar e instalar en el móvil

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew assembleRelease testDebugUnitTest lint
adb install -r app/build/outputs/apk/release/app-release.apk
./gradlew --stop
```

- La release va firmada con la clave **debug** de esta máquina (uso personal, instalación manual).
  Para actualizarla desde otro PC hará falta una clave de release propia, fuera del repositorio.
- El APK lleva `arm64-v8a` y `x86_64` (~73 MB). El modelo de voz NO va dentro: se descarga en el
  onboarding (Canary 207 MB o Parakeet 670 MB, verificados por SHA-256).
- Sentry solo se activa si al compilar existe `SENTRY_DSN` (o la propiedad `mysone.sentryDsn`).
  Sin DSN no envía nada. Nunca recibe transcripciones, prompts, respuestas ni la clave.

## IA (opcional)

Desactivada hasta que en Ajustes › IA se elige proveedor, se pone la clave y se acepta el aviso de
envío. Por defecto Anthropic: `claude-haiku-5-5` en segundo plano (≈1 $/mes con 3 h de voz al día) y
`claude-opus-5-5` para responder; ambos se cambian en Ajustes. Mistral (UE), DeepSeek (desaconsejado:
datos en China) y cualquier API compatible con OpenAI como alternativas. Sin IA, la transcripción y la
búsqueda funcionan igual en local. Detalle y costes: `docs/investigacion/05`.

## Limitaciones conocidas (v1)

- Solo Android. No graba llamadas (Android lo impide) y, mientras otra app usa el micro, la
  escucha queda en «micrófono ocupado» y se registra el hueco.
- Tras reiniciar o actualizar, Android no deja reabrir el micro solo: sale «Toca para reanudar».
- Monosílabos aislados («Sí.», «No.») no llegan al reconocedor (VAD con 0,25 s mínimo, a propósito:
  `docs/investigacion/08`). Sin separación de voces (quién habla) ni audio guardado.
- Probado a fondo en emulador (API 34 y 36) con IA falsa (`tools/mock-llm.mjs`). **Sin probar todavía**:
  un móvil real (batería, fabricantes que matan procesos, micro en el bolsillo), un proveedor de IA
  real con clave de verdad, la pantalla de bloqueo con PIN y Android 17.

## Pruebas en emulador

Build y tests desde la raíz, en Git Bash:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew connectedDebugAndroidTest assembleDebug testDebugUnitTest   # con un emulador arrancado
./gradlew --stop
```

Los tests instrumentados (`app/src/androidTest`) prueban los DAOs y el FTS5 sobre Room en memoria
con `BundledSQLiteDriver`.

Build `qa` (`./gradlew installQa`): la release con R8 y shrink, firmada con la clave debug, `dev.javieraguilera.mysone.qa` con el nombre «Mysone QA» y sin depurable, con http:// permitido solo a `10.0.2.2` y `localhost` para probarla contra `tools/mock-llm.mjs`.

IA falsa para las pruebas: `node tools/mock-llm.mjs` (puerto 8787). En la app, URL
`http://10.0.2.2:8787` y clave `test-key`; imita la Messages API de Anthropic y la de OpenAI.

### Audio de prueba y la «ñ»

No uses la voz TTS «Microsoft Helena Desktop» (la de `conv1.wav`, `conv2.wav` y la autoprueba) para juzgar
la «ñ»: con ella Canary parte casi todas («espa ol», «ma ana») y con voz humana no
(`docs/investigacion/08-diagnostico-asr.md`). Para eso hay voz real CC-BY-4.0 de MLS-es (16 clips con «ñ»,
PCM16 mono 16 kHz) en
`C:/Users/aguil/AppData/Local/Temp/claude/C--Users-aguil-Dev-mysone/70657f54-8e94-4902-a40b-c79f75e4892e/scratchpad/asrdiag/audio/mls`,
con `refs.tsv` (`mls/<fichero>.wav<TAB>texto`, rutas relativas a `audio/`). Es el scratchpad de una sesión
y puede desaparecer: el original está en Hugging Face, `facebook/multilingual_librispeech`, config
`spanish`, split `test`.

### Micrófono falso (solo debug)

Para probar la ruta en vivo (AudioRecord → VAD → cola → ASR → conversaciones) con audio conocido:
si al empezar a capturar existe `files/debug/mic.pcm` (PCM16 LE mono 16 kHz), `AudioCapture` usa sus
muestras en vez de las del micro, a ritmo real; al acabarse lo renombra a `mic.pcm.done` y sigue con
silencio. El micro se abre igual (permiso, indicador), así que el resto del flujo no cambia. En release
no existe (R8 lo elimina).

```bash
export MSYS_NO_PATHCONV=1   # Git Bash: si no, convierte /data/... en una ruta de Windows
ffmpeg -i conv1.wav -f s16le -ac 1 -ar 16000 mic.pcm
adb push mic.pcm /data/local/tmp/mic.pcm
adb shell "run-as dev.javieraguilera.mysone sh -c 'mkdir -p files/debug && cp /data/local/tmp/mic.pcm files/debug/'"
```

Después, empezar (o reanudar) la escucha en la app. Para repetir, volver a copiar `mic.pcm` y pausar y
reanudar la escucha (o detenerla y empezar): el fichero se lee al abrir el micro, no mientras se captura.

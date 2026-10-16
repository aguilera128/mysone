# Mysone

Contrato completo en `docs/arquitectura.md`.

## Pruebas en emulador

Build y tests desde la raíz, en Git Bash:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
./gradlew connectedDebugAndroidTest assembleDebug testDebugUnitTest   # con un emulador arrancado
./gradlew --stop
```

Los tests instrumentados (`app/src/androidTest`) prueban los DAOs y el FTS5 sobre Room en memoria
con `BundledSQLiteDriver`.

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

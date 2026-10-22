# 09 — Quién habla: diarización y voz del dueño con sherpa-onnx

Fecha: 22-oct-2026. Medido en el PC de desarrollo (Windows, x86_64) con **sherpa-onnx 1.13.8 para Python** (la misma
versión que el AAR de la app) y 2 hilos, y después en el emulador `Pixel_7_Pro_API_34` con la app. Los tiempos de
escritorio sirven para comparar entre sí, no son los del móvil. Las muestras son pequeñas (9 y 14 voces): diferencias
de uno o dos puntos son ruido.

---

## 1. Por qué

Las llamadas importadas (`ConvSource.CALL`) y los audios importados salían como un único bloque de texto sin saber
quién decía cada frase. Se quería «Hablante 1», «Hablante 2»… y, si el dueño registra su voz, «Tú». El AAR de
sherpa-onnx 1.13.8 ya trae lo necesario, sin dependencias nuevas.

## 2. API de sherpa-onnx 1.13.8 (comprobada)

Firmas leídas con `javap` en el `classes.jar` del AAR de la caché de Gradle y fuentes Kotlin de la etiqueta `v1.13.8`
(`sherpa-onnx/kotlin-api/OfflineSpeakerDiarization.kt`, `Speaker.kt`, `SpeakerEmbeddingExtractorConfig.kt`) y sus
ejemplos (`kotlin-api-examples/test_offline_speaker_diarization.kt`, `test_speaker_id.kt`):

- `OfflineSpeakerDiarization(assetManager = null, config)` con `OfflineSpeakerDiarizationConfig(segmentation =
  OfflineSpeakerSegmentationModelConfig(pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model,
  windowShiftRatio = 0.1)), embedding = SpeakerEmbeddingExtractorConfig(model), clustering = FastClusteringConfig(
  numClusters = -1, threshold = 0.5), minDurationOn = 0.2, minDurationOff = 0.5)`; `process(FloatArray)` →
  `OfflineSpeakerDiarizationSegment(start, end, speaker, confidence)`. Pide **el audio entero de una vez**.
- `SpeakerEmbeddingExtractor(assetManager = null, SpeakerEmbeddingExtractorConfig(model, numThreads))`:
  `createStream()`, `stream.acceptWaveform(samples, sampleRate)`, `inputFinished()`, `isReady(stream)`,
  `compute(stream)` → `FloatArray`, `dim()`.
- `SpeakerEmbeddingManager(dim)`: `add`, `search(embedding, threshold)`, `verify`. No se usa: el coseno se calcula en
  Kotlin, que así se prueba en la JVM.
- La JNI lee los campos de las data class por nombre: ya los conserva la regla `-keep class com.k2fsa.sherpa.onnx.**`.

## 3. Modelos

De las releases `speaker-segmentation-models` y `speaker-recongition-models` (sic) de sherpa-onnx, en Hugging Face
(`csukuangfj/sherpa-onnx-pyannote-segmentation-3-0` @ `9403a6902bb58e3d5ae8c7e77c3422de279db2e0` y
`csukuangfj/speaker-embedding-models` @ `0743f301363dec56491a490f6d6cbc9d67f9a3bf`). SHA-256 del puntero LFS de la API
de HF, comprobado con `sha256sum` tras bajarlos. Licencias: 3D-Speaker (CAM++ y ERes2Net) Apache-2.0 según la API de
ModelScope de cada modelo (`iic/speech_eres2net_sv_en_voxceleb_16k`, `iic/speech_campplus_sv_zh_en_16k-common_advanced`…)
y el LICENSE del repo `modelscope/3D-Speaker`; WeSpeaker VoxCeleb CC-BY-4.0 (`docs/pretrained.md` de wenet-e2e/wespeaker);
pyannote segmentation-3.0 MIT (LICENSE del repo de HF, © 2022 CNRS); la de NeMo TitaNet no se comprobó.

Voz del dueño: cada voz de MLS-es registrada con un clip de ~10 s y probada con turnos de 1,5–5 s de otro clip suyo
frente a los de las demás (9 voces). EER = error con el mejor umbral; «tel» = turnos en banda telefónica (300–3400 Hz)
contra el registro en banda ancha. RTF = tiempo / duración del audio, una pasada, 2 hilos.

| Modelo | Tamaño | Licencia | EER ancha | EER tel | Umbral ancha → tel | RTF |
|---|---|---|---|---|---|---|
| 3D-Speaker CAM++ VoxCeleb (`3dspeaker_speech_campplus_sv_en_voxceleb_16k`) | 29,6 MB | Apache-2.0 | 46 % | 44 % | — | 0,021 |
| 3D-Speaker CAM++ zh-en «advanced» | 28,3 MB | Apache-2.0 | 0 % | 6 % | 0,55 → 0,39 | 0,023 |
| **3D-Speaker ERes2Net VoxCeleb** (`3dspeaker_speech_eres2net_sv_en_voxceleb_16k`) | **26,5 MB** | **Apache-2.0** | **0 %** | **6 %** | **0,48 → 0,41** | 0,056 |
| WeSpeaker ResNet34-LM VoxCeleb | 26,5 MB | CC-BY-4.0 | 7 % | 38 % | — | 0,039 |
| NeMo TitaNet small | 40,3 MB | sin comprobar | 1 % | 6 % | 0,43 → 0,22 | 0,024 |

Elegido **ERes2Net VoxCeleb**: el único que separa bien en las dos bandas con un umbral casi igual (las llamadas mezclan
la voz del dueño, grabada en el teléfono, y la de la otra persona, que llega por la red). Más lento que CAM++, pero en
la importación da igual. SHA-256 `c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5`, 26 485 263 B,
192 valores por huella. Segmentación pyannote: `model.int8.onnx` 1 540 506 B (`d582f4b4…27b5d`), MIT; no se usa (§4).

## 4. Enfoques

Conversaciones sintéticas con voces reales: turnos de 1,5–5 s (cortados en pausas de los clips de MLS-es) alternando
2 o 3 voces, con pausas de 0,1–0,8 s entre turnos, y la misma conversación en banda telefónica. Se pasa el VAD de la
app (Silero 0,5 / 0,5 s / 0,25 s, margen de 1,5 s) y se mide el **% del tiempo de voz con el hablante correcto**
(mejor emparejamiento uno a uno entre grupos y voces reales; un grupo de más cuenta como error). 10 conversaciones,
461 s, 9 voces.

- **Una huella por trozo del VAD y agrupar**: 59 %. El VAD junta los turnos con pausas de menos de 0,5 s: 28 de 41
  trozos mezclaban dos voces. En llamadas reales los turnos se pisan aún más.
- **Huellas de ventanas de 1,5 s cada 0,75 s, agrupar todas las del fichero y partir cada trozo donde cambia el
  grupo**: 94–98 % en banda ancha según el modelo (ERes2Net 97,5 %, umbral 0,3) y en banda telefónica ERes2Net 93 %
  frente a 82–83 % de CAM++ y TitaNet. Ventanas de 2 s: peor (92–95 %).
- **Igual, pero el corte se decide con las ventanas del propio trozo** (lo que hace la app: así cada parte se
  transcribe al momento, sin guardar audio ni decodificar dos veces): 95,9 % frente a 96,5 % del anterior. Prácticamente
  lo mismo.
- **`OfflineSpeakerDiarization` (segmentación pyannote 3.0 int8 + ERes2Net)** sobre el fichero entero, partiendo los
  trozos por sus turnos: con su configuración por defecto (`windowShiftRatio` 0,1, umbral 0,5) 81 % en banda ancha y
  62 % en telefónica, a 0,55 y 0,30 × tiempo real; con `windowShiftRatio` 0,5, 89 % y 70 % a 0,07. Su umbral no se
  afinó (solo 0,5), pero partía muy por debajo del método de ventanas con el mismo extractor (97,5 % y 93 %).

Descartado pyannote además por diseño: necesita el fichero entero en memoria como `FloatArray` (una hora a 16 kHz son
230 MB) y la importación de Mysone va en streaming (decodificar → VAD → transcribir) sin guardar nunca el audio.

## 5. Constantes finales

Réplica exacta del código Kotlin (`Speakers.kt`) en Python: 14 voces de MLS-es (4 clips de 8–16 s cada una), 24
conversaciones de 2 voces y 4 de 3 (1 590 s); voz del dueño registrada con otros 2 clips suyos, como en la app (media
de las huellas de sus ventanas). «Tú ok» = % de la voz del dueño marcada «Tú»; «otros como Tú» = % de la de los demás
marcada «Tú» (cada voz hace de dueño por turnos).

| Partir (trozo) | Agrupar | Acierto ancha | Acierto tel | Grupos de más (media) | Tú ok / otros como Tú, ancha (umbral 0,55) | Ídem, tel |
|---|---|---|---|---|---|---|
| 0,3 | 0,30 | 95,1 % | 86,8 % | 0,21 / 0,32 | 99,4 % / 8,7 % | 93,9 % / 18,0 % |
| 0,4 | 0,30 | 95,1 % | 88,2 % | 0,21 / 0,32 | 99,4 % / 8,7 % | 93,7 % / 15,4 % |
| **0,4** | **0,35** | **96,2 %** | **89,0 %** | 0,18 / 0,57 | **99,4 % / 5,8 %** | **94,5 % / 14,3 %** |
| 0,4 | 0,40 | 97,6 % | 90,7 % | 0,21 / 0,75 | 99,2 % / 2,9 % | 95,0 % / 10,5 % |

Elegido partir 0,4 / agrupar 0,35 / «Tú» 0,55: 0,40 acierta algo más pero deja casi un hablante de más por llamada en
banda telefónica. En banda ancha el umbral de «Tú» da igual entre 0,35 y 0,60 (los grupos o se parecen mucho o nada);
en telefónica, subirlo de 0,45 a 0,55 cambia 3 puntos de aciertos por 3 de falsos «Tú», y un «Tú» de más es peor
(atribuye al dueño lo que dijo otro). Los «otros como Tú» vienen sobre todo de una pareja de voces muy parecidas que
queda como un solo hablante en todas las configuraciones (esa conversación se queda en ~52 %).

Huellas de ventanas solapadas: RTF 0,10–0,12 en el PC con 2 hilos, frente a 0,13 de Parakeet y 0,20 de Canary
(`08` §4): la importación tarda del orden de 1,5–2 veces más en el PC.

## 6. Prueba en el emulador

`Pixel_7_Pro_API_34` (3 GB, x86_64), Canary, sin el móvil personal (todo con `-s emulator-5554`). Audio: turnos de dos
voces de MLS-es (4096 y 11168), 51,6 s, 11 turnos con pausas de 0,15–0,6 s, en WAV y como llamada AAC a 8 kHz en banda
telefónica; voz del «dueño» = otros dos clips de 4096 (27 s) como `mic.pcm`.

1. **Migración**: APK de `main` (esquema 1), importar el WAV (11 segmentos; el último mezclaba tres turnos), instalar
   encima la versión nueva: `user_version` 2, columna `speaker`, los 11 segmentos intactos con `speaker` nulo, la
   búsqueda FTS y su `integrity-check` bien. `MigrationTest` instrumentado: correcto (9 tests de `androidTest`).
2. **Modelo de voces**: «Descargar (26 MB)» en Ajustes › Transcripción › Quién habla, desde Hugging Face: instalado.
3. **Sin voz registrada**, el WAV otra vez: 15 líneas (antes 11), 14 con su hablante («Hablante 1» y «Hablante 2»);
   el trozo que mezclaba tres turnos sale en tres líneas bien atribuidas. Una línea corta («o por cuerda.») quedó
   mezclada por un corte 0,5 s antes de tiempo. 22 s de importación frente a 16 s sin hablantes.
4. **Registrar mi voz** con `mic.pcm`: 15 s de voz, huella guardada (768 B en `files/voice`).
5. **Llamada** (carpeta `Recordings/Call`, «Transcribir también»): 15 de 15 líneas bien, la voz registrada como «Tú» y
   la otra como «Hablante 1»; la grabación se borra al acabar, como pide el ajuste.
6. **Exportar**: cada segmento lleva `speaker` (null en la conversación migrada); la huella no sale.
7. **IA falsa** (`tools/mock-llm.mjs`, que resume con las primeras frases que recibe): resúmenes «Tú: …», «Hablante 1:
   …» en las importadas con hablantes y sin marcas en la migrada. **Preguntar** «¿Quién dijo que a las cuatro estaba
   libre?» → «…«Hablante 1: Don todos modos, a las cuatro siempre estoy libre.» [c:3]».

## 7. Escucha en directo

No en v1 (`docs/arquitectura.md` §5.6): las huellas solapadas cuestan del orden del propio ASR, así que la escucha
gastaría hasta casi el doble de CPU y batería; y agrupar conversaciones de horas transcritas trozo a trozo desde una
cola que sobrevive a la muerte del proceso pediría guardar la huella de cada segmento (otra migración). Lo barato y útil
sería solo «Tú»: comparar cada trozo con la voz registrada, sin agrupar, con ventanas sin solapar (≈ la mitad de
coste; con CAM++ zh-en, que en banda ancha separa igual de bien, unas 2,5 veces menos). Falta medirlo en el móvil, con
el micro lejano y en el bolsillo.

## 8. Pendiente

- Llamadas reales del S25 Ultra: formato de la grabadora (¿estéreo con un canal por lado?, ¿la voz propia en banda
  ancha?) y cuánto tarda la importación en el móvil.
- Más de 3 voces, voces parecidas (mismo sexo y edad), solapes (dos a la vez: la parte va a una sola voz), música.
- Grabaciones largas: el agrupado es O(n²) en memoria (3 000 partes ≈ 18 MB); por encima, sin hablantes.

## 9. Cómo repetirlo

Los guiones se quedaron en el scratchpad de la sesión (no van al repositorio). Material: MLS-es del servidor de
datasets de HF (`https://datasets-server.huggingface.co/rows?dataset=facebook/multilingual_librispeech&config=spanish&split=test`,
1 500 filas: 14 voces con 4 clips de 8–16 s), pasado a WAV mono de 16 kHz con ffmpeg; venv con `sherpa-onnx==1.13.8`,
`numpy` y `soundfile`. Método: §4 (turnos cortados con el VAD a 0,15 s, pausas al azar con semilla fija, banda
telefónica con un filtro FFT 300–3400 Hz), y para §5 una copia línea a línea de `voiceWindows`, `splitByVoice`,
`quietestCut`, `clusterVoices` y `speakerNumbers`.

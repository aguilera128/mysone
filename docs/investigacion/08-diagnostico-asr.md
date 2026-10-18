# 08 — Diagnóstico del ASR en el escritorio («ñ», precisión, memoria y frases cortas)

Fecha: 19-oct-2026. Medido en el PC de desarrollo (Windows, x86_64) con **sherpa-onnx 1.13.8 para
Python** (la misma versión que el AAR de la app), onnxruntime 1.31.0 y 2 hilos, como en el móvil. Los
tiempos y la memoria son de escritorio: sirven para comparar modelos entre sí, no son los del móvil.
Las muestras son pequeñas (357 palabras en la comparativa, 208 palabras con «ñ»): diferencias de un
punto de WER son ruido.

---

## 1. Por qué

En el emulador la autoprueba enseñaba «Funciona: «… transcripción en espa ol»» y `conv1.wav` salía con
«Recuérdame ma ana a las diez de la ma ana». Había que saber si la culpa era de Canary, de la
cuantización int8, de la puntuación (PnC), de la voz sintética o de la app, y si otro modelo de
sherpa-onnx lo hace mejor en español.

## 2. Material y método

- **Comparativa general** (23 clips): `conv1.wav` y `conv2.wav` (TTS «Microsoft Helena Desktop», las de
  la prueba E2E), la antigua frase de autoprueba, 5 frases TTS con «ñ», 8 palabras o frases cortas TTS,
  5 clips de voz humana de MLS-es (audiolibros) y 2 de ejemplo de Hugging Face; y aparte otras 15
  frases cortas TTS («Ya.», «No, no.», «Vale, vale.»…) para §5. WER sobre texto
  normalizado (minúsculas, sin puntuación; los números en cifra se pasan a letra para no castigar a
  quien escribe «10»). «Voz humana» = MLS + ejemplos de HF; «TTS» = el resto.
- **Prueba de la «ñ»** (208 palabras con «ñ»):
  - TTS Helena Desktop (SAPI): 16 (la autoprueba antigua y 5 frases).
  - TTS OneCore (voces «Pablo» y «Laura» de Windows): 30 (las mismas 5 frases).
  - Voz humana limpia: 27 (16 de MLS-es, 11 de MInDS-14, llamadas de banca grabadas por teléfono a 8 kHz).
  - Esa voz humana con 5 degradaciones: 135 (banda telefónica 300–3400 Hz; ruido a 10 y a 5 dB de SNR;
    reverberación RT60 0,6 s; «bolsillo»: banda 100–4000 Hz + reverberación 0,4 s + ruido 10 dB, a −38 dBFS).
  - «Partida» = la palabra sale cortada justo en la «ñ» («espa ol», «ma ana»).
- Cada clip se decodifica **entero** y también **troceado como en la app** (Silero `threshold 0.5`,
  `minSilenceDuration 0.5`, `minSpeechDuration 0.25`, 1,5 s de pre-roll y trozos de 25 s como máximo,
  igual que `VadSegmenter`).

## 3. La «ñ» de Canary

Canary 180M flash int8 con la configuración de la app (`srcLang="es"`, `tgtLang="es"`, PnC):

| Fuente | «ñ» exactas | Partidas en la «ñ» |
|---|---|---|
| TTS Helena Desktop | 3/16 | 12 |
| TTS OneCore (Pablo, Laura) | 26/30 | 4 (todas de Pablo) |
| Voz humana limpia (MLS-es + MInDS-14) | 24/27 | 0 |
| Voz humana degradada (5 variantes) | 110/135 | 2 |
| *Parakeet v3 int8, las 208* | *179/208* | *0* |

Las «ñ» que Canary falla con voz humana no son cortes sino otra palabra («enseñan» → «enseñando»,
«enseñarme» → «enseñar», «compañía» → «compañero») o una pegada («añoscontinuó»).

Lo que se descartó como causa:

- **Cuantización**: el modelo fp32 hace lo mismo con Helena (5/16, 11 partidas).
- **Puntuación**: sin PnC es igual o peor (Helena 2/16, 13 partidas).
- **La app**: con sherpa-onnx de escritorio y el WAV tal cual sale lo mismo que en el emulador.
- **Empate de logits**: no lo es. En «espa ol» el decodificador elige `pa` con logit 22,9 frente a 17,9
  de `pañ`; el modelo «oye» de verdad un corte de palabra con esa voz.

Transcripciones de Helena (Canary int8):

| Referencia | Canary |
|---|---|
| Hola, esto es una prueba de transcripción en español. | Hola, esto es una prueba de transcripción en espa ol. |
| Mañana es el cumpleaños de la niña. | Ma ana es el cumplea os de la ni a. |
| El señor Muñoz compró castañas y piñones en España. | El se or Muñoz compró casta as y pi o nes en Espa a. |
| Este año vamos a la montaña con mi compañero. | Este a o vamos a la monta a con mi compa ero. |

**Conclusión**: el corte en la «ñ» es sobre todo un artefacto de la voz TTS Helena (y algo de Pablo) en
trozos cortos. Con voz humana Canary acierta la «ñ». El parche `restoreEnye` se mantiene, pero solo con
Canary (`cleanText(fixEnye = …)` en `Transcriber.kt`): con Parakeet no hace falta y no se toca su texto.

## 4. Comparativa de modelos

| Modelo (int8) | WER | WER voz humana | WER TTS | «ñ» | Tildes | RTF | Pico WS (MB) | Pico privada (MB) |
|---|---|---|---|---|---|---|---|---|
| Canary 180M flash (`canary-180m-es`) | 9,2 % | 4,3 % | 14,7 % | 29/42 | 27/31 | 0,20 | 740 | 897 |
| **Parakeet TDT 0.6B v3** (`parakeet-v3`) | **2,8 %** | **3,2 %** | 2,4 % | **42/42** | 28/31 | 0,13 | 1 124 | 1 270 |
| FastConformer transducer es-1424 | 4,8 % | 4,8 % | 4,7 % | 40/42 | 27/31 | 0,04 | 641 | 754 |
| FastConformer CTC es-1424 | 5,6 % | 6,4 % | 4,7 % | 40/42 | 25/31 | 0,04 | 635 | 746 |
| Nemotron 3.5 ASR streaming 0.6B (560 ms) | 4,8 % | 5,9 % | 3,5 % | 42/42 | 26/31 | 0,40 | 802 | 798 |
| Whisper small | 7,0 % | 11,2 % | 2,4 % | 40/42 | 21/31 | 0,53 | 1 267 | 1 517 |

«ñ» y «Tildes» = palabras de la referencia con «ñ» o con tilde que salen exactas. RTF = tiempo de
decodificación / duración del audio (más bajo, más rápido). WS = working set del
proceso; «privada» = memoria privada que añade el modelo. Paquetes: los de la release `asr-models` de
sherpa-onnx (`sherpa-onnx-nemo-fast-conformer-{transducer,ctc}-es-1424-int8`,
`sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11`, Whisper small int8).

Transcripciones clave (troceadas como en la app):

| Modelo | `conv1.wav` / `conv2.wav` |
|---|---|
| Referencia | «Recuérdame mañana a las diez de la mañana…» · «mi hermana Lucía cumple años el veinte de noviembre» · «hotel Alhambra Palace del doce al catorce de diciembre» · «cuatro mil quinientos euros» · «el treinta de noviembre» |
| Canary | «Recuérdame **ma ana** a las diez de la **ma ana**…» · «mi hermana lucía cumpleaños el veinte de noviembre» · «hotel Alhambra Palis del doce al catorce» · «cuatro mil quinientos euros» |
| Parakeet | «Recuérdame mañana a las 10 de la mañana…» · «Lucía cumple años el 20 de noviembre» · «hotel Alambra Palis del 12 al 14 de diciembre» · «4.500 euros» · «el 30 de noviembre» |
| FastConformer es-1424 | «Lucía cumpleaños **el de** Noviembre»; entero, «del **al de** diciembre» y «la entrega es **el de** noviembre» |
| Nemotron 3.5 | «del **X** al catorce de diciembre» |

Parakeet escribe los números en cifras (12, 4.500, 10), más útil para buscar y para las fechas de
tareas y recordatorios. FastConformer es-1424 es rápido y ligero pero **se come los números**, justo lo
que alimenta tareas y recordatorios: descartado. Nemotron 3.5 es preciso pero 3 veces más lento que
Parakeet y también pierde algún número; no necesitamos streaming. Whisper small es el más lento y el peor
con voz humana.

Contra de Parakeet: es multilingüe sin idioma forzable y con voz muy degradada se pasa al inglés
(reverberación: «Nothing, and Ferrar was fusiled…»; «bolsillo»: «Oh my god.»). Canary, con `srcLang="es"`
forzado, no lo hace. En la voz humana limpia y con ruido moderado Parakeet es el mejor.

## 5. Frases cortas y el VAD

Con `minSpeechDuration 0.25` el VAD **no deja pasar** monosílabos aislados: «Sí.», «No.», «Ya.», «Hola.» y
«No, no.» dan 0 trozos. Decodificados enteros (lo que llegaría al ASR si el VAD los dejara pasar):

| Audio | Canary | Parakeet |
|---|---|---|
| No. | Clase. | **Yeah.** |
| Ya. | Ya. | **Yeah.** |
| No, no. | Lo. | No, no. |
| Sí. | sí | Sí. |
| Vale, vale. (sí pasa el VAD) | **Male.** | Vale, vale. |

Palabras algo más largas («Vale.», «Gracias.», «Buenos días.», «Mañana.», «No sé.») pasan el VAD y salen
bien con los dos. **Se mantiene 0,25**: bajarlo metería «Yeah.» con Parakeet o basura con Canary, y perder
un «sí» suelto cuesta menos que inventar texto.

## 6. Decisión

| Tema | Decisión | Por qué |
|---|---|---|
| Modelo recomendado | **Por RAM** (`recommendedModelId(deviceRamGb())`): Parakeet v3 con **≥ 6 GB**; Canary con menos o si no se sabe | Parakeet es el más preciso (2,8 % frente a 9,2 %, 42/42 «ñ», cifras). Pica en ~1,1 GB (WS) / ~1,3 GB (privada); el Memory Limiter de Android 17 deja 2 GiB a un servicio en primer plano con 6 GB y 1 GiB con 4 GB (07 P8) |
| Modelo ligero | Canary 180M sigue en el catálogo y es el valor por defecto de `AppSettings.asrModelId` | 207 MB, ~0,75–0,9 GB de pico, idioma forzado |
| Descartados | FastConformer es-1424 (se come números), Nemotron 3.5 (lento), Whisper small (lento, peor con voz humana) | §4 |
| Parche de la «ñ» | Se mantiene, solo con Canary | §3 |
| Autoprueba | `assets/selftest_es.wav` pasa a «Hola, esto es una prueba de transcripción de voz.» (sin «ñ») | La antigua enseñaba «espa ol» con Canary aunque el modelo funcione. Los dos modelos transcriben la nueva exacta |
| VAD | `minSpeechDuration` sigue en 0,25 | §5 |

## 7. Pendiente

- Medir RAM y RTF en un móvil real (`dumpsys meminfo`, `am memory-limiter status` en Android 17), sobre
  todo Parakeet en uno de 6 GB con la escucha en segundo plano.
- Probar con voz propia grabada con el móvil en distintos sitios (mesa, bolsillo, coche): las
  degradaciones de §2 son sintéticas.

## 8. Cómo repetirlo

Los scripts y el audio quedaron en el scratchpad de la sesión (`scratchpad/asrdiag/`, temporal):
`b/scripts/bench.py` (comparativa, un modelo por proceso), `b/scripts/analyze.py` (tablas),
`b/scripts/short_test.py` (frases cortas), `scripts/eval_enye.py` (prueba de la «ñ»),
`scripts/degrade.py` (degradaciones). La voz humana es de MLS-es (`facebook/multilingual_librispeech`,
config `spanish`, split `test`, CC-BY-4.0) y de MInDS-14 (`PolyAI/minds14`, `es-ES`), ambos en Hugging Face.

La frase de autoprueba se genera con Windows PowerShell 5.1 (System.Speech no existe en PowerShell 7).
El `.ps1` tiene que ir guardado **con BOM**: sin él, PowerShell 5.1 lo lee como ANSI y estropea la «ó».

```powershell
param([string]$out)
Add-Type -AssemblyName System.Speech
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$s.SelectVoice("Microsoft Helena Desktop")
$s.SetOutputToWaveFile($out, $fmt)
$p = New-Object System.Speech.Synthesis.PromptBuilder
$p.AppendBreak([TimeSpan]::FromMilliseconds(300))
$p.AppendText("Hola, esto es una prueba de transcripción de voz.")
$p.AppendBreak([TimeSpan]::FromMilliseconds(300))
$s.Speak($p)
$s.SetOutputToNull()
$s.Dispose()
```

`powershell.exe -NoProfile -ExecutionPolicy Bypass -File selftest.ps1 -out selftest_es.wav` → PCM16
mono 16 kHz, 4,3 s.

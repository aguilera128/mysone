# 05 — Proveedor de LLM, coste y privacidad

Investigación del 15-oct-2026. Precios y condiciones comprobados hoy en las fuentes oficiales enlazadas.
Si una cifra no la he podido verificar en fuente oficial, lo digo al lado.

## 0. Conclusión corta

| Ruta | Recomendación por defecto | Coste estimado/mes (3 h voz/día) |
|---|---|---|
| (a) Procesado en segundo plano (título, resumen, tareas, facts, resumen diario) | **Claude Haiku 5.5** (`claude-haiku-5-5`), Messages API nativa, `output_config.format` (JSON Schema garantizado), `effort: "low"` | **≈ 0,60 $** |
| (b) Preguntas sobre la memoria (RAG) | Expansión de consulta con **Haiku 5.5** + respuesta con **Claude Sonnet 5.5** (`claude-sonnet-5-5`); Opus 5.5 como opción "respuesta profunda" | **≈ 9 $** (Sonnet) / ≈ 18 $ (Opus) / ≈ 0,50 $ (todo Haiku) |
| Total recomendado | Haiku (a) + Sonnet (b) | **≈ 9–10 $/mes** |

- **El precio no decide**: cualquier modelo "pequeño" (Haiku 5.5, gpt-6-luna, Mistral Small 4, DeepSeek flash, gpt-oss-120b) sale a **1–2 $/mes** para este volumen. Lo que decide es (1) salida estructurada garantizada en español, (2) privacidad/RGPD de grabaciones de terceros y (3) calidad en las respuestas.
- **Alternativa UE (máxima privacidad RGPD)**: **Mistral** (empresa en París, alojamiento UE por defecto según fuentes de terceros, DPA en vigor desde 27-jul-2026): `mistral-small-2603` para (a) y Mistral Medium 3.5 para (b). ≈ 1,5 $ (todo Small) a ≈ 7,5 $/mes (Small + Medium).
- **DeepSeek (`deepseek-flash`) no recomendado como proveedor por defecto en esta app**, pese a ser el más barato (≈ 1,5–2 $/mes): almacena y procesa en la República Popular China, la política de privacidad incluye entrenar con los datos (opt-out por email), los términos de la plataforma se rigen por ley china y no mencionan DPA. Con grabaciones ambientales de terceros es un riesgo RGPD serio. Úsalo solo si el dueño lo acepta explícitamente, y en ese caso sí aplica la regla de franjas valle (lotes de fin de día después de las 12:00 hora peninsular entre semana).
- **Embeddings: no en v1.** FTS (SQLite) + expansión de consulta con LLM + resúmenes jerárquicos (conversación → día → semana) cubre el caso. Si luego falla el recall, añadir embeddings **en el dispositivo** (EmbeddingGemma) o `voyage-4-lite` (0,02 $/MTok, 200 M tokens gratis = gratis durante años a este volumen).
- **Cliente HTTP**: dos formatos, nada más — Messages API de Anthropic (nativo, con caché y structured outputs) y `chat/completions` OpenAI-compatible (Mistral, OpenAI, DeepSeek, Groq, OpenRouter). **No usar** la capa OpenAI-compatible de Anthropic: ignora `response_format`, `strict` y no soporta prompt caching.

---

## 1. Supuestos de volumen

- 3 h de voz/día ≈ **30.000 palabras/día** en español.
- Tokens por palabra en español: **estimación 1,3–1,7** según tokenizador (no hay cifra oficial por idioma). Uso **1,6 tok/palabra** (conservador) → **≈ 48.000 tokens/día ≈ 1,44 M tokens/mes** de transcripción. El tokenizador de Haiku 5.5 cuenta ~30 % más tokens que el de Haiku 4.5 para el mismo texto (guía de migración de Anthropic). **Medirlo** con `POST /v1/messages/count_tokens` (gratuito) sobre una transcripción real antes de fijar presupuestos.
- Segmentación en **~25 conversaciones/día** (media ~1.900 tokens).
- **Por conversación** (ruta a): prefijo estable 2.000 tokens (instrucciones + descripción del esquema + 2 ejemplos), parte variable ~2.800 (metadatos + facts del usuario ~800 + transcripción ~1.900), salida ~800 (JSON ~500 + razonamiento ~300 a effort `low`).
- **Resumen diario**: entrada ~7.000 (25 resúmenes + prefijo), salida ~1.500.
- Procesado **en ráfagas** (cola que se vacía de golpe cada 30–60 min o al cargar), para que las llamadas consecutivas lean la caché de 5 min: ~5 escrituras de caché/día.
- **Preguntas**: 10/día. Paso 1 (expansión): ~1.100 entrada, ~300 salida. Paso 2 (respuesta): ~1.500 prefijo + ~8.000 de contexto recuperado + pregunta, ~1.000 salida (400 respuesta + ~600 razonamiento). Las preguntas son esporádicas (> 5 min entre sí), así que su prefijo **no** se cuenta como cacheado.

Totales mensuales (30 días):

| Bloque | Entrada nueva | Lectura de caché | Escritura de caché | Salida |
|---|---|---|---|---|
| A — procesado | 2,25 M | 1,56 M | 0,30 M | 0,645 M |
| B — preguntas (expansión + respuesta) | 3,2 M (0,33 M expansión + 2,85 M respuesta) | — | — | 0,39 M (0,09 M + 0,30 M) |

El razonamiento (thinking) se factura como salida: es la variable más incierta. Con effort `low` en extracción debería quedarse en cientos de tokens; si se dispara, el coste de salida se duplica — sigue siendo céntimos en Haiku.

---

## 2. Precios verificados (por millón de tokens, USD)

### 2.1 Anthropic — https://platform.claude.com/docs/en/about-claude/pricing

| Modelo | Entrada | Escritura caché 5 min | Escritura caché 1 h | Lectura caché | Salida | Batch (entrada/salida) |
|---|---|---|---|---|---|---|
| `claude-opus-5-5` | 4 | 5 | 8 | 0,20 (0,05×) | 20 | 2 / 10 |
| `claude-sonnet-5-5` | 2 | 2,50 | 4 | **0,10** (0,05×) | 10 | 1 / 5 |
| `claude-haiku-5-5` (prompt ≤ 100.000 tokens) | 0,10 | 0,125 | 0,20 | 0,01 | 0,50 | 0,05 / 0,25 |
| `claude-haiku-5-5` (prompt > 100.000 tokens) | 0,50 | 0,625 | 1 | 0,05 | 2,50 | 0,25 / 1,25 |

- **Haiku 5.5 tiene dos tarifas por longitud de prompt**: si una petición pasa de 100.000 tokens de entrada (contando lecturas/escrituras de caché), paga la tarifa alta entera. Mantener el contexto de las preguntas muy por debajo (≈ 10 k) — no "meter la semana entera".
- Opus 5.5 / Sonnet 5.5: 1 M de contexto a tarifa plana; 128 K de salida máxima.
- `inference_geo: "us"` → **×1,1** en todas las categorías. Por defecto `global` (precio estándar). **No existe opción UE** en la API de primera parte: solo `us` y `global`; "workspace geo" solo `us` (https://platform.claude.com/docs/en/manage-claude/data-residency).
- Discrepancia detectada: la tabla en caché de mi skill decía lectura de caché de Sonnet 5.5 = 0,20 $; la página oficial de hoy dice **0,10 $** (0,05×). Vale la oficial.
- Mínimo cacheable: **512 tokens** en Opus 5.5, Sonnet 5.5 y Haiku 5.5. Por debajo no cachea y no da error (https://platform.claude.com/docs/en/build-with-claude/prompt-caching).

### 2.2 DeepSeek — https://api-docs.deepseek.com/quick_start/pricing

| Modelo | Acierto caché (valle / punta) | Fallo caché (valle / punta) | Salida (valle / punta) |
|---|---|---|---|
| `deepseek-flash` (DeepSeek-V4.1-Flash) | 0,003 / 0,006 | 0,15 / 0,30 | 0,60 / 1,20 |
| `deepseek-v4-pro` (DeepSeek-V4-Pro-0813) | 0,022 / 0,044 | 0,66 / 1,32 | 1,98 / 3,96 |

- Punta: **01:00–04:00 y 06:00–10:00 UTC, lunes a viernes**, excluidos festivos chinos. Valle: el resto, fines de semana y festivos chinos completos. Coincide con la nota global del dueño.
- `deepseek-v4-flash` y `deepseek-v4-flash-vision-exp` se siguen aceptando pero los sirve V4.1-Flash a precio Flash; los modelos originales están retirados.
- Contexto 1 M; salida máx. 384 K. JSON Output, tool calls, endpoint **Anthropic** (`https://api.deepseek.com/anthropic`) y OpenAI (`https://api.deepseek.com`).
- Caché de contexto en disco **automática**, "best-effort", sin escritura cobrada aparte (lo no cacheado se cobra a precio de fallo); se limpia "en horas o días". Campos: `usage.prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` (https://api-docs.deepseek.com/guides/kv_cache). No documenta tamaño mínimo de unidad.
- **Thinking activado por defecto** (effort `high`): desactivarlo para extracción con `"thinking": {"type": "disabled"}` (formato OpenAI) (https://api-docs.deepseek.com/guides/thinking_mode).

### 2.3 Mistral (UE)

| Modelo | ID de API | Entrada | Caché | Salida | Contexto | Fuente |
|---|---|---|---|---|---|---|
| Mistral Small 4 (26.03) | `mistral-small-2603` | 0,15 | 10 % de entrada (genérico, ver abajo) | 0,60 | 256 k | https://docs.mistral.ai/models/mistral-small-4-0-26-03 |
| Mistral Medium 3.5 (26.04) | la ficha muestra `mistral-medium-3-5+2` (sospechoso, verificar en la consola) | 1,50 | — | 7,50 | 256 k | https://docs.mistral.ai/models/mistral-medium-3-5-26-04 |
| Mistral Large 4 (26.10, **Public Preview**) | `mistral-large-4` | 0,68 (precio lanzamiento −50 %, original 1,36) | 0,07 | 2,09 (orig. 4,18) | 1 M | ficha en el repo de docs vía Context7 (`/mistralai/platform-docs-public`) |
| Mistral Large (página de precios, probablemente Large 3) | — | 0,50 | — | 1,50 | — | https://mistral.ai/pricing |
| `mistral-embed` | `mistral-embed` | 0,10 (**solo fuentes de terceros**) | — | — | 8 k | no verificado en fuente oficial |

- Las fichas muestran los dos precios sin etiqueta; el orden entrada/salida es inferido (coincide con fuentes de terceros).
- Caché: parámetro `prompt_cache_key` en `/v1/chat/completions`; "Cached tokens are billed at 10% of the standard input token price" (referencia de la API). Batch −50 %.
- Structured outputs con `response_format: {type: "json_schema", json_schema: {name, schema, strict: true}}` — ver §6.2.

### 2.4 OpenAI — https://developers.openai.com/api/docs/pricing

| Modelo | Entrada | Entrada cacheada | Salida |
|---|---|---|---|
| `gpt-6-luna` (gama mini) | 0,10 | 0,01 | 0,50 |
| `gpt-5-nano` | 0,05 | 0,005 | 0,40 |
| `gpt-5.4-mini` | 0,75 | 0,075 | 4,50 |
| `gpt-6.1-sol` | 2,00 | 0,10 | 10,00 |
| `text-embedding-3-small` | 0,02 | — | — |

- Batch/Flex con descuento. Endpoints de residencia de datos (UE) **+10 %** para modelos publicados desde el 5-mar-2026.
- No he verificado las capacidades de `gpt-6-luna` (structured outputs estrictos, calidad en español); solo su precio.

### 2.5 Groq — https://console.groq.com/docs/models

- `openai/gpt-oss-120b`: 0,15 / 0,60; `openai/gpt-oss-20b`: 0,075 / 0,30; contexto 131 k. Llama 3.x: "Contact sales". La página no documenta caché.
- Útil como respaldo de STT en la nube: `whisper-large-v3-turbo` 0,04 $/hora de audio.

### 2.6 OpenRouter — https://openrouter.ai/docs/faq

- Precio del proveedor sin margen; **5,5 % de comisión al comprar créditos** (mín. 0,80 $; 5 % cripto). BYOK: 25.000 $/mes gratis, luego 5 %.
- Añade un intermediario más (otro encargado del tratamiento) sin ventaja de precio para un único proveedor. Solo tiene sentido para probar modelos rápido.

---

## 3. Coste mensual estimado (supuestos de §1)

| Opción | A — procesado | B — preguntas | Total/mes |
|---|---|---|---|
| **Haiku 5.5 (A) + Sonnet 5.5 (B respuesta, Haiku expansión)** — recomendado | 0,60 $ | 8,8 $ | **≈ 9,4 $** |
| Haiku 5.5 (A) + Opus 5.5 (B respuesta) | 0,60 $ | 17,5 $ | ≈ 18 $ |
| Todo Haiku 5.5 | 0,60 $ | 0,52 $ | ≈ 1,1 $ |
| Todo Sonnet 5.5 | 11,9 $ | 10,3 $ | ≈ 22 $ |
| Todo Opus 5.5 | 23,7 $ | 20,6 $ | ≈ 44 $ |
| `deepseek-flash` (A en valle; B mitad punta) | 0,77 $ | 0,71–1,43 $ | ≈ 1,5–2,2 $ |
| `deepseek-v4-pro` | 3,0 $ | 2,9–5,8 $ | ≈ 6–9 $ |
| Mistral Small 4 todo | 0,79 $ | 0,71 $ | ≈ 1,5 $ |
| Mistral Small 4 (A) + Medium 3.5 (B) | 0,79 $ | 6,6 $ | ≈ 7,4 $ |
| Mistral Large 4 preview todo | 3,2 $ | 3,0 $ | ≈ 6,2 $ (precio de lanzamiento) |
| `gpt-6-luna` todo | 0,59 $ | 0,52 $ | ≈ 1,1 $ (+10 % si UE) |
| `gpt-5-nano` todo | 0,39 $ | 0,32 $ | ≈ 0,7 $ |
| Groq `gpt-oss-120b` todo (sin caché) | 1,0 $ | 0,71 $ | ≈ 1,7 $ |

Cálculo de ejemplo (Haiku, bloque A): 2,25 M × 0,10 + 1,56 M × 0,01 + 0,30 M × 0,125 + 0,645 M × 0,50 = 0,225 + 0,016 + 0,038 + 0,323 ≈ **0,60 $**.

Observaciones:
- **Batch API (−50 %)** no compensa aquí: ahorraría ~0,30 $/mes en Haiku, añade hasta 24 h de latencia y el Batch de Anthropic **retiene datos 29 días** y no es elegible para ZDR.
- **La caché ahorra poco en euros a este volumen con Haiku** (~0,1 $/mes), pero en Sonnet/Opus y en conversaciones de seguimiento sí cuenta, y es gratis diseñarla bien desde el principio (§7). En preguntas esporádicas (> 5 min) marcar `cache_control` **encarece** (escritura 1,25× sin lectura): usar caché solo en el hilo de seguimiento.
- El margen de error real está en (1) tokens/palabra y (2) tokens de razonamiento. Instrumentar `usage` en cada llamada y guardar el coste por día en la BD local.

---

## 4. Privacidad y RGPD

Contexto: la app graba **conversaciones de terceros** (no solo del dueño). Aunque el uso sea personal, al enviar texto a un proveedor cloud ese proveedor es encargado del tratamiento. Reglas que aplico: enviar solo **texto** (nunca audio) al LLM, solo lo necesario, y preferir proveedores sin entrenamiento y con DPA.

| Proveedor | Entrenamiento con datos de API | Retención por defecto | Ubicación | DPA / transferencias | ZDR |
|---|---|---|---|---|---|
| **Anthropic** | "Retained data is never used for model training without your express permission." | Docs de la plataforma: el contenido "is not retained by default" salvo *Covered Models* (Fable/Mythos, 30 días). Artículo de privacidad (1-jul-2026): borrado "within 30 days". Marcado por uso indebido: hasta 2 años. Batch: 29 días. Tratarlo como **≤ 30 días**. | Inferencia `global` o `us`; sin opción UE; almacenamiento `us`. | DPA con SCC incorporado automáticamente a los Commercial Terms (https://privacy.claude.com/en/articles/7996862-how-do-i-view-and-sign-your-data-processing-addendum-dpa, https://www.anthropic.com/legal/data-processing-addendum) | A petición vía ventas (no autoservicio). Haiku/Sonnet/Opus 5.5 sí son compatibles con ZDR. |
| **Mistral** | DPA §2.3: permite entrenar "unless Customer is or has opted-out". Help center (vía búsqueda): plan **Scale** fuera del entrenamiento por defecto; plan **Experiment** (gratis) hay que hacer opt-out en el panel de privacidad. | DPA (27-jul-2026) no fija días; fuentes de terceros hablan de 30 días para abuso. | UE por defecto (fuentes de terceros; el DPA no lo dice). Transferencias con adecuación/SCC. | DPA público: https://legal.mistral.ai/terms/data-processing-addendum | Existe ("when zero data retention has been activated"); activación no documentada en el DPA. |
| **OpenAI** | No por defecto. | Logs de abuso hasta 30 días (`/v1/chat/completions`, `/v1/responses`). | Residencia UE en `eu.api.openai.com`, **requiere aprobación** de ZDR/MAM y +10 %. | — | Con aprobación previa. (https://developers.openai.com/api/docs/guides/your-data) |
| **Groq** | La página no lo dice. | No retiene por defecto; logs de fiabilidad/abuso hasta 30 días. | Datos en GCP **EE. UU.**; SCC. | — | **Autoservicio** en Data Controls. (https://console.groq.com/docs/your-data) |
| **OpenRouter** | Filtra proveedores que entrenan salvo que actives el interruptor. | No registra prompts/respuestas por defecto. | `eu.openrouter.ai` solo **enterprise** (contactar ventas). | — | `"provider": {"zdr": true}` por petición o en ajustes. |
| **DeepSeek** | Política de privacidad: entrenar es un fin declarado; opt-out por email a privacy@deepseek.com. Términos de la plataforma: no lo aclaran para la API. | "for as long as you have an account". | **China**: "process and store your Personal Data in People's Republic of China". | Sin DPA. Ley aplicable: RPC (Hangzhou). Representante UE: Prighter. | No. |

Fuentes DeepSeek: https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html (actualizada 10-feb-2026), https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html (en vigor 29-abr-2026).

**Lectura práctica**: Anthropic y Mistral son las dos opciones sostenibles. Anthropic gana en salida estructurada garantizada y calidad; Mistral gana en jurisdicción (UE). DeepSeek queda fuera por defecto para este tipo de dato.

---

## 5. ¿Embeddings o FTS + expansión con LLM?

**Decisión: v1 sin embeddings.** Razones:

1. Las preguntas típicas ("¿qué me dijo Ana del presupuesto?", "¿qué tengo que hacer mañana?", "¿cuándo hablé con el fontanero?") tienen **nombres propios, fechas y términos concretos**: FTS con BM25 los recupera bien si el LLM expande la consulta (sinónimos, variantes, prefijos `reuni*`) y extrae el **rango de fechas**.
2. Ya existen **resúmenes por conversación y diarios** generados por el LLM: buscar en resúmenes (texto denso y normalizado) suple buena parte de lo que aporta la búsqueda semántica.
3. Las tareas y los facts están en **tablas estructuradas** (no hace falta buscarlos semánticamente).
4. Anthropic no tiene embeddings propios; añadirlos es otro proveedor, otra clave y otro encargado del tratamiento.

Pipeline de pregunta:
1. **Expansión** (Haiku 5.5, structured output): `{terminos_fts: [...], desde, hasta, tipo: "hecho|tarea|conversacion|resumen"}` a partir de la pregunta + fecha/hora actual.
2. **Recuperación local**: FTS (BM25) sobre segmentos de transcripción, resúmenes de conversación y resúmenes diarios, filtrado por fecha; + facts y tareas relevantes por SQL. Tope ≈ 8.000 tokens.
3. **Respuesta** (Sonnet 5.5) citando IDs (`[c123]`) que la app enlaza a la transcripción original con hora.

Nota técnica (fuera de este informe, verificar en el de almacenamiento): el SQLite del sistema Android no garantiza FTS5; FTS4 sí está disponible, o empaquetar SQLite propio. Tokenizador con `remove_diacritics` para español.

**Cuándo añadir embeddings** (si en uso real fallan preguntas parafraseadas, p. ej. "lo del coche" cuando se dijo "la ITV"):
- **En el dispositivo, sin enviar nada fuera**: EmbeddingGemma (v1: 308 M parámetros, 100+ idiomas, contexto 2 K, < 200 MB de RAM cuantizado, Matryoshka 768→128; v2: 740 M, 8.192 tokens; Apache 2.0) — https://ai.google.dev/gemma/docs/embeddinggemma. No he verificado el runtime Android concreto (LiteRT/MediaPipe).
- **En la nube**: `voyage-4-lite` 0,02 $/MTok con 200 M tokens gratis (https://docs.voyageai.com/docs/pricing) — a 1,5–2 M tokens/mes es gratis años; `text-embedding-3-small` 0,02 $/MTok; `mistral-embed` 0,10 $/MTok (no verificado).
- Búsqueda vectorial: fuerza bruta sobre vectores int8 de 256 dimensiones basta (≈ 100 k fragmentos/año ≈ 25 MB); búsqueda híbrida BM25 + coseno con fusión por rango recíproco.

---

## 6. Cliente HTTP mínimo

Dos formatos. Una interfaz interna `Llm.completeJson(route, system, user, schema): JsonObject + Usage` con dos implementaciones. Sin streaming en segundo plano (respuestas cortas); streaming solo en la pantalla de preguntas si se quiere UX.

Opción SDK oficial para Claude en Kotlin/Android: `com.anthropic:anthropic-java` **2.72.0** (Maven Central, publicada hace ~1 día a fecha de hoy; requisito Java 8+; el README **no menciona Android**). Si se prefiere no arrastrar el SDK (tamaño del APK, dependencias), el formato crudo de abajo es pequeño y estable. Decidirlo en la fase de implementación midiendo el tamaño del APK.

### 6.1 Anthropic Messages API (nativa)

```
POST https://api.anthropic.com/v1/messages
x-api-key: <clave>
anthropic-version: 2023-06-01
content-type: application/json
```

Petición (procesado de una conversación con Haiku 5.5):

```json
{
  "model": "claude-haiku-5-5",
  "max_tokens": 4096,
  "system": [
    {
      "type": "text",
      "text": "<<PROMPT_EXTRACCION_V1: instrucciones fijas + ejemplos>>",
      "cache_control": {"type": "ephemeral"}
    }
  ],
  "messages": [
    {
      "role": "user",
      "content": "<perfil>...facts del usuario...</perfil>\n<meta>{\"inicio\":\"2026-10-15T10:32:00+02:00\",\"fin\":\"2026-10-15T10:51:00+02:00\",\"zona\":\"Europe/Madrid\"}</meta>\n<transcripcion>...</transcripcion>"
    }
  ],
  "output_config": {
    "effort": "low",
    "format": {
      "type": "json_schema",
      "schema": { "...": "ver §6.3" }
    }
  }
}
```

Respuesta (forma):

```json
{
  "id": "msg_...",
  "type": "message",
  "role": "assistant",
  "model": "claude-haiku-5-5",
  "content": [
    {"type": "thinking", "thinking": "", "signature": "..."},
    {"type": "text", "text": "{\"titulo\": \"...\", ...}"}
  ],
  "stop_reason": "end_turn",
  "stop_details": null,
  "usage": {
    "input_tokens": 2800,
    "cache_creation_input_tokens": 0,
    "cache_read_input_tokens": 2000,
    "output_tokens": 760
  }
}
```

Reglas del cliente (verificadas):
- Leer el **primer bloque `type == "text"`**; ignorar bloques `thinking` (en 5.5 vienen vacíos por defecto, `display: "omitted"`).
- Comprobar `stop_reason` antes de parsear: `"refusal"` (200, facturado; Haiku 5.5 **no tiene fallback en servidor**) → reintentar una vez con `claude-sonnet-5-5`; `"max_tokens"` → JSON incompleto, reintentar con más `max_tokens`.
- Enums: pueden diferir solo en mayúsculas → comparar sin distinguir mayúsculas.
- **Haiku 5.5**: no enviar `temperature`/`top_p`/`top_k` (valores no por defecto → 400); no prefill (400); `thinking` adaptativo por defecto, effort por defecto `medium` → fijar `"low"` explícito en extracción.
- **Sonnet 5.5 / Opus 5.5**: `tool_choice` forzado (`any`/`tool`) → 400; usar `output_config.format`. Opus 5.5: no se puede desactivar thinking; Sonnet 5.5: `{"type":"disabled"}` → 400.
- Esquema JSON: `additionalProperties: false` obligatorio en objetos; **no soporta** `minimum/maximum`, `minLength/maxLength`, esquemas recursivos; `minItems` solo 0 o 1; formatos `date-time`, `date`, `time`, `duration` sí. La gramática compilada se cachea 24 h; cambiar la **estructura** del esquema la invalida, y cambiar `output_config.format` invalida la caché de prompt del hilo.
- Errores: 429 (respetar `retry-after`), 5xx/529 → reintento exponencial; 400 → no reintentar, registrar (sin contenido de usuario en logs).
- Total de entrada = `input_tokens + cache_read_input_tokens + cache_creation_input_tokens`.

Fuentes: https://platform.claude.com/docs/en/build-with-claude/structured-outputs, https://platform.claude.com/docs/en/build-with-claude/prompt-caching.

**Por qué no la capa OpenAI-compatible de Anthropic** (`https://api.anthropic.com/v1/`): la doc la define como "not considered a long-term or production-ready solution"; `response_format` y `strict` se **ignoran**; "Prompt caching is not supported"; `inference_geo` no disponible. https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk

### 6.2 `chat/completions` OpenAI-compatible (Mistral, OpenAI, DeepSeek, Groq, OpenRouter)

```
POST {base}/chat/completions
Authorization: Bearer <clave>
Content-Type: application/json
```

| Proveedor | `base` | JSON | Caché |
|---|---|---|---|
| Mistral | `https://api.mistral.ai/v1` | `json_schema` + `strict: true` | `prompt_cache_key` (10 % del precio de entrada) |
| OpenAI | `https://api.openai.com/v1` (UE: `https://eu.api.openai.com/v1`, requiere aprobación) | `json_schema` + `strict` | automática |
| DeepSeek | `https://api.deepseek.com` | solo `{"type":"json_object"}` + la palabra "json" y un ejemplo en el prompt; "may occasionally return empty content" | automática |
| Groq | `https://api.groq.com/openai/v1` (de memoria, **no verificado** hoy) | no verificado | no documentada |
| OpenRouter | `https://openrouter.ai/api/v1` (de memoria, **no verificado** hoy) | depende del proveedor | depende del proveedor |

Petición (Mistral):

```json
{
  "model": "mistral-small-2603",
  "messages": [
    {"role": "system", "content": "<<PROMPT_EXTRACCION_V1>>"},
    {"role": "user", "content": "<perfil>...</perfil>\n<meta>...</meta>\n<transcripcion>...</transcripcion>"}
  ],
  "response_format": {
    "type": "json_schema",
    "json_schema": {"name": "conversacion", "schema": { "...": "ver §6.3" }, "strict": true}
  },
  "max_tokens": 2048,
  "prompt_cache_key": "mysone-extraccion-v1"
}
```

Petición (DeepSeek, extracción sin razonamiento):

```json
{
  "model": "deepseek-flash",
  "messages": [
    {"role": "system", "content": "<<PROMPT_EXTRACCION_V1 + 'Responde solo en json con este formato: {...ejemplo...}'>>"},
    {"role": "user", "content": "..."}
  ],
  "response_format": {"type": "json_object"},
  "thinking": {"type": "disabled"},
  "max_tokens": 2048
}
```

Respuesta (forma común):

```json
{
  "id": "...",
  "object": "chat.completion",
  "model": "...",
  "choices": [
    {"index": 0, "finish_reason": "stop",
     "message": {"role": "assistant", "content": "{\"titulo\": \"...\"}"}}
  ],
  "usage": {"prompt_tokens": 4800, "completion_tokens": 600, "total_tokens": 5400}
}
```

- DeepSeek añade `message.reasoning_content` (si hay thinking) y `usage.prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`. Con `tools`, hay que devolver el `reasoning_content` previo o da 400.
- `finish_reason == "length"` → JSON truncado: reintentar. Contenido vacío (DeepSeek) → reintentar una vez.
- Validar siempre el JSON contra el esquema en el cliente en este formato (solo `json_object` no garantiza la forma).

DeepSeek también ofrece endpoint Anthropic (`https://api.deepseek.com/anthropic`), pero ahí `output_config` solo admite `effort` y `cache_control` se ignora: **no** sirve para reutilizar el cliente Anthropic con structured outputs (https://api-docs.deepseek.com/guides/anthropic_api).

### 6.3 Esquema de extracción (compatible con las restricciones de Anthropic)

```json
{
  "type": "object",
  "properties": {
    "descartable": {"type": "boolean", "description": "true si es ruido, TV o sin contenido útil"},
    "titulo": {"type": "string"},
    "resumen": {"type": "string"},
    "temas": {"type": "array", "items": {"type": "string"}},
    "personas": {"type": "array", "items": {"type": "string"}},
    "tareas": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "texto": {"type": "string"},
          "vence": {"anyOf": [{"type": "string", "format": "date-time"}, {"type": "null"}]},
          "recordar_en": {"anyOf": [{"type": "string", "format": "date-time"}, {"type": "null"}]},
          "confianza": {"type": "string", "enum": ["alta", "media", "baja"]},
          "cita": {"type": "string"}
        },
        "required": ["texto", "vence", "recordar_en", "confianza", "cita"],
        "additionalProperties": false
      }
    },
    "hechos": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "hecho": {"type": "string"},
          "categoria": {"type": "string", "enum": ["persona", "preferencia", "salud", "trabajo", "lugar", "fecha", "otro"]},
          "cita": {"type": "string"}
        },
        "required": ["hecho", "categoria", "cita"],
        "additionalProperties": false
      }
    }
  },
  "required": ["descartable", "titulo", "resumen", "temas", "personas", "tareas", "hechos"],
  "additionalProperties": false
}
```

- Fechas en ISO 8601 con desfase (`+02:00`), resueltas por el modelo contra `meta.inicio`; la app valida con `java.time` y descarta fechas absurdas (pasado lejano).
- `cita` = fragmento literal que justifica la tarea/hecho: permite mostrar la evidencia y reduce alucinaciones.
- El esquema es **constante** (versionado con el prompt): cambiarlo invalida gramática y caché.

---

## 7. Diseño de prompts estables (acierto de caché)

Prioridad (coincide con la nota global del dueño): **prefijo estable ≫ modelo ≫ hora**.

Orden de render en Anthropic: `tools` → `system` → `messages`. Cualquier byte distinto invalida todo lo posterior.

1. **Bloque 1 (`system`, con `cache_control`)**: instrucciones + reglas de fechas + descripción de campos + 2 ejemplos. Texto **constante en el código** (`PROMPT_EXTRACCION_V1`), nada interpolado: sin fecha actual, sin nombre de dispositivo, sin IDs. ≥ 512 tokens (mínimo cacheable de los modelos 5.5); con los ejemplos sale ~2.000.
2. **Bloque 2 (opcional, segundo breakpoint)**: perfil/facts del usuario, que cambian como mucho una vez al día. Serializar de forma **determinista** (orden fijo por ID, sin marcas de tiempo). Si cambia a diario, mejor dejarlo en el mensaje de usuario y no gastar escritura.
3. **Mensaje de usuario (volátil)**: metadatos con la fecha/hora **de la conversación** (no "ahora") y la transcripción. Es lo único que cambia entre llamadas.
4. Mantener constantes por ruta: modelo, `effort`, `thinking`, esquema. Las cachés son **por modelo**: no alternar modelos en la misma ruta.
5. **Ráfagas**: la caché de 5 min se renueva con cada lectura; procesar la cola de golpe (WorkManager cada 30–60 min, o al cargar con Wi-Fi) en lugar de una llamada cada vez que termina una conversación. Si se quiere procesado casi inmediato, la TTL de 1 h (`"ttl": "1h"`, escritura 2×) cuesta céntimos con Haiku.
6. **Preguntas**: `system` fijo (formato de citas, reglas de "no sé"); la fecha actual y el contexto recuperado van en el mensaje de usuario. Conversación de seguimiento **solo añadiendo** mensajes (append-only), con `cache_control` automático a nivel raíz; no reescribir turnos previos (en los modelos 5.5 editar el historial invalida los bloques de thinking y puede dar 400 en cuentas creadas desde el 31-ago-2026).
7. Verificar: `cache_read_input_tokens > 0` a partir de la 2.ª llamada de cada ráfaga. Si sale 0, buscar el invalidador (fecha en el system, JSON desordenado, cambio de esquema).
8. Mistral: mismo prefijo + `prompt_cache_key` fijo por ruta (`mysone-extraccion-v1`, `mysone-pregunta-v1`). DeepSeek: misma estructura (la caché es automática por prefijo; el contenido estable al principio, lo variable al final).

---

## 8. Recomendación final y configuración

- **Por defecto**: Anthropic. Haiku 5.5 para todo lo de segundo plano y la expansión de consulta; Sonnet 5.5 para responder; botón "respuesta profunda" con Opus 5.5. `inference_geo` por defecto (`global`; `us` cuesta +10 % y no aporta nada para un usuario en España).
- **Alternativa UE**: Mistral (`mistral-small-2603` + Medium 3.5), plan Scale o con opt-out de entrenamiento confirmado.
- **Alternativa barata**: DeepSeek `deepseek-flash` solo con consentimiento explícito del dueño (China, entrenamiento, sin DPA), lotes en franja valle.
- Configurable en ajustes: proveedor + modelo por ruta + clave de API guardada en el Keystore de Android (nunca en el repositorio ni en logs).
- Registrar `usage` por llamada y coste acumulado por día en la BD local; alerta si el mes supera un umbral (p. ej. 15 $).

## 9. Lo que no he podido verificar

- Tokens por palabra en español para cada tokenizador (estimado 1,3–1,7).
- Precio oficial de `mistral-embed` y el ID de API exacto de Mistral Medium 3.5 (la ficha muestra `mistral-medium-3-5+2`).
- Ubicación exacta de procesamiento de Mistral (el DPA no la dice; fuentes de terceros: UE por defecto) y su retención en días.
- Si Groq entrena con datos de API; base URLs de Groq y OpenRouter (de memoria).
- Capacidades de `gpt-6-luna` más allá del precio.
- Compatibilidad oficial de `anthropic-java` 2.72.0 con Android.
- Discrepancia de retención de Anthropic: docs ("not retained by default") frente a artículo de privacidad ("within 30 days").

## Fuentes

- Anthropic: https://platform.claude.com/docs/en/about-claude/pricing · https://platform.claude.com/docs/en/build-with-claude/prompt-caching · https://platform.claude.com/docs/en/build-with-claude/structured-outputs · https://platform.claude.com/docs/en/cli-sdks-libraries/libraries/openai-sdk · https://platform.claude.com/docs/en/manage-claude/data-residency · https://platform.claude.com/docs/en/manage-claude/api-and-data-retention · https://privacy.claude.com/en/articles/7996866-how-long-do-you-store-my-organization-s-data · https://privacy.claude.com/en/articles/7996862-how-do-i-view-and-sign-your-data-processing-addendum-dpa · https://www.anthropic.com/legal/data-processing-addendum · https://github.com/anthropics/anthropic-sdk-java · https://central.sonatype.com/artifact/com.anthropic/anthropic-java
- DeepSeek: https://api-docs.deepseek.com/quick_start/pricing · https://api-docs.deepseek.com/guides/kv_cache · https://api-docs.deepseek.com/guides/json_mode · https://api-docs.deepseek.com/guides/thinking_mode · https://api-docs.deepseek.com/guides/anthropic_api · https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html · https://cdn.deepseek.com/policies/en-US/deepseek-open-platform-terms-of-service.html
- Mistral: https://mistral.ai/pricing · https://docs.mistral.ai/models/mistral-small-4-0-26-03 · https://docs.mistral.ai/models/mistral-medium-3-5-26-04 · https://docs.mistral.ai/getting-started/models/models_overview/ · https://docs.mistral.ai/studio/conversations/structured-output/custom · https://legal.mistral.ai/terms/data-processing-addendum · https://help.mistral.ai/en/articles/347614-can-i-opt-out-of-my-input-or-output-data-being-used-for-training (404 al descargar; contenido vía buscador) · repo de docs vía Context7 `/mistralai/platform-docs-public` (ficha de Mistral Large 4, `prompt_cache_key`)
- OpenAI: https://developers.openai.com/api/docs/pricing · https://developers.openai.com/api/docs/guides/your-data
- Groq: https://console.groq.com/docs/models · https://console.groq.com/docs/your-data
- OpenRouter: https://openrouter.ai/docs/faq · https://openrouter.ai/docs/guides/features/zdr · https://openrouter.ai/docs/guides/features/in-region-routing · https://openrouter.ai/blog/announcements/us-in-region-routing
- Embeddings: https://docs.voyageai.com/docs/pricing · https://ai.google.dev/gemma/docs/embeddinggemma

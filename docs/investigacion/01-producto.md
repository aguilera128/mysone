# 01 — Investigación de producto: Bee (Amazon), competidores y MVP solo-móvil

Fecha de la investigación: 15-oct-2026 (el mismo día en que Amazon presentó Bee Frontier y Bee Eclipse).
Alcance: qué hace Bee hoy, qué hacen Limitless, Plaud, Omi, Friend y similares, de qué se quejan los usuarios, y qué conviene construir en un MVP Android solo con el micrófono del móvil, para uso personal en español de España.

> Nota de método: Reddit no se ha podido leer (WebFetch está bloqueado para `reddit.com`, y las búsquedas `site:reddit.com` no devuelven hilos). Las quejas de usuarios salen de reseñas de prensa, blogs de usuarios de largo plazo, Trustpilot, el tablero de feedback de Omi y Hacker News. Si algo no se ha podido verificar, se dice.

---

## 1. Resumen ejecutivo

- **"Bee Eclipse" existe y es de hoy.** Es un **accesorio de hardware**, no una app: una tarjeta de 4,6 mm que se pega por imán (MagSafe / Qi2) a la espalda del móvil, con más de 21 días de batería, desde **99,99 $**, "disponible pronto". Junto a ella sale **Bee Frontier** (pulsera o clip, 74,99 $, ya a la venta, sustituye a la Pioneer) y una suscripción **Bee Pro de 11,99 $/mes**.
- Bee es **solo para EE. UU.** (se vende en Amazon.com). Tiene app Android (`com.bee.android`, desarrollador "Bluush Inc.", más de 5.000 descargas, actualizada el 15-oct-2026), pero ninguna fuente dice que el servicio funcione en España.
- El **núcleo funcional** que se repite en todos los productos: captura → transcripción → **conversaciones segmentadas con título y resumen** → **tareas sugeridas** → **resumen del día** → **hechos o memorias sobre el usuario** → **chat que responde sobre lo escuchado**. Bee añade insights y patrones, plantillas, notas de voz, marcadores y acciones (borradores de email, invitaciones de calendario).
- **Las quejas repetidas** en toda la categoría: no distingue al dueño de otros hablantes ni de la tele (llega a escribir "fanfiction" sobre la vida del usuario); demasiados to-dos y "facts" de poca calidad; notificaciones sin control fino; batería real muy por debajo de la anunciada; pérdida silenciosa de audio; privacidad y consentimiento de terceros; suscripciones; y el riesgo de que el servicio cierre (Limitless, comprada por Meta, dejó de funcionar en la UE y el Reino Unido).
- **Recomendación para el MVP:** local-first, captura continua con estado siempre visible, transcripción en el dispositivo, LLM solo sobre texto, **"Yo / Otros"** mediante enrolamiento de voz, **bandeja de sugerencias con límite** (nada se añade solo), **chat con citas que se pueden tocar** y que te llevan al punto exacto de la transcripción, y resumen diario. Evitar el aluvión de facts, el deslizar tipo Tinder para revisarlos, las compras y los permisos excesivos en el onboarding.

---

## 2. Bee: historia y estado a 15-oct-2026

| Fecha | Hito | Fuente |
|---|---|---|
| 2024–2025 | **Bee Pioneer** (pulsera o clip), 49,99 $. Hay fuentes contradictorias sobre la suscripción: Forbes dice que no había cuota mensual; TechCrunch (nov-2025) dice 19 $/mes y solo iOS. | [Forbes](https://www.forbes.com/sites/forbes-personal-shopper/article/best-ai-wearables/), [TechCrunch nov-2025](https://techcrunch.com/2025/11/21/the-hottest-ai-wearables-and-gadgets-you-can-buy-right-now) |
| mar-2025 | Reseña de The Verge (Victoria Song): no distingue hablantes, confunde la tele con la vida real; el CEO dice que trabajan en "liveness detection". | [Vice resumiendo The Verge](https://vice.com/en/article/this-ai-memory-device-records-your-life-and-gets-most-of-it-wrong) |
| 22-jul-2025 | Amazon anuncia la compra de Bee. | [TechCrunch](https://techcrunch.com/2025/07/22/amazon-acquires-bee-the-ai-wearable-that-records-everything-you-say/), [HN](https://hn.algolia.com/api/v1/search?query=Amazon%20Bee%20wearable&tags=story) |
| ene-2026 (CES) | Demo en el CES. El equipo tiene 8 personas. Funciones nuevas: Actions, Voice Notes, Daily Insights y Templates (despliegue escalonado). | [TechCrunch "Why Amazon bought Bee"](https://techcrunch.com/2026/01/12/why-amazon-bought-bee-an-ai-wearable/), [TechCrunch hands-on](https://techcrunch.com/2026/01/12/hands-on-with-bee-amazons-latest-ai-wearable), [Amazon "new features"](https://www.aboutamazon.com/news/devices/bee-amazon-wearable-ai-device-new-features) |
| 2026 | **Bee Private Compute**: claves en el dispositivo, VMs confidenciales con attestation, Sigstore, auditoría de Trail of Bits. Paper de Amazon Science (2026; el PDF lleva la fecha 09-23-26). | [Amazon](https://www.aboutamazon.com/news/devices/bee-private-compute-data-protection), [Amazon Science](https://www.amazon.science/publications/bee-private-compute-privacy-preserving-intelligence-for-bee), [charla AI Engineer](https://ai.engineer/talks/privacy-preserving-intelligence-steve-korshakov-bee-acq-amazon) |
| **15-oct-2026** | **Bee Frontier** (74,99 $, ya a la venta) + **Bee Eclipse** (99,99 $, "pronto") + **Bee Pro** (11,99 $/mes) + app de escritorio (Mac y Windows, con Pro). | [Amazon](https://www.aboutamazon.com/news/devices/amazon-personal-ai-bee-frontier-bee-eclipse), [bee.computer/products/eclipse](https://bee.computer/products/eclipse), [Engadget](https://www.engadget.com/2287245/bee-frontier-bee-eclipse-bee-pro-launch/) |

### 2.1 Hardware actual

| | Bee Frontier | Bee Eclipse |
|---|---|---|
| Forma | Pulsera de silicona o clip para la ropa | Tarjeta de 4,6 mm que se pega por imán al móvil (MagSafe; anillo metálico para fundas; Qi2 en Android según [How-To Geek](https://www.howtogeek.com/amazon-bee-frontier-eclipse-ai/)) |
| Micrófonos | 2, con filtrado de ruido | 3 según Engadget; el diagrama de la página oficial marca 4 posiciones (sin confirmar) |
| Batería | Hasta 7 días, USB-C | Más de 21 días ("3 semanas"), USB-C |
| Resistencia | IP67 | No consta |
| Precio | 74,99 $ | Desde 99,99 $ |
| Botón | Pulsar = empezar/parar; mantener = preguntar al asistente o nota de voz | Pulsar = empezar/parar; mantener = nota de voz; **doble pulsación = marcar un momento** |
| Autoapagado | Tras X minutos sin sonido (5 o 15 min) | Ajustable de 5 min a 24 h sin voz |
| Llamadas | — | "Both sides of every phone call, remembered" (las dos partes de cada llamada). **No se documenta cómo las capta.** Pide que obtengas el permiso de los demás participantes cada vez que empieza a grabar. |
| Idiomas | — | "40+" (la misma página también dice "up to 40") |

Lectura para nuestro caso: **Eclipse es justo lo que el usuario quiere replicar con una app**, pero Amazon lo resuelve con hardware. Así esquiva tres límites del móvil: la batería, las restricciones de Android para usar el micrófono en segundo plano y la imposibilidad de que una app normal capture el audio de las llamadas (ver §6).

### 2.2 Funciones de Bee, una a una

| Función | Cómo lo hace Bee | Fuente |
|---|---|---|
| **Grabación continua** | No escucha por defecto. Un botón la activa y un LED (blanco en Eclipse, verde en la Pioneer) indica que está activa. Se apaga sola tras un periodo de silencio. | [Amazon](https://www.aboutamazon.com/news/devices/amazon-personal-ai-bee-frontier-bee-eclipse), [Eclipse](https://bee.computer/products/eclipse), [TechCrunch hands-on](https://techcrunch.com/2026/01/12/hands-on-with-bee-amazons-latest-ai-wearable) |
| **Transcripción** | En tiempo real. **No guarda el audio**: "processed instantly and then deleted". Por eso no se puede volver a escuchar para comprobar la precisión, que es una crítica explícita de TechCrunch. No se ha podido verificar dónde corre el ASR (en el móvil o en el servidor). | [Eclipse](https://bee.computer/products/eclipse), [TechCrunch](https://techcrunch.com/2026/01/12/why-amazon-bought-bee-an-ai-wearable/) |
| **Segmentación en conversaciones** | Cada conversación se divide en **secciones temáticas**, cada una con su resumen y **un fondo de color distinto**. Al tocar una sección se ve su transcripción exacta. Campos de una conversación (CLI oficial): `id, state, title, short_summary, start_time, end_time, device_type`, ubicación principal. | [TechCrunch hands-on](https://techcrunch.com/2026/01/12/hands-on-with-bee-amazons-latest-ai-wearable), [bee-cli](https://github.com/bee-computer/bee-cli) |
| **Línea de tiempo** | "Your timeline of memories": vista diaria de conversaciones, lugares y conclusiones. | [bee.computer](https://bee.computer) |
| **Resumen diario** | "Daily Memories": resumen del día con temas y eventos clave. Campos en la CLI: `date_time, conversations_count`, resumen corto y largo, **resumen de email y calendario**, `locations`, `conversations`. En el plan nuevo, "daily memories" es de Pro. | [mikecann.blog](https://mikecann.blog/posts/always-listening-a-month-with-the-bee-computer-ai-assistant), [bee-cli](https://github.com/bee-computer/bee-cli), [Amazon](https://www.aboutamazon.com/news/devices/amazon-personal-ai-bee-frontier-bee-eclipse) |
| **Facts / memorias** | Hechos sobre el dueño (preferencias, relaciones, proyectos, compromisos). Campos: `text, confirmed, tags`. El usuario los confirma o rechaza; en 2025 se hacía **deslizando a izquierda o derecha**, un sistema muy criticado. Unos 25 facts al día por revisar, a menudo erróneos. | [Vice/The Verge](https://vice.com/en/article/this-ai-memory-device-records-your-life-and-gets-most-of-it-wrong), [mikecann](https://mikecann.blog/posts/always-listening-a-month-with-the-bee-computer-ai-assistant), [bee-cli](https://github.com/bee-computer/bee-cli) |
| **To-dos** | Sugeridos a partir de lo que dices, que puedes **aceptar o descartar** (`suggestions`, `accept-suggestion`, `dismiss-suggestion`). Campos: `text, completed, alarmAt`. Es la función mejor valorada ("Automatic To-dos That Actually Work!"), aunque llega a unos 15 al día. | [mikecann](https://mikecann.blog/posts/always-listening-a-month-with-the-bee-computer-ai-assistant), [bee-cli](https://github.com/bee-computer/bee-cli) |
| **Recordatorios** | Son to-dos con `alarmAt`. La app de 2025 no tenía control fino de notificaciones: el usuario las apagaba todas y se quedaba también sin recordatorios. | [mikecann](https://mikecann.blog/posts/always-listening-a-month-with-the-bee-computer-ai-assistant) |
| **Chat** | "Ask Bee anything" (en 2025 se llamaba "Buzz"). Responde sobre conversaciones pasadas y también con conocimiento general. En el plan nuevo es de Pro ("personal chat assistant"). **No se ha podido verificar si muestra citas.** | [bee.computer](https://bee.computer), [mikecann](https://mikecann.blog/posts/always-listening-a-month-with-the-bee-computer-ai-assistant) |
| **Insights / patrones** | "Daily Insights": tendencias de ánimo, cambios en las relaciones, temas que se repiten semana a semana, y objetivos sugeridos ("like a coach who actually knows their life"). Hay una sección "Grow". | [Amazon new features](https://www.aboutamazon.com/news/devices/bee-amazon-wearable-ai-device-new-features), [TechCrunch hands-on](https://techcrunch.com/2026/01/12/hands-on-with-bee-amazons-latest-ai-wearable) |
| **Notas de voz** | Con una pulsación larga dictas una nota. En la CLI se llaman `journals`, con `text`, `state` y `aiResponse` (`message`, `cleanedUpText`, `followUp`, `todos`): la IA **limpia el texto**, propone un seguimiento y extrae tareas. | [bee-cli](https://github.com/bee-computer/bee-cli) |
| **Marcadores** | La doble pulsación marca un momento o fuerza a procesar la conversación en curso (se configura en la app). | [TechCrunch hands-on](https://techcrunch.com/2026/01/12/hands-on-with-bee-amazons-latest-ai-wearable), [Eclipse](https://bee.computer/products/eclipse) |
| **Plantillas** | Resúmenes según el contexto: clase, plan de cuidados, lluvia de ideas, siguientes pasos con un cliente. | [Amazon](https://www.aboutamazon.com/news/devices/amazon-personal-ai-bee-frontier-bee-eclipse) |
| **Acciones** | Borradores de email e invitaciones de calendario a partir de lo hablado. | [Amazon](https://www.aboutamazon.com/news/devices/amazon-personal-ai-bee-frontier-bee-eclipse) |
| **Reconocimiento de la voz del dueño** | Entrenamiento de voz en el onboarding (FAQ de la Pioneer, citada en las búsquedas; la página da 404 hoy). En la práctica, el usuario tocaba un segmento para confirmar que el hablante era él. Fue **el mayor fallo** según The Verge. Alexa usa voice ID para entregar los insights de Bee. | [TechCrunch hands-on](https://techcrunch.com/2026/01/12/hands-on-with-bee-amazons-latest-ai-wearable), [Engadget](https://www.engadget.com/2287245/bee-frontier-bee-eclipse-bee-pro-launch/) |
| **Integraciones** | Gmail, Google Calendar, Outlook, Apple HealthKit, Fotos, contactos, Alexa+ y Amazon.com (recomendaciones de compra, opt-in). También una CLI y un **servidor MCP** oficiales, con búsqueda por palabra clave y `--neural`. | [Amazon](https://www.aboutamazon.com/news/devices/amazon-personal-ai-bee-frontier-bee-eclipse), [bee-cli](https://github.com/bee-computer/bee-cli), [bee-skill](https://github.com/bee-computer/bee-skill) |
| **Ubicación** | `locations clusters/recent/current`; el timeline incluye lugares. | [bee-cli](https://github.com/bee-computer/bee-cli) |

### 2.3 Privacidad y controles de Bee

- El audio se procesa en memoria y se borra; las transcripciones y los resúmenes se guardan **cifrados con claves que crea y controla el dispositivo**. Amazon dice no tener una clave maestra.
- Antes de enviar nada, el móvil verifica (attestation) que el servidor ejecuta software aprobado y publicado en un log de transparencia (Sigstore). Las claves que se envían al servidor **caducan a los 7 días** (24 h les pareció demasiado poco). Unas 20.000 líneas de código propio. Inferencia propia, sin proveedores externos de modelos. ([charla](https://ai.engineer/talks/privacy-preserving-intelligence-steve-korshakov-bee-acq-amazon), [paper](https://www.amazon.science/publications/bee-private-compute-privacy-preserving-intelligence-for-bee))
- Hay un botón de encendido y apagado con LED, autoapagado por silencio, borrado de transcripciones y resúmenes cuando quieras, revocación de permisos y, en Eclipse, un aviso para pedir permiso a los demás en las llamadas.
- Dato útil para dimensionar: **unos 10 millones de tokens capturados por usuario al año** (≈27.000 tokens/día), según el resumen de la charla de Korshakov. También cuenta que las revelaciones muy sensibles suelen aparecer en la primera semana de uso.

### 2.4 Modelo de negocio

- Hardware barato (74,99 $ / 99,99 $) más una suscripción.
- **Gratis con la compra:** resúmenes de conversación, recaps y **14 días de memoria**.
- **Bee Pro, 11,99 $/mes:** resúmenes ilimitados, integraciones con Amazon.com, Alexa+ y apps de terceros, to-dos sugeridos, daily memories, chat personal, plantillas y la app de escritorio. Prueba gratuita de 30 días. Los clientes antiguos conservan todas las funciones gratis para siempre (según Maria Zollo en [Engadget](https://www.engadget.com/2287245/bee-frontier-bee-eclipse-bee-pro-launch/)).
- Hay monetización indirecta: **recomendaciones de productos de Amazon a partir de tus conversaciones** (opt-in). Gizmodo se lo toma con sarcasmo ([Gizmodo](https://gizmodo.com/amazon-bee-eclipse-frontier-2000826303)), y How-To Geek llama a los aparatos "subscription vehicles" ([How-To Geek](https://www.howtogeek.com/amazon-bee-frontier-eclipse-ai/)).

---

## 3. Competidores y similares

| Producto | Estado (oct-2026) | Precio | Qué aporta / patrón destacable | Pegas conocidas |
|---|---|---|---|---|
| **Limitless Pendant** (antes Rewind) | Comprada por **Meta** el 5-dic-2025. Ya no se vende. Soporte a los clientes existentes "al menos un año". Los suscriptores pasan gratis al plan Unlimited. Rewind cerró y **el servicio dejó de funcionar en la UE y el Reino Unido**. | Antes: 99 $; Free 20 h/mes, Pro 19 $/mes (100 h), Unlimited 29 $/mes | "Lifelogs" cronológicos con títulos y resúmenes. **Ask AI enlaza las respuestas a los lifelogs de origen.** Prompts programados que llegan como notificación diaria. **Consent Mode**: suena un aviso y deja de grabar a quien no ha dado su consentimiento. Speaker ID tras ≥20 s de audio etiquetado a mano. Buffer offline de 35 h. Exportación y API REST. | Speaker ID inconsistente; batería por debajo de lo prometido; vendor lock-in demostrado. ([limitless.ai](https://limitless.ai/), [Betanews](https://betanews.com/2025/12/08/meta-has-acquired-limitless-a-startup-that-produces-ai-powered-wearables/), [layer3labs](https://www.layer3labs.io/gear/reviews/limitless-pendant), [yespress](https://yespress.io/products/limitless.md)) |
| **Plaud NotePin / NotePin S** | A la venta. La NotePin S salió en 2026. | NotePin 149–159 $; NotePin S 179 $. Starter gratis con 300 min/mes; Pro 17,99 $/mes o 99,99 $/año (1.200 min); Unlimited 29,99 $/mes o 239,99 $/año. **Los minutos no se acumulan de un mes a otro.** | **Graba al pulsar y conserva el audio** (64 GB). 112 idiomas. Etiquetas de hablante. Más de 10.000 plantillas. Mapas mentales. "Ask Plaud" (beta). Exporta a MP3, WAV, TXT, SRT, MD, DOCX, PDF y PNG. | La suscripción acaba costando más que el aparato (TechRadar); funciones solo en Pro. ([TechRadar](https://www.techradar.com/ai-platforms-assistants/claude/plaud-notepin-bundle-review), [affiliatebooster](https://www.affiliatebooster.com/plaud-ai-pricing/), [Plaud blog](https://uk.plaud.ai/blogs/news/updates-label-the-speakers-and-create-your-own-templates)) |
| **Omi** (Based Hardware) | A la venta. Todo **open source (MIT)**: app en Flutter, backend en FastAPI con Firestore, Deepgram, Redis y diarizador en GPU. ~13,7k estrellas. | Colgante a 179 $ (129 $ con descuento). **Plan gratis: transcripción ilimitada en el móvil + 300 min de nube al mes.** Unlimited 269 $/año. | Conversations, Memories, Action items, Chat. Más de 250 apps en su tienda y más de 22 integraciones. Self-hosting. **Transcripción en el móvil.** Perfiles de voz. | Pérdida de audio si se desconecta; transcripciones con "caracteres de otros idiomas" cuando el idioma principal es el portugués; perfiles de voz con nombres basura; batería de un día. ([docs.omi.me](https://docs.omi.me/), [GitHub](https://github.com/BasedHardware/omi), [omi.me/pricing](https://www.omi.me/pricing), [feedback](https://omi.featurebase.app/p/data-loss-during-app-disconnection), [feedback idiomas](https://omi.featurebase.app/p/mixedweird-language-transcriptions)) |
| **Friend** (Avi Schiffmann) | A la venta, con ventas bajas según el Museum of Failure. | 129 $ | Compañero emocional que escucha siempre y responde por texto en el móvil. | Personalidad "condescendiente" (Wired); capta mal el audio; rechazo social (sus anuncios en el metro de Nueva York aparecieron pintados). **Ejemplo de qué no hacer.** ([Verdict](https://www.verdict.co.uk/ai-friend-loneliness-epidemic/), [Museum of Failure](https://museumoffailure.com/exhibition/friend-ai), [KVIA/CNN](https://kvia.com/news/business-technology/cnn-business-consumer/2025/11/16/how-this-tiny-device-became-a-symbol-for-the-backlash-against-ai/)) |
| **Pebble Index 01** (Core Devices) | Anunciado en dic-2025; envíos previstos para mar-2026 (no he podido verificar el estado actual). | 75 $ en reserva, 99 $ después; pila no recargable de 12–15 h de grabación. | Notas de voz al pulsar. **STT y LLM locales en la app open source del móvil**, sin suscripción; nube opcional. | Sin escucha continua. ([Techlicious](https://www.techlicious.com/blog/pebble-index-01-ring-recorder-launch/), [mjtsai](https://mjtsai.com/blog/2025/12/09/pebble-index)) |
| **Sandbar Stream** (anillo) | Ronda serie A de 23 M$ en mar-2026. Solo EE. UU. e iOS. | — | Micrófono apagado por defecto; se mantiene pulsado para hablar en voz baja; notas de voz. | — ([TechCrunch](https://techcrunch.com/2026/03/10/sandbar-secures-23m-series-a-for-its-ai-note-taking-ring), [Wareable](https://www.wareable.com/wearable-tech/sandbar-ai-smart-stream-ring-voice-notes-announcement)) |
| **Google Pixel "Audio Memory"** | **No lanzado**: solo aparece en el código de Android System Intelligence del Pixel 10 (jun-2026). | — | "Keep track of what you hear throughout your day… your important conversations", **en el dispositivo** (Private Compute Core). Si sale, sería la competencia nativa en Android. | No se sabe si escucha siempre o hay que activarlo. ([9to5Google](https://9to5google.com/2026/06/22/google-pixel-audio-memory/), [Android Authority](https://www.androidauthority.com/pixel-audio-memory-3680024/)) |

Otros que se citan: SwitchBot AI MindClip a 119,99 $ ([TechRepublic](https://www.techrepublic.com/article/news-best-wearable-ai-note-takers-2026/)) y Screenpipe (captura de pantalla y audio en el escritorio) ([screenpipe](https://screenpipe.com/blog/best-ai-wearables-memory-2026)). Según la búsqueda hecha, no he encontrado ninguna app Android publicada que grabe y transcriba de forma continua solo con el micrófono del móvil. Omi dice que su app transcribe en el móvil, pero no he podido verificar si lo hace en segundo plano o con la pantalla bloqueada.

---

## 4. Qué se quejan los usuarios (por temas)

| Queja | Evidencia | Implicación para nosotros |
|---|---|---|
| **No distingue al dueño de los demás ni de la tele o la música** | The Verge/Vice: "can't differentiate between your voice and anyone else's"; un argumento de *Abbott Elementary* acabó guardado como hecho sobre la autora ("fanfiction"). mikecann: podcasts y tele tratados como conversación. | Enrolamiento de voz para separar Yo y Otros; los facts **solo a partir de lo que dice el dueño**; detectar cuándo suena contenido multimedia. |
| **Inferencias absurdas y facts inventados** | Bee "convencida" de que la periodista tenía un paciente en Luisiana; ~25 facts al día "a menudo sin sentido" (mikecann). | Umbral de confianza alto, límite diario y cita literal de origen en cada fact. |
| **Demasiadas sugerencias** | ~15 to-dos al día sin prioridad (mikecann). | Bandeja con tope (p. ej., 5 al día), prioridad y agrupación. |
| **Notificaciones sin control fino** | El usuario lo apaga todo y pierde también los recordatorios (mikecann). | Un canal de notificación de Android por tipo (recordatorios, resumen diario, sugerencias, estado). |
| **Batería** | Bee: anuncia 7 días, en la práctica unos 2; además **se notaba en la batería del móvil** (mikecann; layer3labs: 1,5–2 días). Omi: un día. | Medir y mostrar el consumo; procesar en lotes cuando el móvil carga. |
| **Pérdida silenciosa de datos** | Omi: huecos en la transcripción tras una desconexión; Trustpilot de Bee: "no conversations ever actually logged". | Mostrar los huecos en el timeline con su motivo; cola persistente y reintentos. |
| **Idioma mezclado** | Omi con portugués: "weird characters from other languages". | Fijar es-ES y no autodetectar el idioma de cada fragmento. |
| **Sin audio no hay forma de comprobar** | TechCrunch: descartar el audio lo hace "a non-starter" para usos que requieren volver a escucharlo. | Conservar el audio cifrado de forma opcional durante N días. |
| **Privacidad, terceros y momentos íntimos** | The Verge: se dio cuenta de que la escuchaba "during extremely private moments"; Warren Ellis la guarda en una bolsa Faraday. | Pausa a un toque, pausas por horario o lugar, estado siempre visible. |
| **Onboarding pide demasiado** | Bee pedía calendario, contactos, email y notas desde el principio (mikecann). | Pedir permisos poco a poco, cuando hagan falta. |
| **Compras y "creepiness"** | Recomendaciones de Amazon a partir de las conversaciones (Gizmodo); Lactaid sugerido tras una broma sobre el estómago (The Verge). | Nada de anuncios ni compras. |
| **Suscripción y minutos** | Plaud: los minutos caducan cada mes y la suscripción cuesta más que el aparato; Bee: "subscription vehicles". | Uso personal: coste controlado y visible. |
| **Cierre del proveedor** | Limitless dejó de funcionar en la UE y el Reino Unido tras la compra; Humane como precedente. | Local-first y exportación abierta (JSON/Markdown). |
| **Hardware frágil y soporte** | Correa de la Pioneer que se abre sola; envíos tardíos y aparatos que no se conectan (Trustpilot). | No aplica (sin wearable), pero aplica a la fiabilidad del servicio en segundo plano. |

Fuentes: [Vice/The Verge](https://vice.com/en/article/this-ai-memory-device-records-your-life-and-gets-most-of-it-wrong), [mikecann.blog](https://mikecann.blog/posts/always-listening-a-month-with-the-bee-computer-ai-assistant), [Trustpilot Bee](https://www.trustpilot.com/review/www.bee.computer), [layer3labs Bee](https://www.layer3labs.io/gear/reviews/bee-ai-pendant), [warrenellis.ltd](https://warrenellis.ltd/?p=16008), [TechCrunch hands-on](https://techcrunch.com/2026/01/12/hands-on-with-bee-amazons-latest-ai-wearable), [Omi feedback](https://omi.featurebase.app/p/l-pendant-not-capturing-all-conversations), [TechRadar Plaud](https://www.techradar.com/ai-platforms-assistants/claude/plaud-notepin-bundle-review).

---

## 5. Lo que hace bien la categoría (y conviene copiar)

1. **Segmentar en lugar de mostrar la transcripción en bruto**: conversación → secciones temáticas con resumen → transcripción exacta bajo demanda (Bee).
2. **Tareas como sugerencias que se aceptan o descartan**, no como inserciones automáticas (Bee).
3. **Notas de voz con limpieza por IA** (`cleanedUpText`, `followUp`, `todos`) (Bee journals).
4. **Respuestas del chat enlazadas a la fuente** (Limitless Ask AI → lifelogs).
5. **Prompts programados**: una pregunta favorita que llega cada día como notificación (Limitless).
6. **Marcador de momento** con un gesto (doble pulsación en Bee) y **autopausa por silencio**.
7. **Exportación abierta y API/MCP** (CLI y MCP de Bee, API REST de Limitless).
8. **Transcripción en el dispositivo como base gratuita** (Omi, Pebble Index).

---

## 6. Restricciones de Android que condicionan la UX (verificado en developer.android.com)

- Grabar en segundo plano exige un **foreground service de tipo `microphone`**, el permiso `FOREGROUND_SERVICE_MICROPHONE`, `RECORD_AUDIO` concedido y una **notificación persistente**. `RECORD_AUDIO` está sujeto a restricciones *while-in-use*: **no se puede arrancar ese servicio desde segundo plano ni desde `BOOT_COMPLETED`** (salvo excepciones). Consecuencia para la UX: tras reiniciar el móvil o si el sistema mata el servicio, hay que **avisar al usuario con una notificación y que toque para reanudar**. ([FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types))
- **Dos apps normales no pueden capturar audio a la vez**: desde Android 10, la que pierde el micrófono **recibe silencio** sin que salte ningún error. Las fuentes `VOICE_COMMUNICATION` (WhatsApp, Meet) y `CAMCORDER` tienen prioridad. Se puede detectar con `AudioRecordingCallback` / `isClientSilenced()`. Consecuencia para la UX: un estado **"Micrófono ocupado por otra app"** y huecos marcados en el timeline. ([Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input))
- **Llamadas telefónicas**: solo las capturan apps privilegiadas (preinstaladas, con `CAPTURE_AUDIO_OUTPUT`) o servicios de accesibilidad. Una app instalada a mano no puede prometer "las dos partes de cada llamada" como Eclipse. Como mucho, el altavoz del móvil cuando no hay otra app con prioridad. ([Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input))
- Calidad acústica: el móvil va en el bolsillo o en un bolso. Esto no está medido en ninguna fuente, pero es razonable esperar peor captación que con una pulsera, así que hay que mostrar un **indicador de calidad o nivel de voz**.

---

## 7. Lista priorizada de funciones para el MVP solo-móvil

**P0 — imprescindible para que sea útil y fiable**

1. **Captura continua** en un foreground service, con una notificación persistente con acciones **Pausar (15 min / 1 h / hasta mañana)**, **Marcar momento** y **Nota de voz**. Tile de Ajustes rápidos. El estado se ve siempre: *Escuchando · Pausado · Micrófono ocupado · Sin voz*.
2. **VAD (detección de actividad de voz)** para procesar solo cuando hay voz, más **segmentación en conversaciones** por silencios (umbral configurable, p. ej. 2–5 min, en la línea del autoapagado de 5 min de Bee).
3. **Transcripción en el dispositivo en es-ES** (idioma fijo), con timestamps por frase. Cola persistente y reintentos: ningún fragmento se pierde sin dejar rastro.
4. **Audio**: se borra al transcribir; como opción, **se conserva cifrado N días** (resuelve la queja de TechCrunch). Referencia de tamaño: Opus mono a 16 kbps ≈ **7,2 MB/h**, unos 115 MB al día con 16 h. Es un cálculo propio con la tasa de bits, no un dato de ninguna fuente.
5. **Procesado con IA solo sobre texto** por conversación: título, resumen corto, secciones, tareas candidatas con fecha/hora y hechos candidatos.
6. **Timeline "Hoy"** con navegación por días (ver §8).
7. **Detalle de conversación**: resumen, secciones, transcripción con hablante y hora, y lo que se ha extraído.
8. **Bandeja de tareas sugeridas** (aceptar / editar / descartar) y **recordatorios** con notificación local a la hora indicada.
9. **Chat sobre tu memoria con citas** (RAG, recuperación de fragmentos y respuesta generada con ellos) y filtros de tiempo ("ayer", "la semana pasada"); si no encuentra nada, lo dice.
10. **Búsqueda** de texto completo (y semántica si cuesta poco).
11. **Resumen diario** a una hora configurable, con su notificación.
12. **Privacidad básica**: base de datos local cifrada, borrar una conversación, un día o un rango, exportar a JSON o Markdown, cero telemetría y canales de notificación separados.

**P1 — sube mucho la calidad**

13. **Enrolamiento de voz del dueño** (unos 20–30 s, como el mínimo de 20 s de Limitless) para etiquetar **Yo / Otros**; los facts y compromisos solo salen de "Yo".
14. **Detección de multimedia**: marcar o descartar cuando el propio móvil reproduce audio, y usar el LLM para clasificar "esto es tele, radio o podcast" y excluirlo de los facts (equivale a la "liveness detection" de Bee).
15. **Facts / "Lo que sé de ti"** con revisión por lotes, límite diario y categorías; los sensibles (salud) nunca se confirman solos.
16. **Notas de voz** con texto limpio, seguimiento y tareas (patrón `journals` de Bee).
17. **Añadir al calendario** mediante un Intent `ACTION_INSERT` (sin OAuth) y compartir un resumen.
18. **Prompts programados** ("cada lunes: ¿qué tengo pendiente con clientes?").
19. Widget de inicio con el estado y el último resumen.

**P2 — más adelante**

20. Plantillas de resumen (reunión, médico, clase). 21. Insights y patrones semanales. 22. Personas: etiquetar a otros hablantes y unirlos con los contactos. 23. Pausas automáticas por lugar u horario. 24. Integración con Google Calendar o Tasks por API. 25. Wear OS o micrófono Bluetooth. 26. Un servidor MCP o export para preguntar desde Claude o desde otros agentes (como hace Bee con su CLI).

Fuera de alcance: grabar llamadas (Android no lo permite a apps normales), compras y recomendaciones, compartir con otros usuarios y "personalidad de compañero" estilo Friend.

---

## 8. Patrones de UX concretos

**Pantalla "Hoy" (timeline)**
- Arriba, una tarjeta de estado: punto de color + "Escuchando · 3 h 12 min de voz hoy · última voz hace 2 min" + botón grande **Pausar** con duraciones. Si el micrófono está ocupado o el servicio se ha parado, aparece un banner rojo con la acción "Reanudar".
- Debajo, una lista vertical por hora: cada **conversación** es una tarjeta con título generado, `10:05–10:32 · 27 min`, una línea de resumen y chips (`2 tareas`, `1 recordatorio`, `Oficina` (lugar) si hay ubicación). Los **huecos** son filas grises y finas: "Pausado 14:00–15:00", "Micrófono ocupado (llamada)", "Sin voz". Las notas de voz y los marcadores aparecen como eventos pequeños en su hora.
- Para cambiar de día, una tira horizontal de fechas. En los días pasados, la tarjeta de **resumen diario** encabeza la lista (patrón Daily Memories de Bee).

**Detalle de conversación**
- Cabecera con título editable, fecha, hora de inicio y fin, duración, lugar y participantes ("Yo + 2").
- "Resumen" en 3–5 viñetas.
- "Secciones": bloques temáticos, cada uno con un **fondo tintado distinto** (patrón Bee) y desplegable con su transcripción.
- "Extraído": tareas y facts candidatos con aceptar/descartar en línea y la frase de origen citada.
- Transcripción completa con `hh:mm:ss`, etiqueta **Yo / Otro** (tocar para corregir) y saltos de marcador resaltados.
- Acciones: renombrar, reprocesar, compartir como Markdown, borrar (con confirmación).

**Tareas y recordatorios**
- Dos pestañas: **Sugeridas** (bandeja con un número; cada una con su cita de origen y un enlace a la conversación; botones Aceptar, Editar fecha, Descartar) y **Mis tareas** (Hoy / Próximas / Sin fecha).
- Las fechas relativas ("el jueves a las 5") se resuelven respecto a la hora de la conversación y se muestran ya convertidas antes de aceptar.
- Al aceptar una tarea con fecha se programa la notificación local, que trae acciones Hecho y Posponer.

**Facts / "Lo que sé de ti"**
- Agrupados por categorías: Personas, Trabajo, Preferencias, Salud, Lugares y Rutinas. Cada fact muestra su fuente (conversación y fecha), un estado (sugerido o confirmado) y se puede editar.
- La revisión se hace **por lotes** ("5 nuevos esta semana": Confirmar / Editar / Descartar en una lista), **sin deslizar tipo Tinder** y con tope diario.

**Chat con citas**
- Campo "Pregunta sobre lo que has oído…" con sugerencias ("¿Qué quedé en hacer esta semana?", "¿Qué dijo Ana del presupuesto?", "Resúmeme el martes").
- Chips de filtro temporal (Hoy, 7 días, 30 días, Todo).
- La respuesta lleva citas numeradas `[1]`. Debajo, tarjetas de fuente con el título de la conversación, la fecha y hora y un fragmento de 1–2 líneas; al tocarlas se abre la **transcripción en el timestamp exacto, resaltado**.
- Si no hay pruebas suficientes, la respuesta dice explícitamente "No lo encuentro en tus conversaciones", sin rellenar con conocimiento general salvo que se pida.
- Se puede guardar una pregunta como **prompt programado** (patrón Limitless).

**Resumen diario**
- Notificación a la hora elegida (p. ej., 21:30): "Hoy: 7 conversaciones · 3 tareas nuevas · 1 recordatorio mañana".
- La pantalla muestra el resumen, 3 momentos destacados, las tareas pendientes, los facts por revisar y un enlace a cada conversación.

**Onboarding**
- Explicación de qué se graba y dónde se guarda, con la base legal en una frase → permiso de micrófono → notificaciones → (opcional) exclusión de la optimización de batería → enrolamiento de voz → listo. El resto de permisos (ubicación, calendario) se piden cuando se usa la función correspondiente.

---

## 9. Qué evitar

- **Aluvión de facts y to-dos** (25 y 15 al día en Bee): ponerles tope, puntuación de confianza y un resumen semanal.
- **Atribuir al dueño lo que dicen otros, la tele o la música** ("fanfiction").
- **Revisar facts deslizando tipo Tinder.**
- **Notificaciones en bloque** sin canales por tipo.
- **Pérdidas silenciosas**: todo hueco se muestra con su motivo.
- **Detección automática de idioma** que mezcla alfabetos: fijar es-ES.
- **Prometer más batería de la real**: medir el consumo y mostrarlo en Ajustes.
- **Pedir todos los permisos en el onboarding.**
- **Compras, anuncios o "sugerencias" comerciales.**
- **Grabación invisible**: la notificación persistente es obligatoria y además conviene; añadir una forma rápida de avisar a quien tienes delante.
- **Dependencia de un backend ajeno**: local-first y exportación (Limitless dejó tirados a sus usuarios europeos).
- **Prometer grabar llamadas**: en Android 10 o superior no es posible para una app normal.
- **Respuestas del chat sin fuente.**
- **Legal en España** (no es asesoría jurídica): grabar una conversación en la que participas es en general lícito (STC 114/1984), pero **captar conversaciones ajenas en las que no participas puede encajar en el art. 197.1 CP**, y difundir las grabaciones es otra cuestión (art. 197.3, más la protección de datos: la voz es un dato personal). La exclusión doméstica del RGPD (art. 2.2.c) se debilita si se capta a terceros o el espacio público (criterio de la AEPD en el PA/00023/2024). Por eso: pausa fácil, nada de difundir lo grabado y retención de audio corta u opcional. ([recordinglaw](https://www.recordinglaw.com/es/spain/recording-laws/recording-conversations/), [reclamador](https://www.reclamador.es/blog/es-legal-grabar-una-conversacion-telefonica), [AEPD PA/00023/2024](https://www.aepd.es/documento/pa-00023-2024.pdf))

---

## 10. Modelo de datos sugerido (inspirado en la CLI oficial de Bee)

```
Conversation { id, state(capturing|transcribing|processing|done|failed), title, short_summary,
               sections[{title, summary, start_ms, end_ms}], start_time, end_time, location?, audio_ref? }
Utterance    { id, conversation_id, start_ms, end_ms, speaker(me|other|unknown), text, confidence }
Gap          { start_time, end_time, reason(paused|mic_busy|service_killed|no_speech) }
Fact         { id, text, category, confirmed(bool), source_utterance_ids[], created_at }
Todo         { id, text, due_at?, alarm_at?, state(suggested|accepted|dismissed|done), source_utterance_ids[] }
VoiceNote    { id, created_at, raw_text, cleaned_text, follow_up?, todo_ids[] }
Bookmark     { id, time, conversation_id? }
DailySummary { date, short_summary, full_summary, conversations_count, highlights[] }
```

Referencia: los campos `title, short_summary, start_time, end_time, state` (conversation), `text, confirmed, tags` (fact), `text, completed, alarmAt` (todo), `cleanedUpText, followUp, todos` (journal) y `conversations_count, short/full summary` (daily) vienen de [bee-computer/bee-cli](https://github.com/bee-computer/bee-cli). `source_utterance_ids` es un añadido propio para que las citas se puedan tocar.

---

## 11. Datos no verificados o contradictorios

- Cómo capta Bee Eclipse las dos partes de una llamada: **no está documentado**.
- Número de micrófonos de Eclipse: 3 (Engadget) frente a 4 posiciones en el diagrama oficial.
- Dónde corre el ASR de Bee (en el móvil o en el servidor): no consta. mikecann lo atribuye al móvil, pero es una suposición suya.
- Precio de la suscripción de Bee antes de oct-2026: 19 $/mes (TechCrunch) frente a gratis (Forbes, AllThingsAI).
- Que Bee esté disponible fuera de EE. UU.: ninguna fuente lo dice; se vende en Amazon.com.
- Si el chat de Bee muestra citas: no verificado.
- Estado actual del envío del Pebble Index 01: no verificado.
- Sentimiento en Reddit: no accesible desde las herramientas disponibles.

---

## 12. Fuentes

- Amazon — Bee Frontier y Bee Eclipse: https://www.aboutamazon.com/news/devices/amazon-personal-ai-bee-frontier-bee-eclipse
- Bee Eclipse (producto): https://bee.computer/products/eclipse
- Bee (inicio): https://bee.computer
- Engadget (lanzamiento, Bee Pro): https://www.engadget.com/2287245/bee-frontier-bee-eclipse-bee-pro-launch/
- How-To Geek: https://www.howtogeek.com/amazon-bee-frontier-eclipse-ai/
- Gizmodo: https://gizmodo.com/amazon-bee-eclipse-frontier-2000826303
- 9to5Google (lanzamiento): https://9to5google.com/2026/10/15/amazon-launches-bee-ai-wearables/
- TechCrunch, Why Amazon bought Bee: https://techcrunch.com/2026/01/12/why-amazon-bought-bee-an-ai-wearable/
- TechCrunch, hands-on: https://techcrunch.com/2026/01/12/hands-on-with-bee-amazons-latest-ai-wearable
- TechCrunch, compra (jul-2025): https://techcrunch.com/2025/07/22/amazon-acquires-bee-the-ai-wearable-that-records-everything-you-say/
- TechCrunch, wearables (nov-2025): https://techcrunch.com/2025/11/21/the-hottest-ai-wearables-and-gadgets-you-can-buy-right-now
- Amazon — funciones nuevas de Bee: https://www.aboutamazon.com/news/devices/bee-amazon-wearable-ai-device-new-features
- Amazon — Bee Private Compute: https://www.aboutamazon.com/news/devices/bee-private-compute-data-protection
- Amazon Science — paper: https://www.amazon.science/publications/bee-private-compute-privacy-preserving-intelligence-for-bee
- Charla de Steve Korshakov (AI Engineer 2026): https://ai.engineer/talks/privacy-preserving-intelligence-steve-korshakov-bee-acq-amazon
- bee-cli (modelo de datos): https://github.com/bee-computer/bee-cli
- bee-skill: https://github.com/bee-computer/bee-skill
- Google Play, Bee AI: https://play.google.com/store/apps/details?id=com.bee.android
- Vice resumiendo The Verge: https://vice.com/en/article/this-ai-memory-device-records-your-life-and-gets-most-of-it-wrong
- mikecann.blog (un mes con Bee): https://mikecann.blog/posts/always-listening-a-month-with-the-bee-computer-ai-assistant
- Trustpilot Bee: https://www.trustpilot.com/review/www.bee.computer
- layer3labs (Bee): https://www.layer3labs.io/gear/reviews/bee-ai-pendant
- layer3labs (Limitless): https://www.layer3labs.io/gear/reviews/limitless-pendant
- layer3labs (comparativa): https://www.layer3labs.io/comparisons/limitless-vs-bee-vs-omi
- Limitless: https://limitless.ai/ y https://www.limitless.ai/new
- Betanews (Meta compra Limitless): https://betanews.com/2025/12/08/meta-has-acquired-limitless-a-startup-that-produces-ai-powered-wearables/
- yespress (Limitless): https://yespress.io/products/limitless.md
- Omi docs: https://docs.omi.me/ — GitHub: https://github.com/BasedHardware/omi — Precios: https://www.omi.me/pricing
- Omi feedback: https://omi.featurebase.app/p/data-loss-during-app-disconnection, https://omi.featurebase.app/p/mixedweird-language-transcriptions, https://omi.featurebase.app/p/l-pendant-not-capturing-all-conversations
- Plaud (TechRadar): https://www.techradar.com/ai-platforms-assistants/claude/plaud-notepin-bundle-review
- Plaud (precios): https://www.affiliatebooster.com/plaud-ai-pricing/
- Plaud (etiquetas de hablante y plantillas): https://uk.plaud.ai/blogs/news/updates-label-the-speakers-and-create-your-own-templates
- TechRepublic (wearables 2026): https://www.techrepublic.com/article/news-best-wearable-ai-note-takers-2026/
- Friend: https://www.verdict.co.uk/ai-friend-loneliness-epidemic/, https://museumoffailure.com/exhibition/friend-ai, https://kvia.com/news/business-technology/cnn-business-consumer/2025/11/16/how-this-tiny-device-became-a-symbol-for-the-backlash-against-ai/
- Pebble Index 01: https://www.techlicious.com/blog/pebble-index-01-ring-recorder-launch/, https://mjtsai.com/blog/2025/12/09/pebble-index
- Sandbar: https://techcrunch.com/2026/03/10/sandbar-secures-23m-series-a-for-its-ai-note-taking-ring
- Pixel Audio Memory: https://9to5google.com/2026/06/22/google-pixel-audio-memory/, https://www.androidauthority.com/pixel-audio-memory-3680024/
- Screenpipe: https://screenpipe.com/blog/best-ai-wearables-memory-2026
- Forbes: https://www.forbes.com/sites/forbes-personal-shopper/article/best-ai-wearables/
- Android, tipos de FGS: https://developer.android.com/develop/background-work/services/fgs/service-types
- Android, captura de audio concurrente: https://developer.android.com/media/platform/sharing-audio-input
- Legal en España: https://www.recordinglaw.com/es/spain/recording-laws/recording-conversations/, https://www.reclamador.es/blog/es-legal-grabar-una-conversacion-telefonica, https://www.aepd.es/documento/pa-00023-2024.pdf

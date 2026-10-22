# Auditoría de seguridad, 22-oct-2026

Skill `security-audit` de Cloudflare, perfil `standard`, sobre el commit `41712c3` (árbol limpio). Es la primera ejecución sobre este repositorio. La salida completa está fuera del repositorio, en `C:/Users/aguil/security-audit-skill/mysone/run-1/`:

- `REPORT.md`: ejecución, postura, tabla de pistas, endurecimiento, patrones positivos y cobertura.
- `NEEDS-VALIDATION.md`: traza, evidencia, bloqueos y planes de validación de cada pista.
- `findings.json` y `coverage-ledger.json`: los registros y la cobertura. Las próximas ejecuciones del skill los reutilizan.

## Cómo se hizo

Solo leyendo código. En esta máquina no hay sandbox con aislamiento del sistema operativo, así que no se ejecutó ni un build ni un test ni el emulador. Eso tiene una consecuencia directa: **ningún hallazgo podía salir confirmado**. Lo que respalda el código queda como pista pendiente de validar, cada una con su plan local exacto. La cobertura fue de 52 unidades en 12 olas de caza, cerradas por un crítico limpio. Cada candidato pasó por un verificador independiente y cada registro final, por otra revisión nueva.

## Resultado

- **0 confirmados, 37 pendientes de validar (sin severidad) y 1 rechazado.**
- **Temas que se repiten:**
  - Borrar, «Olvidar» y la retención no alcanzan todas las copias derivadas: citas de tareas, respuestas de Preguntar, facts, resúmenes diarios, capturas e importaciones en curso.
  - Algunos trabajos de IA siguen enviando datos después de revocarla.
  - Pausa, olvido, retención y recordatorios se fían del reloj de pared.
  - Hay trabajo y llamadas de pago sin tope.
  - Cadena de suministro y firma: el AAR de JitPack va sin digest y la release se firma con la clave debug.
- **Bloqueada:** no se pudo comprobar si las dependencias fijadas tienen CVE conocidos, porque hacía falta red.

## Pistas por prioridad de validación

La prioridad solo ordena qué validar antes; no es severidad.

| # | Prioridad | Área | Pista | Traza |
|---|---|---|---|---|
| 1 | alta | Preguntar y citas | Olvidar o borrar una conversación deja las respuestas de Preguntar que copiaron sus fragmentos literales sin citarla | `ListeningService.kt:490 → AskEngine.kt:120` |
| 2 | alta | Captura y servicio | Un salto hacia delante de la hora de red acaba antes de tiempo la pausa de privacidad y vuelve a abrir el micrófono | `ListeningService.kt:187 → ListeningService.kt:299` |
| 3 | alta | Captura y servicio | Los PendingIntent de «Reanudar» reabren el micro sin desbloquear ni caducar cuando los envía otra app | `ListeningNotification.kt:47 → ListeningService.kt:299` |
| 4 | alta | Preguntar y citas | Las citas combinadas ([c:12, 13]) se ven como chips pero no se guardan en citations: al borrar u olvidar la conversación citada, la respuesta de Preguntar se queda y sigue yendo al proveedor de IA | `AskEngine.kt:117 → AskEngine.kt:120` |
| 5 | media | Interfaz | El diálogo de edición de Tareas y Memoria y el borrador de Preguntar sobreviven a «Borrar todo» y «Olvidar» en el estado guardado de las raíces de pestaña | `TasksScreen.kt:212 → TasksScreen.kt:226` |
| 6 | media | IA y proveedor | El procesado por partes y el «Preguntar» planificar→responder siguen enviando datos al proveedor de IA después de apagar la IA, quitar la clave, cambiar de proveedor u olvidar/borrar | `ProcessWorker.kt:20 → LlmClient.kt:112` |
| 7 | media | IA y proveedor | Una conversación que siempre falla con un error «reintentable» se reenvía sin límite y bloquea toda la cola de IA | `ListeningService.kt:704 → AiProcessing.kt:98` |
| 8 | media | IA y proveedor | La voz ambiente provoca una llamada de pago al modelo por cada segmento con palabra disparadora, sin agrupar ni límite | `ListeningService.kt:499 → LlmClient.kt:112` |
| 9 | media | IA y proveedor | El procesado con IA interrumpido vuelve a la cola sin contar intento y se reenvía desde la parte 1 sin fin | `ConversationTracker.kt:34 → MaintenanceWorker.kt:43` |
| 10 | media | Recordatorios y alarmas | Una conversación marcada como ruido de tele o radio conserva las tareas OPEN y las alarmas exactas del recordatorio rápido, y sus copias en Preguntar y en el resumen diario | `ListeningService.kt:499 → ReminderReceiver.kt:45` |
| 11 | media | Importación y modelos | La fecha de modificación del proveedor, sin acotar, deja la transcripción importada fuera de la retención y dentro de cualquier «Olvidar» | `AudioImporter.kt:127 → DataManager.kt:101` |
| 12 | media | Importación y modelos | Una importación que mata el proceso o no termina se repite sin tope desde cero y bloquea la cola de importaciones; no hay forma de cancelar solo esa | `AudioImporter.kt:47 → ImportWorker.kt:45` |
| 13 | media | Importación y modelos | Una importación de audio en cola o en curso vuelve a crear transcripción dentro del intervalo recién olvidado y la envía al proveedor de IA | `AudioImporter.kt:35 → AiProcessing.kt:52` |
| 14 | media | Captura y servicio | Sin tope duro para la voz en curso del VAD: un audio continuo que parezca voz hace crecer los búferes nativo y Java hasta un OutOfMemoryError (importación y escucha en vivo) | `ImportWorker.kt:90 → VadSegmenter.kt:125` |
| 15 | media | Datos, borrado y retención | Las respuestas de Preguntar que copian fragmentos literales sobreviven al plazo de retención de la transcripción | `AskEngine.kt:116 → DataManager.kt:149` |
| 16 | media | Datos, borrado y retención | La cita literal que guarda cada tarea sobrevive al plazo de retención de la transcripción | `Prompts.kt:34 → ConversationScreen.kt:413` |
| 17 | media | Datos, borrado y retención | Si la zona horaria cambia, borrar una conversación no borra el resumen diario que la incluye, y ese resumen sigue llegando al proveedor de IA, a Hoy y a la exportación | `ConversationViewModel.kt:106 → AskEngine.kt:309` |
| 18 | media | Datos, borrado y retención | «Olvidar los últimos N min» no borra lo dicho antes de un adelanto del reloj de pared dentro de la ventana | `AudioCapture.kt:132 → DataManager.kt:93` |
| 19 | media | Datos, borrado y retención | «Olvidar los últimos N min» conserva recordatorios y tareas aceptadas de lo olvidado cuando la cita escrita por el modelo queda vacía al normalizarse o es genérica | `ConversationProcessor.kt:40 → DataManager.kt:250` |
| 20 | media | Datos, borrado y retención | Borrar la conversación abierta (OPEN) vuelve a guardar como conversación nueva la voz anterior al borrado que seguía en la VAD o en el canal, y la manda al proveedor de IA | `ConversationScreen.kt:223 → AiProcessing.kt:42` |
| 21 | media | Datos, borrado y retención | «Olvidar los últimos N min» conserva los facts confirmados que salieron de lo olvidado, y siguen yendo al proveedor de IA en cada Preguntar | `TodayViewModel.kt:87 → AskEngine.kt:299` |
| 22 | media | Recordatorios y alarmas | Sin tope de alarmas para los recordatorios que crea el modelo: al llegar al límite de alarmas por app de Android, schedule() lanza, el mantenimiento deja de aplicar la retención de la transcripción y cada conversación nueva con recordatorio se deshace | `ConversationProcessor.kt:41 → MaintenanceWorker.kt:34` |
| 23 | media | Datos, borrado y retención | Un recuerdo o una tarea borrados en Memoria o Tareas pueden no borrarse si se cambia de pestaña enseguida, aunque la pantalla ya haya dicho «borrado» | `MemoryScreen.kt:95 → AskEngine.kt:299` |
| 24 | media | IA y proveedor | Al editar la URL de la IA se conserva la clave del proveedor anterior y se envía al nuevo host | `SettingsAiScreen.kt:434 → AnthropicClient.kt:42` |
| 25 | baja | Captura y servicio | Unos extras de arranque malformados enviados por otra app podrían tumbar el proceso que aloja el servicio de micrófono | `AndroidManifest.xml:39 → ListeningService.kt:155` |
| 26 | baja | Recordatorios y alarmas | El modelo decide solo, con su explicitReminder, qué tareas sacadas de voz ajena o de audio importado pasan a OPEN con alarma exacta, sin comprobación determinista ni aprobación del dueño | `ListeningService.kt:491 → ReminderReceiver.kt:93` |
| 27 | baja | IA y proveedor | La respuesta del proveedor de IA se lee entera en memoria, sin límite, después de descomprimir el gzip | `LlmClient.kt:112 → AiProcessing.kt:77` |
| 28 | baja | Compilación y cadena de suministro | La release comparte applicationId y clave de firma con la variante debug depurable: un APK debug puede sustituirla en el sitio y dejar al descubierto los datos privados y la clave de IA del Keystore | `build.gradle.kts:31 → SecretStore.kt:57` |
| 29 | baja | Importación y modelos | tokens.txt se acepta solo por su tamaño y luego lo analiza el código nativo como vocabulario del modelo | `ModelManager.kt:137 → Transcriber.kt:184` |
| 30 | baja | Compilación y cadena de suministro | El AAR de sherpa-onnx servido por JitPack (clases Kotlin y librerías JNI nativas) entra en el APK de release firmado sin ningún digest fijado en el repositorio | `settings.gradle.kts:20 → Transcriber.kt:174` |
| 31 | baja | Datos, borrado y retención | Un salto del reloj hacia delante adelanta el borrado de la transcripción y deja la retención en suspenso tras corregirse | `MaintenanceWorker.kt:22 → MaintenanceWorker.kt:39` |
| 32 | baja | Recordatorios y alarmas | Un salto transitorio del reloj hacia delante gasta los recordatorios pendientes: avisan una vez antes de tiempo y no vuelven a sonar a su hora real | `SystemEventsReceiver.kt:19 → ReminderReceiver.kt:41` |
| 33 | baja | Recordatorios y alarmas | Un recordatorio que salta a la vez que un borrado sigue mostrando el texto de la tarea borrada | `ReminderReceiver.kt:23 → ReminderReceiver.kt:104` |
| 34 | baja | Interfaz | Al copiar una respuesta de Preguntar no se marca como sensible, a diferencia de la transcripción | `AskScreen.kt:250 → libs.versions.toml:5` |
| 35 | baja | Preguntar y citas | La regex de citas es cuadrática con tiradas largas de espacios y con «[c:» sin cerrar, y se vuelve a aplicar a todo el chat en el hilo principal | `OpenAiCompatClient.kt:74 → AskText.kt:18` |
| 36 | baja | Preguntar y citas | Una respuesta guardada con más de 32.766 números dentro de marcadores [c:...] tumbaría la app cada vez que se abre Preguntar | `AskEngine.kt:117 → AskViewModel.kt:38` |
| 37 | baja | Interfaz | «Olvidar los últimos N min» confirma el borrado sin esperar a forgetLast y aunque falle | `TodayScreen.kt:469 → TodayScreen.kt:180` |

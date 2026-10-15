# 02 — Grabación continua de micrófono en segundo plano en Android 10 → 17

Investigación del 15-oct-2026. Fuente principal: developer.android.com, completada con el CDD (source.android.com) y con el código de AOSP (rama `main`) donde la documentación no llega. Cada dato lleva su URL. Lo que no he podido verificar está marcado **[NO VERIFICADO]**, y lo que deduzco leyendo código, **[INFERENCIA AOSP]**.

Estado de las versiones: **Android 17 (API 37) es estable desde el 16-jun-2026** (Pixel), con QPR en beta. Para Google Play, desde el 31-ago-2026 las apps nuevas y las actualizaciones deben apuntar a **API 36**.
- https://www.androidauthority.com/android-17-release-date-3639790/
- https://developer.android.com/google/play/requirements/target-sdk

---

## 0. Decisiones recomendadas (resumen)

| Tema | Decisión |
|---|---|
| Mecanismo | Un único `Service` propio con `foregroundServiceType="microphone"`, **arrancado siempre desde la UI visible** (botón "Empezar a escuchar") o desde una acción de la notificación. Nada de WorkManager para capturar. |
| SDK | `minSdk 29` (Android 10), `targetSdk 36` (lo que exige Play hoy). `compileSdk 37` para tener las APIs de 17; subir a `targetSdk 37` solo tras probar el endurecimiento de audio y el de BAL. |
| Captura | `AudioRecord`, PCM 16 bit, mono, 16 kHz (el CDD lo garantiza sin sobremuestreo), fuente `VOICE_RECOGNITION` (sin NS/AGC por defecto según el CDD), con opción de `MIC`. **No** usar `VOICE_COMMUNICATION`/`CAMCORDER` ni `setPrivacySensitive(true)`, porque silenciarían a WhatsApp y al Asistente. |
| Pausas | Pausar parando `AudioRecord`, **sin** `stopForeground()`: este resetea el permiso while-in-use y después no se puede reanudar desde segundo plano. |
| Arranque tras reinicio | `BOOT_COMPLETED` **no** puede abrir un FGS de micrófono (desde Android 14). Al arrancar el móvil: publicar una notificación "Toca para reanudar" cuyo `PendingIntent.getForegroundService` arranca el servicio, lo que está exento por interacción con la notificación. |
| Reinicio por el sistema | Devolver `START_STICKY`. Según el código AOSP, el permiso WIU se conserva en el reinicio sticky. Aun así, `startForeground` va dentro de `try/catch` con la notificación de reanudar como respaldo. Hay que probarlo en un dispositivo real. |
| Batería y OEM | Pedir la exención de optimización de batería (app instalada a mano: sin problema de política). En Samsung y Xiaomi, guiar al usuario por los ajustes que lista dontkillmyapp.com. |
| Recordatorios | `AlarmManager.setExactAndAllowWhileIdle` (o `setAlarmClock` para los críticos) más notificación de prioridad alta. En la app instalada a mano: `USE_EXACT_ALARM` (API 33+) y `SCHEDULE_EXACT_ALARM` con `maxSdkVersion="32"`. Si algún día va a Play: solo `SCHEDULE_EXACT_ALARM`. |
| Trabajo diferido | Transcripción y VAD dentro del propio FGS, con coroutines en segundo plano. Las llamadas a la IA las hace el propio FGS, que tiene red sin restricciones incluso en Doze. WorkManager **2.12.0** solo para lotes diferibles y reintentos (resúmenes nocturnos, copias). En Android 16 sus jobs consumen cuota aunque haya un FGS. |
| Almacenamiento | No guardar PCM en bruto: 115 MB/h. Comprimir a Opus con `MediaCodec` (Android 10+) en Ogg: unos 7–11 MB/h a 16–24 kbps, y solo los tramos con voz. |

---

## 1. Matriz de cambios por versión (lo que afecta a esta app)

| Versión (API) | Cambio relevante | Fuente |
|---|---|---|
| 9 (28) | Hace falta el permiso `FOREGROUND_SERVICE`. Las apps en segundo plano sin FGS reciben silencio del micro. | https://developer.android.com/develop/background-work/services/fgs/changes · https://developer.android.com/media/platform/sharing-audio-input |
| **10 (29)** | Nuevo esquema de **captura concurrente** (prioridades por privilegio, UI visible y fuente sensible). `registerAudioRecordingCallback` e `isClientSilenced`. Codificador **Opus** en `MediaCodec` y muxer Ogg. | https://developer.android.com/media/platform/sharing-audio-input · https://developer.android.com/media/platform/supported-formats |
| **11 (30)** | Un FGS que use el micro **debe declarar el tipo `microphone`** (target 30+). Permiso "Solo esta vez". `setPrivacySensitive()`. Reseteo automático de permisos en apps sin uso. | https://developer.android.com/develop/background-work/services/fgs/changes · https://developer.android.com/training/permissions/requesting |
| **12 (31)** | **Prohibido arrancar FGS desde segundo plano** (`ForegroundServiceStartNotAllowedException`), con exenciones. Indicador verde de micro, interruptor global del micro y Panel de privacidad. Alarmas exactas tras `SCHEDULE_EXACT_ALARM`. Notificación de FGS diferida 10 s. `addOnModeChangedListener`. | https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start · https://developer.android.com/training/permissions/explaining-access |
| **13 (33)** | `POST_NOTIFICATIONS` en tiempo de ejecución, desactivado por defecto. **Task Manager** con botón "Detener" que para la app entera. Bucket *restricted* tras 8 días sin uso. `USE_EXACT_ALARM`. | https://developer.android.com/develop/ui/views/notifications/notification-permission · https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping · https://developer.android.com/topic/performance/appstandby |
| **14 (34)** | **Tipo de FGS obligatorio** más `FOREGROUND_SERVICE_MICROPHONE`. Comprobación de permisos al crear el FGS: `SecurityException` si falta `RECORD_AUDIO` o WIU. **FGS de micro prohibido desde `BOOT_COMPLETED`**. Notificaciones de FGS **descartables**. `SCHEDULE_EXACT_ALARM` **denegado por defecto**. `RECEIVER_EXPORTED/NOT_EXPORTED` obligatorio. Full-screen intent limitado. | https://developer.android.com/about/versions/14/behavior-changes-14 · https://developer.android.com/about/versions/14/behavior-changes-all |
| **15 (35)** | `BOOT_COMPLETED` no puede lanzar `dataSync`/`camera`/`mediaPlayback`/`phoneCall`/`mediaProjection`/`microphone`. Timeouts de 6 h/24 h para `dataSync` y `mediaProcessing` (**no para `microphone`**). Exención de `SYSTEM_ALERT_WINDOW` recortada. Hace falta FGS o ser la app visible para pedir foco de audio. El *force-stop* cancela todos los `PendingIntent`. Peticiones de red fuera de un ciclo de vida válido → excepción. Páginas de 16 KB. | https://developer.android.com/about/versions/15/behavior-changes-15 · https://developer.android.com/about/versions/15/behavior-changes-all |
| **16 (36)** | **Cuotas de JobScheduler y WorkManager** también para jobs que corren junto a un FGS o arrancados con la app visible. El bucket *active* pasa a tener cuota. `setImportantWhileForeground` deja de funcionar. Edge-to-edge obligatorio, predictive back y orientación ignorada en ≥600dp. Diálogo de compatibilidad si las librerías nativas están alineadas a 4 KB. | https://developer.android.com/about/versions/16/behavior-changes-all · https://developer.android.com/about/versions/16/behavior-changes-16 |
| **17 (37)** | **Endurecimiento del audio en segundo plano**: la reproducción, el foco y el volumen exigen una actividad visible o un FGS que no sea `shortService`; con target 37, un FGS **con capacidades WIU**. Afecta a la reproducción, no a la grabación. **Límites de memoria por app** (`MemoryLimiter:AnonSwap`). BAL endurecido (`MODE_BACKGROUND_ACTIVITY_START_ALLOWED` en desuso). `ACCESS_LOCAL_NETWORK` con target 37. CT activado por defecto. | https://developer.android.com/about/versions/17/changes/bg-audio · https://developer.android.com/about/versions/17/behavior-changes-all · https://developer.android.com/about/versions/17/behavior-changes-17 |

En Android 17, ni la página de cambios de FGS ni las de comportamiento traen **ninguna regla nueva específica para FGS de micrófono**. Lo he comprobado en las tres páginas.

---

## 2. Foreground service de tipo `microphone`

**Datos oficiales** (https://developer.android.com/develop/background-work/services/fgs/service-types):
- Manifest: `android:foregroundServiceType="microphone"`.
- Permiso: `FOREGROUND_SERVICE_MICROPHONE`, además de `FOREGROUND_SERVICE`.
- Constante: `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE`.
- Requisito previo: `RECORD_AUDIO` concedido.
- La página dice: *"You cannot create a microphone foreground service while your app is in the background. You also cannot launch one from a BOOT_COMPLETED receiver, except in a few exceptions."*

**Excepciones de `startForeground(int, Notification, int)`**, copiadas de la referencia de `Service` (https://developer.android.com/reference/android/app/Service):
- `ForegroundServiceStartNotAllowedException` (target ≥ S): bloqueado por la restricción de arranque en segundo plano.
- `InvalidForegroundServiceTypeException` (target ≥ 34): tipo inválido (`NONE`).
- `MissingForegroundServiceTypeException` (target ≥ 34): no hay tipo en el manifest y se pasó `FOREGROUND_SERVICE_TYPE_MANIFEST`.
- `IllegalArgumentException`: el tipo no es un subconjunto del declarado en el manifest.
- `SecurityException` (target ≥ 34): falta el permiso del tipo, o falta WIU.

**Plazo para llamar a `startForeground()`** tras `startForegroundService()`:
- La referencia de `Context` solo dice *"an amount of time comparable to the ANR interval"*. Si no se cumple, la app se cierra con `ForegroundServiceDidNotStartInTimeException` (https://developer.android.com/reference/android/content/Context).
- En AOSP `main`: `DEFAULT_SERVICE_START_FOREGROUND_TIMEOUT_MS = 30 s` y un ANR 10 s después. Son configurables por `device_config`.
- Recomendación: llamarlo en el primer `onStartCommand`, antes de inicializar modelos.

**Notificación:**
- Prioridad `PRIORITY_LOW` o superior (https://developer.android.com/develop/background-work/services/fgs/launch).
- Desde Android 12 la notificación se muestra con 10 s de retraso, salvo que lleve botones de acción, sea de ciertos tipos o use `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)` (https://developer.android.com/guide/components/foreground-services). Para una app que graba conviene `IMMEDIATE`, y un botón "Pausar" ya cumple la condición.

**`ServiceCompat.startForeground`** (androidx core, la última es 1.19.1 en Maven) enmascara los tipos según la API: en API 29+ admite `MICROPHONE`; por debajo de 29 llama a la versión sin tipo. Así no hacen falta ramas por versión en el código propio (código: https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/app/ServiceCompat.java).

---

## 3. Restricciones *while-in-use* (WIU): desde dónde se puede arrancar

Fuente: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

Son dos barreras distintas:

1. **BFSL** (arrancar un FGS desde segundo plano, target 31+). Exenciones: venir de un estado visible, la propia notificación/widget/burbuja, FCM de alta prioridad, alarma exacta, `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`/`LOCKED_BOOT_COMPLETED`, cambios de hora o idioma, CDM, **optimización de batería desactivada**, `SYSTEM_ALERT_WINDOW` con overlay visible (desde Android 15), etc.
2. **WIU** (micro, cámara y ubicación). Es más estricta: *"Creating a service that needs while-in-use permissions … from the background is blocked even when an exemption applies."* Desde Android 14 el sistema lanza `SecurityException` al crear el servicio; antes de 14, el servicio arrancaba pero recibía silencio.

**Situaciones que sí conceden WIU a un FGS iniciado en segundo plano** (lista oficial):
1. Lo arranca un componente del sistema.
2. Interacción con un widget de la app.
3. **Interacción con una notificación** ← la vía útil para "Toca para reanudar".
4. Un `PendingIntent` enviado desde otra app que está visible.
5. DPC en modo device owner.
6. App que proporciona el `VoiceInteractionService` (no aplica).
7. Permiso privilegiado `START_ACTIVITIES_FROM_BACKGROUND` (no aplica).

**Lo que NO concede WIU:**
- La exención de optimización de batería: sirve para BFSL, no para WIU.
- Una alarma exacta: está exenta de BFSL, pero no aparece en la lista WIU.
- `BOOT_COMPLETED`: lo prohíbe expresamente.

`PermissionChecker.checkSelfPermission()` devuelve `GRANTED` en segundo plano y engaña: el permiso de micro es "mientras se usa la app". Mensaje de logcat cuando falla: `Foreground service started from background can not have location/camera/microphone access`.

**Detalle de AOSP sobre el ciclo de vida del permiso WIU** [INFERENCIA AOSP, `ActiveServices.java` y `ServiceRecord.java` de `main`]:
- La concesión WIU (`mAllowWiu_noBinding`) vive en el `ServiceRecord`. `setFgsRestrictionLocked()` **solo la mejora**: si ya no estaba `DENIED`, la deja como está.
- Solo se borra en `resetFgsRestrictionLocked()`, que se llama en `stopForeground()`, en `bringDownServiceLocked()` (el servicio se destruye) y en el caso de un servicio iniciado con `startService` que tarda más de 10 s en llamar a `startForeground`.
- Consecuencias:
  - Volver a llamar a `startForeground()` estando ya en primer plano, por ejemplo para actualizar la notificación, **no** pierde el WIU. Aun así es más sencillo actualizarla con `NotificationManager.notify(mismoId, …)`.
  - **Llamar a `stopForeground()` mata el WIU**. Después, un `startForeground(MICROPHONE)` en segundo plano fallará. Por eso la pausa no debe degradar el servicio.
- La política de tipos (`ForegroundServiceTypePolicy.FGS_TYPE_POLICY_MICROPHONE`) marca `RECORD_AUDIO` como *foregroundOnlyPermission*. Con el app-op en `MODE_FOREGROUND` y sin WIU devuelve `PERMISSION_DENIED`, y de ahí la `SecurityException`.

---

## 4. Reinicios: START_STICKY, muerte del proceso, Task Manager y force-stop

**`START_STICKY`** (referencia oficial de `Service`, citada literalmente):
> "if this service's process is killed while it is started … Later the system will try to re-create the service … it will be called with a null intent object … Since Android version S, apps targeting S or above are disallowed to start a foreground service from the background, but **the restriction doesn't impact restarts of a sticky foreground service**. However, when apps start a sticky foreground service from the background, the same restriction still applies."

**¿Puede el reinicio volver a abrir el micro?**
- La documentación solo habla de la restricción BFSL. No dice nada del WIU.
- [INFERENCIA AOSP] Al morir el proceso, `killServicesLocked()` llama a `scheduleServiceRestartLocked()`. El `ServiceRecord` sigue en `mRestartingServices` y no pasa por `resetFgsRestrictionLocked()`. El reinicio (`performServiceRestartLocked` → `bringUpServiceLocked`) reutiliza **el mismo** `ServiceRecord`. Por tanto, la concesión WIU obtenida al arrancar desde la UI **debería mantenerse** y `startForeground(MICROPHONE)` debería funcionar en el reinicio.
- Hay informes de la comunidad que apuntan en la misma dirección: el permiso "se fija al crear el ServiceRecord" (https://github.com/Ikolvi/Tracelet/issues/405, sobre ubicación).
- No está documentado. Un OEM o una versión futura pueden comportarse distinto: hay que **probarlo** y tener respaldo.
- Si el servicio se destruyó (con `stopSelf` o "Detener" en el Task Manager), el `ServiceRecord` desaparece y se pierde la concesión.
- Retraso del reinicio en AOSP `main`: base de 1 s con factor de backoff ×4, mínimo 10 s entre reinicios, más 0, 10, 20 o 30 s según la presión de memoria (`ActivityManagerConstants`). Para un servicio reiniciado, `fgRequired` es falso, así que no hay ANR si no se llama a `startForeground`. Pero sin FGS el sistema lo trata como servicio en segundo plano y lo detiene enseguida.

**Patrón robusto en `onStartCommand`:**

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    try {
        ServiceCompat.startForeground(this, NOTIF_ID, ongoing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    } catch (e: Exception) { // SecurityException | ForegroundServiceStartNotAllowedException (API 31+)
        notifyTapToResume()       // PendingIntent.getForegroundService(...) → exención WIU por notificación
        stopSelf(); return START_NOT_STICKY
    }
    capture.startIfNeeded()       // intent == null ⇒ reinicio del sistema: reanudar sin más
    return START_STICKY
}
```

**Probar la muerte del proceso** (build debuggable):
```
adb shell run-as <pkg> kill -9 <pid>   # simula un kill del sistema → debe reiniciar (sticky)
adb shell am kill <pkg>                 # solo mata si el proceso es "safe to kill"; puede no tocar un FGS
```

**Task Manager (Android 13+, cualquier target)** (https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping):
- "Detener" saca la app de memoria, quita la notificación y vacía la pila de actividades.
- **No hay callback.** Los jobs y las alarmas **siguen disparándose**.
- Al volver se detecta con `ApplicationExitInfo.REASON_USER_REQUESTED`.
- Prueba: `adb shell cmd activity stop-app <pkg>`.
- El servicio no vuelve solo. Respuesta sensata: si una alarma o un job ve que la grabación debería estar activa y no lo está, publica la notificación "Toca para reanudar".

**Force-stop (Ajustes → Forzar detención):**
- Desde Android 15 **cancela todos los `PendingIntent`**, alarmas incluidas.
- Al sacar la app del estado *stopped* por acción del usuario se recibe `ACTION_BOOT_COMPLETED`. Es el momento de reprogramar.
- Se detecta con `ApplicationStartInfo.wasForceStopped()` (https://developer.android.com/about/versions/15/behavior-changes-all).

**Deslizar la app en recientes:**
- En Android puro, el proceso con FGS sigue vivo y recibe `onTaskRemoved()`.
- Algunos fabricantes (Xiaomi y otros) lo convierten en force-stop. Ver §12.

---

## 5. Límites de tiempo de los FGS (15/16/17)

Fuente: https://developer.android.com/develop/background-work/services/fgs/timeout
- Solo afectan a **`dataSync` y `mediaProcessing`** (6 h por cada 24 h, compartidas por tipo) y a **`shortService`** (unos 3 min).
- La página dice literalmente que *"currently applies only to dataSync and mediaProcessing"*. **El tipo `microphone` no tiene límite.**
- Cuando un tipo con límite expira, se llama a `onTimeout(int startId, int fgsType)` (API 35). Si la app no se detiene, `RemoteServiceException` y cierre.
- **Consecuencia**: no añadir `dataSync` al servicio de captura "para subir datos", porque metería un límite que el micro no tiene. Implementar `onTimeout` de todas formas por si un día cambia; es gratis.
- Prueba: `adb shell am compat enable FGS_INTRODUCE_TIME_LIMITS <pkg>`.

---

## 6. Doze, App Standby, optimización de batería y red

**Doze** (https://developer.android.com/training/monitoring-device-state/doze-standby):
- Suspende la red, ignora los wakelocks, aplaza las alarmas normales y los jobs, y no hace escaneos Wi-Fi.
- `setAndAllowWhileIdle`/`setExactAndAllowWhileIdle`: como máximo una cada 9 min por app; la tabla de power-details dice 7 por hora.
- `setAlarmClock`: saca al sistema de Doze.

**¿Le afecta Doze a un FGS?** Verificado en AOSP:
- **Red**: `NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode` permite red a `procState <= PROCESS_STATE_BOUND_FOREGROUND_SERVICE`. Un FGS tiene red en Doze. La tabla oficial lo confirma: *"App process is running a foreground service → Network: No restrictions"* (https://developer.android.com/topic/performance/power/power-details).
- **Wakelocks**: `PowerManagerService.setWakeLockDisabledStateLocked` solo desactiva en Doze los de UIDs con `procState > PROCESS_STATE_BOUND_FOREGROUND_SERVICE`. Los de un FGS siguen activos.
- **CPU mientras se graba**: el hilo de captura de AudioFlinger (`AudioIn_*`) adquiere un wakelock propio atribuido a los UID clientes (`updateWakeLockUids_l`, en `frameworks/av/.../Threads.cpp`). Mientras `AudioRecord` está activo, la CPU no se suspende y no hace falta un `PARTIAL_WAKE_LOCK` propio para capturar. El consumo se imputa a la app.
- **Jobs**: no se libran. En Doze se aplazan a la ventana de mantenimiento aunque haya FGS (`setImportantWhileForeground` no funciona desde Android 16).

**App Standby buckets** (https://developer.android.com/topic/performance/appstandby):
- Un FGS de larga duración mantiene la app en **active**.
- Tras 8 días sin interacción (Android 13+), *restricted*.
- Libran del bucket restricted, entre otras: las apps con `USE_EXACT_ALARM`, las que el usuario marca como "sin restricciones" y las que tienen widgets activos.
- Las apps en la lista de exención de Doze libran de **todas** las restricciones por bucket.

**Restricción de batería por el usuario** (Ajustes → Batería → "Restringida"): según AOSP, la app *"Can't run foreground services"* (https://source.android.com/docs/core/power/app_mgmt). Comprobar con `ActivityManager.isBackgroundRestricted()` y avisar en la UI.

**Exención de optimización de batería:**
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` más `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (diálogo directo), o `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (lista general). Se comprueba con `PowerManager.isIgnoringBatteryOptimizations()`.
- Qué aporta: red y wakelocks parciales en Doze (que el FGS ya tiene), exención BFSL, salir de las restricciones por bucket y, en la práctica, menos agresividad de los OEM.
- **Política de Play**: está prohibido pedirla directamente salvo que Doze rompa la función principal. Los casos aceptados son chat/VoIP sin FCM, seguridad, automatización de tareas y periféricos. En una app instalada a mano no hay problema; en Play, mejor la pantalla de ajustes general.

**Pruebas:**
```
adb shell dumpsys deviceidle force-idle      # Doze
adb shell dumpsys deviceidle unforce && adb shell dumpsys battery reset
adb shell am set-standby-bucket <pkg> rare|restricted ; adb shell am get-standby-bucket <pkg>
```

---

## 7. Notificaciones: POST_NOTIFICATIONS, descartables y Task Manager

- **Android 13**: `POST_NOTIFICATIONS` en tiempo de ejecución, desactivado por defecto en instalaciones nuevas. **No hace falta para arrancar un FGS**. Si se deniega, la notificación del FGS **no aparece en el cajón** pero sí en el Task Manager (https://developer.android.com/develop/ui/views/notifications/notification-permission). Los recordatorios lo necesitan, así que hay que pedirlo en el onboarding.
- **Android 14**: las notificaciones con `setOngoing(true)` **se pueden descartar**, salvo con el móvil bloqueado y con "Borrar todo" (https://developer.android.com/about/versions/14/behavior-changes-all). El FGS sigue funcionando. Para saberlo, `setDeleteIntent()` y volver a publicarla con `notify()` [NO VERIFICADO que el sistema no limite volver a publicarla; probarlo].
- **Canal del FGS**: `IMPORTANCE_LOW`, sin sonido. Contenido: "Escuchando · 3 h 12 min · 14 notas", con acciones "Pausar" y "Detener". **Canal de recordatorios**: `IMPORTANCE_HIGH`.
- **Full-screen intent** (Android 14+, target 34): reservado a llamadas y alarmas, y Play lo revoca. **No usarlo** para recordatorios. Se comprueba con `NotificationManager.canUseFullScreenIntent()` (https://developer.android.com/about/versions/14/behavior-changes-14).

---

## 8. Captura concurrente: llamadas, Asistente, WhatsApp

Fuentes: https://developer.android.com/media/platform/sharing-audio-input y el CDD 17 §5.4.5 (https://source.android.com/docs/compatibility/17/android-17-cdd).

**Reglas** (desde Android 10):
- Las apps privilegiadas (Asistente preinstalado, servicios de accesibilidad) tienen prioridad sobre las normales.
- Una UI visible tiene prioridad sobre el segundo plano.
- Una fuente **sensible** (`VOICE_COMMUNICATION`, `CAMCORDER`) tiene prioridad sobre una que no lo es.
- Dos apps normales nunca reciben audio a la vez: la otra recibe **silencio**, con el `AudioRecord` "funcionando" pero entregando ceros.

**Escenarios para nuestra app** (normal, en segundo plano, fuente `VOICE_RECOGNITION`/`MIC`):

| Situación | Resultado para nosotros |
|---|---|
| **Llamada telefónica o VoIP** (`AudioManager.getMode()` = `MODE_IN_CALL`/`MODE_IN_COMMUNICATION`) | *"The call always receives audio"*; nosotros **silencio**. Solo podría capturar un servicio de accesibilidad, y la llamada en sí solo una app privilegiada con `CAPTURE_AUDIO_OUTPUT`. **No se puede grabar ni transcribir llamadas.** |
| **WhatsApp grabando una nota de voz** (UI visible) | Si WhatsApp usa una fuente no sensible, gana por tener la UI encima. Si usa `VOICE_COMMUNICATION`, gana por fuente sensible. En los dos casos, **silencio** para nosotros mientras dura la nota [la fuente concreta de WhatsApp está NO VERIFICADA; el resultado es el mismo]. |
| **Asistente/Gemini** | El Asistente recibe audio siempre, salvo que otra app esté capturando con fuente sensible. Nosotros recibimos audio **salvo que el Asistente tenga UI visible encima**. La palabra de activación (`HotwordDetectionService`/`SOURCE_HOTWORD`) convive con nosotros. |
| Dos apps normales en segundo plano | Recibe audio la que empezó a capturar más tarde. |
| **Si nosotros usáramos `VOICE_COMMUNICATION` o `setPrivacySensitive(true)`** | Silenciaríamos a WhatsApp y a otros grabadores aunque tengan la UI delante. `setPrivacySensitive(true)` bloquea incluso al Asistente (CDD C-1-3). **Mala experiencia: no hacerlo.** |

**Detección** (registrar **antes** de `startRecording()`):
```kotlin
record.registerAudioRecordingCallback(executor, object : AudioManager.AudioRecordingCallback() {
    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
        val mine = record.activeRecordingConfiguration ?: return
        segmenter.markSilenced(mine.isClientSilenced)   // API 29+: anotar "hueco: micro ocupado"
    }
})
audioManager.addOnModeChangedListener(executor) { mode -> segmenter.markCall(mode == MODE_IN_CALL || mode == MODE_IN_COMMUNICATION) } // API 31+, sin permisos
```
Con ese estado, la línea de tiempo puede mostrar "14:02–14:09 · micro ocupado (llamada)" en vez de un silencio sin explicación.

---

## 9. Privacidad del sistema: indicador, interruptor, una vez, hibernación

- **Indicador de micro** (Android 12+): icono verde permanente mientras capturamos, y la app aparece en Ajustes rápidos y en el Panel de privacidad (https://developer.android.com/training/permissions/explaining-access). El CDD 17 (9.8.2/H-4-1) obliga a mostrarlo; no se puede ocultar. Es lo que se espera de una app de este tipo, pero el usuario lo verá todo el día.
- **Interruptor global del micro** (Android 12+): con el micro bloqueado, la app recibe **audio en silencio sin código de error** (CDD 9.8.13 C-1-3). `SensorPrivacyManager.supportsSensorToggle(MICROPHONE)` solo dice si el dispositivo lo soporta, no su estado. Heurística: si llegan N segundos de ceros exactos sin estar en llamada ni silenciados, mostrar "¿Micrófono bloqueado?" en la notificación.
- **Panel de privacidad**: declarar una actividad con `VIEW_PERMISSION_USAGE_FOR_PERIOD` (permiso `START_VIEW_PERMISSION_USAGE`) para explicar por qué se usa el micro.
- **"Solo esta vez"** (Android 11+): si el FGS se arranca con la actividad visible, el acceso se mantiene *"until the foreground service stops"* (https://developer.android.com/training/permissions/requesting). Al reiniciar el móvil hay que volver a pedirlo. Hay que pedir "Mientras se usa la app".
- **Hibernación** (target 30+): tras "unos meses" sin uso se resetean los permisos. Con FGS a diario no debería pasar, pero en el onboarding conviene llevar al usuario a desactivar "Pausar actividad si no se usa" con `IntentCompat.createManageUnusedAppRestrictionsIntent()` y `startActivityForResult` (https://developer.android.com/topic/performance/app-hibernation).

---

## 10. AudioRecord vs MediaRecorder, fuente y formato

**AudioRecord, sin duda**:
- Da PCM por bloques para VAD y ASR en streaming, y deja decidir qué se guarda.
- `MediaRecorder` solo escribe un fichero codificado. Para guardar comprimido: `AudioRecord` → VAD → `MediaCodec` (Opus) → `MediaMuxer` (`MUXER_OUTPUT_OGG`, API 29).

**Formato 16 kHz, mono, PCM16:**
- El javadoc de `AudioRecord` aún dice que *"44100Hz is currently the only rate that is guaranteed"* (https://developer.android.com/reference/android/media/AudioRecord).
- **El CDD manda**: desde Android 10 hasta 17, §5.4.1 C-1-1 obliga a soportar **PCM 16 bit, mono, 8000/11025/16000/44100/48000 Hz**. Desde el CDD 17, explícitamente para las fuentes `DEFAULT, MIC, CAMCORDER, VOICE_RECOGNITION, VOICE_COMMUNICATION, UNPROCESSED, VOICE_PERFORMANCE`. Además, sin sobremuestreo y con filtro antialiasing (C-1-2/C-1-3) (https://source.android.com/docs/compatibility/17/android-17-cdd y la del 10).
- Aun así hay que comprobar `getState() == STATE_INITIALIZED` y `getSampleRate()`, con remuestreo por software como respaldo.

**Fuente de audio** (https://developer.android.com/reference/android/media/MediaRecorder.AudioSource y CDD §5.4.2):

| Fuente | Qué hace | Para voz lejana / transcripción |
|---|---|---|
| `VOICE_RECOGNITION` (6) | CDD: **NS y AGC desactivados por defecto**, respuesta plana ±3 dB entre 100 Hz y 4 kHz, sensibilidad calibrada (1 kHz a 90 dB SPL ≈ RMS 2500). | **Recomendada por defecto**: señal predecible y sin artefactos, buena para Whisper y similares. Contra: el nivel es bajo con voz lejana. Aplicar ganancia o normalización por software antes del VAD. |
| `MIC` (1) | Procesado que decide el fabricante; suele llevar AGC y NS. | Más volumen con voz lejana, pero varía según el modelo. Dejarla como opción en Ajustes y probarla. |
| `UNPROCESSED` (9) | En bruto si existe; si no, se comporta como `DEFAULT`. Se comprueba con `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)`. | Útil para experimentar; ni el nivel ni la disponibilidad están garantizados. |
| `VOICE_COMMUNICATION` (7) | AEC, AGC y NS pensados para VoIP de cerca. **Fuente sensible**. | **No**: silencia a otras apps y va afinada para hablar pegado al móvil. |
| `CAMCORDER` (5) | Orientada como la cámara. **Fuente sensible**. | **No**, por el mismo motivo. |

- Efectos opcionales: `android.media.audiofx.NoiseSuppressor`/`AutomaticGainControl` sobre la sesión, si `isAvailable()`.
- Micro integrado: `setPreferredDevice(TYPE_BUILTIN_MIC)` para que no cambie a unos auriculares con cable.
- Errores de lectura: `read()` puede devolver `ERROR_DEAD_OBJECT` si se reinicia `audioserver`. Hay que recrear el `AudioRecord`, lo que es posible porque el FGS mantiene el WIU.

**Tamaños (calculados):**

| Formato | Velocidad | Por hora | 16 h al día |
|---|---|---|---|
| PCM 16 kHz/16 bit mono | 32 000 B/s | **115,2 MB** | 1,84 GB |
| Opus 24 kbps | 3 000 B/s | 10,8 MB | 173 MB |
| Opus 16 kbps | 2 000 B/s | 7,2 MB | 115 MB |
| AMR-WB 12,65 kbps (codificador en todas las versiones) | ≈1 581 B/s | 5,7 MB | 91 MB |

Con VAD y guardando solo los tramos con voz, la cifra real baja en la proporción de tiempo con voz.
- Opus: codificador **Android 10+**; contenedores Ogg/MP4/MKV (https://developer.android.com/media/platform/supported-formats).
- [NO VERIFICADO] Las frecuencias que acepta el codificador Opus de cada dispositivo: consultar `MediaCodecInfo.getCapabilitiesForType("audio/opus").audioCapabilities.supportedSampleRateRanges`.

---

## 11. Consumo de batería (datos de referencia)

- **DSP.Ear (SenSys 2014, placa MDP con Snapdragon 800)**: mantener la CPU despierta con la pantalla apagada cuesta **295 mW**; el micro suma unos **47 mW**; en total 342 mW. Solo eso, con una batería de 2300 mAh, daba menos de 14 h con un clasificador sencillo en la CPU (https://arxiv.org/html/1409.3206). Es hardware antiguo; los SoC actuales gastan menos en reposo, pero el orden de magnitud sirve.
- **Estimación propia [INFERENCIA]**: 342 mW sobre una batería moderna de 5000 mAh × 3,85 V ≈ 19,3 Wh supone ≈ **1,8 %/h** solo por tener micro y CPU despiertos, antes de VAD y ASR. El VAD es barato. La transcripción con un modelo tipo Whisper es lo caro, por eso solo se ejecuta sobre tramos con voz y por lotes. No he encontrado mediciones publicadas de ASR continuo en Android: **hay que medirlo** con `adb shell dumpsys batterystats --reset` y luego Battery Historian / `dumpsys batterystats <pkg>`.
- Como la captura obliga a tener el wakelock de AudioFlinger (§6), **no hay forma de dormir la CPU mientras se escucha**. Para ahorrar: no despertar la radio por cada fragmento (agrupar las subidas) y transcribir por lotes.
- Un "modo nocturno" que pare la escucha por horario solo funciona si no se llama a `stopForeground()`: hay que mantener el FGS con la notificación "En pausa hasta las 07:00".

---

## 12. Fabricantes que matan procesos (dontkillmyapp.com)

Ranking (https://dontkillmyapp.com/), con nota 5 = peor: **Huawei 5, Xiaomi 5, OnePlus 5, Samsung 5**; Meizu, Asus y Oppo 4; Vivo, realme y Motorola 3; Sony 2; **Pixel/AOSP 0**.

- **Samsung** (https://dontkillmyapp.com/samsung):
  - Batería → Límites de uso en segundo plano: quitar la app de "Apps en suspensión" y "Apps en suspensión profunda", añadirla a "Apps que nunca se suspenden" y desactivar "Poner en suspensión las apps sin usar".
  - Batería de la app → "Sin restricciones".
  - Desactivar la batería adaptativa y la optimización automática.
  - La página cita a Samsung: desde One UI 6.0 *"foreground services of apps targeting Android 14 will be guaranteed to work as intended"*. dontkillmyapp pide confirmación a los usuarios; está sin confirmar.
  - En el lado del desarrollador, la página dice *"No known solution on dev end"*.
- **Xiaomi** (MIUI/HyperOS) (https://dontkillmyapp.com/xiaomi):
  - Autoarranque activado.
  - Ahorro de batería de la app → "Sin restricciones".
  - Bloquear la app en recientes (candado).
  - Plantearse desactivar las "Optimizaciones MIUI".
  - Existe la librería `MIUI-autostart` (XomaDev) para leer el estado del autoarranque, pero sería **dependencia nueva**: no la añadiría sin preguntar, porque basta con un enlace a ajustes y un texto de ayuda.
- **Implementación**: pantalla "Mantener activo" con detección de `Build.MANUFACTURER` y botones a los ajustes, más un registro de salidas (`getHistoricalProcessExitReasons`) para ver si nos matan. Las razones útiles son `REASON_LOW_MEMORY`, `REASON_USER_REQUESTED`, `REASON_OTHER` con "MemoryLimiter" en Android 17, `REASON_EXCESSIVE_RESOURCE_USAGE`…
- **Android 17, límites de memoria** (https://source.android.com/docs/core/perf/memory-limiter): límites por proceso según la RAM; por ejemplo, un móvil de 6 GB da 4 GiB a las apps visibles y 2 GiB a las no visibles. Un modelo ASR de cientos de MB cabe, pero las fugas nativas acaban en `REASON_OTHER` + `MemoryLimiter:AnonSwap`.

---

## 13. Recordatorios: alarmas exactas y notificaciones

Fuente: https://developer.android.com/develop/background-work/services/alarms/schedule

- **`SCHEDULE_EXACT_ALARM`**:
  - Lo concede el usuario (Ajustes → "Alarmas y recordatorios") y lo pueden revocar tanto él como el sistema.
  - **No viene concedido en instalaciones nuevas** con target 33+ en Android 14+.
  - Se comprueba con `canScheduleExactAlarms()` y se pide con `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`.
  - Si se revoca, *"your app stops, and all future exact alarms are canceled"*.
  - Hay que escuchar `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` para reprogramar.
- **`USE_EXACT_ALARM`** (API 33+): se concede automáticamente y el usuario no puede revocarlo. **En Play solo para apps de alarma/temporizador o calendarios con notificaciones de eventos** (https://support.google.com/googleplay/android-developer/answer/9888170). En la app instalada a mano es lo más cómodo, y además saca a la app del bucket restricted.
- **Qué API usar**:
  - `setExactAndAllowWhileIdle(RTC_WAKEUP, …)` para recordatorios normales.
  - `setAlarmClock()` para los críticos: *"the system never adjusts their delivery time"* y sale de Doze, a cambio de mostrar el icono de alarma.
  - Sin permiso, `setAndAllowWhileIdle` como respaldo inexacto.
- **Reinicio y actualizaciones**: todas las alarmas se borran al apagar el móvil. Hay que reprogramarlas en `BOOT_COMPLETED` (solo llega si el usuario abrió la app al menos una vez), en `MY_PACKAGE_REPLACED`, en `TIMEZONE_CHANGED` (para recordatorios "a las 9:00 hora local") y al salir de un force-stop (Android 15). Hace falta una única tabla en la base de datos local como fuente de verdad.
- Las alarmas exactas están exentas de BFSL, **pero no conceden WIU**: no sirven para reabrir el micro.
- **Sonido del recordatorio**: que lo reproduzca el sistema mediante el sonido del canal de notificación. Si la app reprodujera audio o TTS desde segundo plano, en Android 17 haría falta un FGS **con WIU** (target 37), y si no, fallaría en silencio. El FGS de micro lo tiene, siempre que se arrancara desde la UI. La alternativa es la excepción de `USAGE_ALARM` más el permiso de alarma exacta (https://developer.android.com/about/versions/17/changes/bg-audio). Para probarlo: `adb shell cmd audio set-enable-hardening throw`.
- Para abrir la app desde el recordatorio, usar el `contentIntent` de la notificación (`PendingIntent.getActivity`). No lanzar actividades desde el receptor: es el endurecimiento de BAL de Android 14 y 17.

---

## 14. WorkManager y trabajo diferido

- **Versión**: `androidx.work:work-runtime(-ktx)` **2.12.0** estable desde el 23-sep-2026 (`minSdk` sube a 24). Comprobado en `dl.google.com/android/maven2/androidx/work/work-runtime/maven-metadata.xml` y en https://developer.android.com/jetpack/androidx/releases/work.
- **Android 16** (https://developer.android.com/about/versions/16/behavior-changes-all): los jobs que se ejecutan **mientras hay un FGS** o que arrancaron con la app visible **consumen la cuota**. Cuotas orientativas (https://developer.android.com/topic/performance/power/power-details):

  | Bucket | Jobs normales | Expeditos |
  |---|---|---|
  | Active | 20 min por cada 60 min | 30 min / 24 h |
  | Working set | 10 min / 4 h | — |
  | Rare | 10 min / 24 h | — |
  | Restricted | 1 vez al día, 10 min | — |

  Para saber por qué se paró uno, `WorkInfo.getStopReason()`.
- Un worker de larga duración con `setForeground` también agota la cuota en Android 16, según la guía: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running.
- **Reparto recomendado**:
  - Dentro del FGS de micro (sin límite de tiempo y con red en Doze): VAD, segmentación, ASR por lotes y la cola de llamadas a la IA (resumir, extraer tareas). Hay que respetar el ciclo de vida: Android 15 lanza excepciones a las peticiones de red hechas fuera de un ciclo de vida válido.
  - WorkManager solo para lo diferible y lo que necesite reintentos persistentes. Por ejemplo, el resumen diario o la reindexación de embeddings, con `NetworkType.CONNECTED` y opcionalmente `setRequiresCharging(true)`. Y como red de seguridad: un `PeriodicWorkRequest` de unos 15 min que comprueba si la escucha debería estar activa y, si no lo está, publica "Toca para reanudar".
  - Si los lotes van a la API de DeepSeek (nota global del usuario), programarlos en horas valle. Las preguntas interactivas nunca se condicionan a la franja.

---

## 15. Política de Google Play (por si algún día se publica)

1. **Declaración de FGS** (https://support.google.com/googleplay/android-developer/answer/13392821): en Play Console → Contenido de la app hay que describir cada tipo, el impacto si se interrumpe, y aportar un **vídeo** del flujo. El tipo micro entra en el caso "Background Audio Access" (*"Capture audio input, for example, voice commands … voice recording"*).
2. **Criterios de FGS** (Abuso de dispositivos y redes, https://support.google.com/googleplay/android-developer/answer/9888379): iniciado o percibido por el usuario, que el usuario pueda detenerlo, que una interrupción perjudique la experiencia, y que dure solo lo necesario. Una escucha continua de días es defendible solo si es la función principal, se activa explícitamente y se ve siempre (notificación más indicador del sistema). Riesgo de revisión **alto**.
3. **Datos de usuario: divulgación destacada y consentimiento** (https://support.google.com/googleplay/android-developer/answer/10144311): pantalla propia dentro de la app, **antes** de pedir el permiso. Debe decir qué se graba, para qué y si sale del dispositivo, y exige una acción afirmativa. Salir de la pantalla no cuenta como consentimiento.
4. **Spyware** (https://support.google.com/googleplay/android-developer/answer/14745000): es infracción recoger datos en segundo plano *"in a manner that is unexpected to the user"*, y también los SDK que transmiten datos de grabaciones. Nada de SDK de terceros con acceso al audio.
5. **Alarmas exactas**: `USE_EXACT_ALARM` solo para alarmas, temporizadores y calendarios. Para Play, `SCHEDULE_EXACT_ALARM`.
6. **Optimización de batería**: el diálogo directo solo para los casos aceptados (§6).
7. **target API 36** desde el 31-ago-2026. **Páginas de 16 KB** para librerías nativas (whisper.cpp, ONNX Runtime, sherpa-onnx…): según https://developer.android.com/guide/practices/page-sizes, a partir del **1-feb-2027** no se podrán publicar actualizaciones sin soporte. Desde Android 16 aparece un diálogo de compatibilidad en los dispositivos de 16 KB aunque la app no esté en Play. Compilar con alineación a 16 KB desde el principio.
8. Ley española y RGPD: grabar a terceros es otro frente, fuera de este informe, pero debe aparecer en la divulgación.

---

## 16. Checklist de implementación (código)

### 16.1 Manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <uses-feature android:name="android.hardware.microphone" android:required="true"/>

  <uses-permission android:name="android.permission.RECORD_AUDIO"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>              <!-- API 28+ -->
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>   <!-- API 34+ -->
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>              <!-- API 33+ -->
  <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
  <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/> <!-- quitar si va a Play -->
  <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32"/>
  <uses-permission android:name="android.permission.USE_EXACT_ALARM"/>                 <!-- sideload; en Play: SCHEDULE_EXACT_ALARM sin maxSdk -->
  <uses-permission android:name="android.permission.INTERNET"/>

  <application ...>
    <service android:name=".capture.ListenService"
        android:exported="false"
        android:foregroundServiceType="microphone"/>   <!-- sin dataSync: metería el límite de 6 h -->

    <receiver android:name=".capture.BootReceiver" android:exported="false">
      <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED"/>
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED"/>
        <action android:name="android.intent.action.TIMEZONE_CHANGED"/>
      </intent-filter>
    </receiver>
    <receiver android:name=".reminders.ReminderReceiver" android:exported="false"/>
    <receiver android:name=".reminders.ExactAlarmPermissionReceiver" android:exported="false">
      <intent-filter>
        <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"/>
      </intent-filter>
    </receiver>

    <!-- Panel de privacidad (Android 12+): explica el uso del micro -->
    <activity android:name=".privacy.MicUsageActivity" android:exported="true"
        android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
      <intent-filter>
        <action android:name="android.intent.action.VIEW_PERMISSION_USAGE_FOR_PERIOD"/>
        <category android:name="android.intent.category.DEFAULT"/>
      </intent-filter>
    </activity>
  </application>
</manifest>
```
- Receptores con `exported="false"`: los broadcasts protegidos que envía el sistema les llegan igualmente, porque el emisor es el UID del sistema. [Comprobarlo en el dispositivo con `adb shell am broadcast` no sirve: el shell no es el sistema. Mejor reiniciar el emulador.]
- WorkManager añade por fusión de manifests `WAKE_LOCK`, `ACCESS_NETWORK_STATE` y su propio receptor de arranque.

### 16.2 Flujo y comprobaciones

1. **Onboarding (con actividad visible)**:
   1. Divulgación propia y consentimiento con acción afirmativa.
   2. `RECORD_AUDIO`: pedir "Mientras se usa la app" y, si eligen "Solo esta vez", explicar la diferencia.
   3. `POST_NOTIFICATIONS` (API 33+).
   4. Alarma exacta: en API 31–32, `canScheduleExactAlarms()` y si no, `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. En 33+ con `USE_EXACT_ALARM` ya está concedido.
   5. Optimización de batería: `isIgnoringBatteryOptimizations()` y si no, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
   6. Hibernación: `getUnusedAppRestrictionsStatus()` y `createManageUnusedAppRestrictionsIntent()`.
   7. Pantalla "Mantener activo" según el fabricante.
   8. `isBackgroundRestricted()` y avisar si es `true`.
2. **Arranque**: `ContextCompat.startForegroundService(ctx, Intent(ctx, ListenService::class.java))` **solo** desde la actividad visible o desde un `PendingIntent.getForegroundService` en una acción de notificación o widget. En API 31+ envolverlo en `try/catch(ForegroundServiceStartNotAllowedException)`.
3. **`onStartCommand`**: `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)` en un `try/catch` (patrón del §4), notificación con `FOREGROUND_SERVICE_IMMEDIATE` y acciones Pausar/Detener, devolver `START_STICKY`. Tratar `intent == null` como reinicio del sistema.
4. **Captura**:
   - `AudioRecord.Builder()` con `VOICE_RECOGNITION`, 16 kHz, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT` y un buffer ≥ `getMinBufferSize()` (por ejemplo, ≥1 s = 32 000 B).
   - Comprobar `state == STATE_INITIALIZED`.
   - `registerAudioRecordingCallback` **antes** de `startRecording()`.
   - Hilo dedicado (`Process.THREAD_PRIORITY_URGENT_AUDIO`) leyendo con `READ_BLOCKING` en tramas de 20–32 ms.
   - Si `read()` devuelve `ERROR_DEAD_OBJECT`, recrear el `AudioRecord`.
   - Marcar los tramos silenciados, en llamada o con ceros sostenidos.
5. **Pausa**: `record.stop()` y nunca `stopForeground()`. Actualizar la notificación con `NotificationManager.notify(NOTIF_ID, …)`.
6. **Detener** (solo por acción del usuario): `stopForeground(STOP_FOREGROUND_REMOVE)` y `stopSelf()`. Guardar en `DataStore` que el usuario lo detuvo, para que la red de seguridad no insista.
7. **`onTimeout(startId, fgsType)`** (API 35): `stopSelf()`. Hoy no aplica a micro, pero es código defensivo.
8. **BootReceiver**: reprogramar alarmas desde la base de datos. Si "escucha activa" estaba a `true`, publicar la notificación "Toca para reanudar la escucha" (`PendingIntent.getForegroundService(..., FLAG_IMMUTABLE)`). **No** llamar a `startForegroundService` desde aquí: `ForegroundServiceStartNotAllowedException` o `SecurityException` en 14+.
9. **Al volver a la app**: leer `getHistoricalProcessExitReasons()` y `ApplicationStartInfo` (API 35) y mostrar "La escucha se cortó a las 13:42 (motivo)".
10. **Recordatorios**: `setExactAndAllowWhileIdle`, con `PendingIntent.getBroadcast(..., FLAG_IMMUTABLE)` y un `requestCode` estable por recordatorio. El receptor publica la notificación con un canal `IMPORTANCE_HIGH`. Sin full-screen intent.
11. **Trabajo diferido**: WorkManager 2.12.0 con restricciones de red. Registrar `getStopReason()`.
12. **Compilación**: librerías nativas alineadas a 16 KB, `compileSdk 37`, `targetSdk 36`, edge-to-edge y predictive back (Android 16).

### 16.3 Pruebas con adb (matriz mínima: emulador API 29, 31, 34, 35, 36 y 37 + un Samsung y un Xiaomi reales)

```
adb shell cmd activity stop-app <pkg>                     # Task Manager
adb shell run-as <pkg> kill -9 <pid>                      # muerte del proceso → reinicio sticky ¿recupera el micro?
adb shell dumpsys deviceidle force-idle                   # Doze
adb shell am set-standby-bucket <pkg> restricted
adb shell am compat enable FGS_BOOT_COMPLETED_RESTRICTIONS <pkg>
adb shell cmd audio set-enable-hardening throw            # Android 17, audio en segundo plano
adb shell am memory-limiter manual <pid> 300              # Android 17, límite de memoria (MB)
adb shell dumpsys activity services <pkg>                 # estado del FGS, tipo, permisos WIU
adb shell dumpsys batterystats <pkg>                      # consumo
```
Escenarios a forzar: llamada entrante; nota de voz de WhatsApp; "Hey Google" con la UI de Gemini; interruptor global del micro; reinicio del móvil; actualización del APK; 24 h con la pantalla apagada.

---

## 17. Riesgos

| Riesgo | Probabilidad / impacto | Mitigación |
|---|---|---|
| El reinicio sticky no recupera el WIU en algún OEM o versión | Media / alto (huecos sin grabar) | `try/catch` más notificación de reanudar, red de seguridad con un `PeriodicWorkRequest` y registro de salidas. Probar con `kill -9`. |
| El fabricante mata la app (Samsung, Xiaomi, OnePlus, Huawei) | Alta en esas marcas / alto | Exención de batería, guía de ajustes por marca y avisar en la UI de cada hueco detectado. |
| Silencio durante llamadas, notas de voz y Asistente | Segura / medio | Es una limitación de la plataforma: indicar los huecos en la línea de tiempo. No hay solución legítima: la accesibilidad sería un abuso y Play lo prohíbe. |
| El usuario pulsa "Detener" en el Task Manager o descarta la notificación | Media / medio | Detectarlo con `REASON_USER_REQUESTED`, avisar al volver y volver a publicar la notificación. |
| Batería: CPU despierta todo el día | Segura / alto | VAD barato, ASR por lotes, subidas agrupadas, modo nocturno y medirlo de verdad. |
| Almacenamiento | Media / medio | Opus solo para los tramos con voz y caducidad del audio en bruto (por ejemplo, 7–30 días; las transcripciones se guardan). |
| Fuga de memoria nativa con el limitador de Android 17 | Baja / alto | ASR en un proceso o hilo vigilado. Detectar `MemoryLimiter:AnonSwap`. |
| Cambios de política en Android 18 o Play | Media / medio | `targetSdk` fijado tras pruebas y revisar las páginas de cambios en cada versión. |
| Publicar en Play | Alta probabilidad de rechazo o revisión estricta | Mantener la distribución instalada a mano. Si va a Play: quitar `USE_EXACT_ALARM` y `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, y añadir divulgación, vídeo y declaración de FGS. |

---

## 18. Fuentes

- FGS, tipos: https://developer.android.com/develop/background-work/services/fgs/service-types
- Restricciones de arranque y WIU: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Lanzar un FGS: https://developer.android.com/develop/background-work/services/fgs/launch
- Cambios de FGS por versión: https://developer.android.com/develop/background-work/services/fgs/changes
- Timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout
- Task Manager: https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping
- Referencia de `Service` (START_STICKY, `startForeground`, `onTimeout`): https://developer.android.com/reference/android/app/Service
- Referencia de `Context.startForegroundService`: https://developer.android.com/reference/android/content/Context
- Notificación de FGS (diferido de 10 s): https://developer.android.com/guide/components/foreground-services
- Android 14: https://developer.android.com/about/versions/14/behavior-changes-14 · https://developer.android.com/about/versions/14/behavior-changes-all
- Android 15: https://developer.android.com/about/versions/15/behavior-changes-15 · https://developer.android.com/about/versions/15/behavior-changes-all
- Android 16: https://developer.android.com/about/versions/16/behavior-changes-16 · https://developer.android.com/about/versions/16/behavior-changes-all
- Android 17: https://developer.android.com/about/versions/17/behavior-changes-17 · https://developer.android.com/about/versions/17/behavior-changes-all · https://developer.android.com/about/versions/17/changes/bg-audio
- Limitador de memoria: https://source.android.com/docs/core/perf/memory-limiter
- Captura concurrente: https://developer.android.com/media/platform/sharing-audio-input
- `AudioSource`: https://developer.android.com/reference/android/media/MediaRecorder.AudioSource · `AudioRecord`: https://developer.android.com/reference/android/media/AudioRecord · `AudioManager`: https://developer.android.com/reference/android/media/AudioManager
- CDD 17 (§5.4 y §9.8): https://source.android.com/docs/compatibility/17/android-17-cdd · CDD 10: https://source.android.com/docs/compatibility/10/android-10-cdd
- Formatos soportados: https://developer.android.com/media/platform/supported-formats
- Indicadores e interruptores: https://developer.android.com/training/permissions/explaining-access
- Permisos de una sola vez: https://developer.android.com/training/permissions/requesting
- Hibernación: https://developer.android.com/topic/performance/app-hibernation
- Doze y App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby
- Límites de energía: https://developer.android.com/topic/performance/power/power-details
- Buckets: https://developer.android.com/topic/performance/appstandby
- Restricción de apps (AOSP): https://source.android.com/docs/core/power/app_mgmt
- Alarmas: https://developer.android.com/develop/background-work/services/alarms/schedule
- `POST_NOTIFICATIONS`: https://developer.android.com/develop/ui/views/notifications/notification-permission
- WorkManager, versiones: https://developer.android.com/jetpack/androidx/releases/work · trabajos largos: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running
- Play, FGS: https://support.google.com/googleplay/android-developer/answer/13392821 · abuso de dispositivos y redes: https://support.google.com/googleplay/android-developer/answer/9888379 · permisos sensibles y alarmas: https://support.google.com/googleplay/android-developer/answer/9888170 · datos de usuario: https://support.google.com/googleplay/android-developer/answer/10144311 · spyware: https://support.google.com/googleplay/android-developer/answer/14745000 · target SDK: https://developer.android.com/google/play/requirements/target-sdk · 16 KB: https://developer.android.com/guide/practices/page-sizes
- dontkillmyapp: https://dontkillmyapp.com/ · https://dontkillmyapp.com/samsung · https://dontkillmyapp.com/xiaomi
- Código AOSP (`main`, consultado el 15-oct-2026):
  - https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActiveServices.java
  - …/am/ServiceRecord.java · …/am/ActivityManagerConstants.java
  - …/core/java/android/app/ForegroundServiceTypePolicy.java · …/core/java/android/net/NetworkPolicyManager.java
  - …/server/power/PowerManagerService.java
  - https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audioflinger/Threads.cpp
- androidx `ServiceCompat`: https://github.com/androidx/androidx/blob/androidx-main/core/core/src/main/java/androidx/core/app/ServiceCompat.java
- Casos de la comunidad: https://github.com/minburg/outspoke/issues/37 · https://github.com/Ikolvi/Tracelet/issues/405
- Energía: DSP.Ear, https://arxiv.org/html/1409.3206
- Lanzamiento de Android 17: https://www.androidauthority.com/android-17-release-date-3639790/

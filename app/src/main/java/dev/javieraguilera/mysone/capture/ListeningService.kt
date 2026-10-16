package dev.javieraguilera.mysone.capture

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.asr.TranscriberState
import dev.javieraguilera.mysone.capture.ListeningController.ACTION_PAUSE
import dev.javieraguilera.mysone.capture.ListeningController.ACTION_RESUME
import dev.javieraguilera.mysone.capture.ListeningController.ACTION_START
import dev.javieraguilera.mysone.capture.ListeningController.ACTION_STOP
import dev.javieraguilera.mysone.capture.ListeningController.EXTRA_MINUTES
import dev.javieraguilera.mysone.capture.ListeningStatus.Error
import dev.javieraguilera.mysone.capture.ListeningStatus.Listening
import dev.javieraguilera.mysone.capture.ListeningStatus.MicBusy
import dev.javieraguilera.mysone.capture.ListeningStatus.NeedsModel
import dev.javieraguilera.mysone.capture.ListeningStatus.NeedsPermission
import dev.javieraguilera.mysone.capture.ListeningStatus.Off
import dev.javieraguilera.mysone.capture.ListeningStatus.Paused
import dev.javieraguilera.mysone.capture.ListeningStatus.Starting
import dev.javieraguilera.mysone.container
import dev.javieraguilera.mysone.core.Time
import dev.javieraguilera.mysone.data.ConvState
import dev.javieraguilera.mysone.data.GapReason
import dev.javieraguilera.mysone.data.ListeningGap
import dev.javieraguilera.mysone.data.PendingAudio
import dev.javieraguilera.mysone.data.Segment
import io.sentry.Sentry
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Servicio en primer plano de tipo microphone (arquitectura §5.1–5.3, investigación 02 §2–§4).
 *
 * Tres hilos: captura (AudioCapture) → Channel → cola en disco (coroutine IO) → ASR (un hilo propio).
 * Las órdenes (Intent) se atienden de una en una con [commands]; conversaciones, cola y huecos se tocan
 * siempre bajo [book] (cola, temporizador, ASR y órdenes). Orden de locks: commands → book, nunca al revés.
 *
 * La "sesión" va de la primera escucha a detener o error; la pausa la conserva (no se llama a
 * stopForeground, que haría perder el permiso del micro "mientras se usa").
 */
class ListeningService : Service() {
    private val c by lazy { container }
    private val db get() = c.db
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Fallo no capturado: ${e.javaClass.simpleName}")
            Sentry.captureException(e) // MysoneApp quita los mensajes antes de enviar
        },
    )
    private val commands = Mutex()
    private val book = Mutex()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val queue by lazy { PendingQueue(this, db.pendingAudio()) }
    private val tracker by lazy {
        ConversationTracker(db.conversations(), { c.settings.current().conversationGapMin * 60_000L }, ::finishLocked)
    }

    private var recovered = false
    @Volatile private var lastStartId = 0
    @Volatile private var foreground = false
    @Volatile private var capture: AudioCapture? = null
    @Volatile private var pausedUntil = 0L
    private val active get() = capture != null || pausedUntil > 0

    private var session: Job? = null
    private var chunks: Channel<VoiceChunk>? = null
    private var queueJob: Job? = null
    private var asrJob: Job? = null
    private var wake: Channel<Unit>? = null
    private var asrThread: ExecutorCoroutineDispatcher? = null
    private var acquired = false
    @Volatile private var draining = false

    @Volatile private var silenced = false
    @Volatile private var pending = 0
    @Volatile private var fragmentsToday = 0
    @Volatile private var hold = 0 // cadena con el motivo de espera de la transcripción, 0 = ninguno
    @Volatile private var lastNotification = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch { db.pendingAudio().count().collect { pending = it } }
        scope.launch {
            while (true) {
                guarded("Contadores") { fragmentsToday = db.segments().countSince(Time.startOfDay(now())).first() }
                refreshNotification()
                delay(NOTIFY_EVERY_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val action = intent?.action
        // Lo primero: después de startForegroundService el sistema da unos segundos (02 §2).
        // STOP llega con startService y no lo necesita.
        if (action != ACTION_STOP && !enterForeground()) {
            if (active) return START_STICKY // ya estábamos en primer plano: se ignora la orden
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                // Sin permiso, startForeground(MICROPHONE) lanza SecurityException (API 34+): reanudar no serviría.
                ListeningController.publish(NeedsPermission)
                c.appScope.launch { c.settings.update { it.copy(listeningEnabled = false) } }
            } else {
                ListeningController.publish(Error(getString(R.string.capture_error_start)))
                ResumeNotifier.show(this)
            }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val minutes = intent?.getIntExtra(EXTRA_MINUTES, 0) ?: 0
        command(startId) {
            when (action) {
                ACTION_START -> {
                    c.settings.update { it.copy(listeningEnabled = true) }
                    clearPause()
                    listen()
                }
                ACTION_RESUME -> {
                    clearPause()
                    listen()
                }
                ACTION_PAUSE -> if (active) pauseUntil(now() + minutes * 60_000L)
                ACTION_STOP -> stop()
                null -> restart() // reinicio sticky tras matar el proceso
            }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Hoy el tipo microphone no tiene límite de tiempo (02 §5); por si algún día lo tiene.
        command(startId) { giveUp(Error(getString(R.string.capture_error_start)), retry = true) }
    }

    /** Solo llega aquí sin pasar por detener si lo para el sistema: el latido queda para el hueco KILLED. */
    override fun onDestroy() {
        val cap = capture
        capture = null
        val job = session
        val asr = asrThread
        val release = acquired
        // La memoria nativa del modelo solo se libera cuando ya no hay ninguna transcripción en curso.
        c.appScope.launch(Dispatchers.IO) {
            cap?.stop()
            job?.cancelAndJoin()
            asr?.close()
            if (release) c.transcriber.release()
        }
        scope.cancel()
        val s = ListeningController.status.value
        if (s is Listening || s is MicBusy || s is Paused || s is Starting) ListeningController.publish(Off)
        super.onDestroy()
    }

    // ---- Órdenes ----

    private fun command(startId: Int, block: suspend () -> Unit) {
        scope.launch {
            commands.withLock {
                guarded("Orden") {
                    if (!recovered) {
                        recovered = true
                        recover()
                    }
                    block()
                }
                if (!active) {
                    ServiceCompat.stopForeground(this@ListeningService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    foreground = false
                    lastNotification = ""
                    stopSelf(startId) // no para si ha llegado otra orden después
                }
            }
        }
    }

    /** Primera orden de esta instancia: deja en orden lo que dejó a medias un proceso anterior. */
    private suspend fun recover() = book.withLock {
        // Hay latido sin "detener" ni pausa: el servicio murió escuchando.
        val alive = prefs.getLong(KEY_ALIVE, 0)
        if (alive > 0) {
            openGapLocked(GapReason.KILLED, alive)
            prefs.edit { remove(KEY_ALIVE) }
        }
        tracker.recoverOrphans()
    }

    private suspend fun restart() {
        if (!c.settings.current().listeningEnabled) return
        val until = prefs.getLong(KEY_PAUSED_UNTIL, 0)
        if (until > now()) {
            pauseUntil(until)
        } else {
            clearPause()
            listen()
        }
    }

    private suspend fun listen() {
        if (capture != null) return
        // Otra vez: un "detener" anterior pudo quitar el primer plano mientras esta orden esperaba.
        if (!enterForeground()) return giveUp(Error(getString(R.string.capture_error_start)), retry = true)
        ResumeNotifier.cancel(this)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return giveUp(NeedsPermission)
        val s = c.settings.current()
        if (!c.models.isInstalled(s.asrModelId)) return giveUp(NeedsModel)
        ListeningController.publish(Starting)
        refreshNotification()
        if (!acquired) acquired = c.transcriber.acquire()
        if (!acquired) {
            // Un modelo que no carga no se arregla reintentando: hace falta elegir otro en la app.
            val st = c.transcriber.state.value
            if (st is TranscriberState.NoModel) return giveUp(NeedsModel)
            return giveUp(Error((st as? TranscriberState.Error)?.message ?: getString(R.string.capture_error_model)))
        }
        val ch = openSession()
        val cap = AudioCapture(
            context = this,
            source = if (s.audioSource == "MIC") MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION,
            onChunk = { ch.trySend(it) }, // UNLIMITED: nunca bloquea al hilo de captura
            onLevel = ::publishLive,
            onSilenced = ::onSilenced,
            onFailure = { failed ->
                command(lastStartId) {
                    if (capture === failed) {
                        capture = null
                        giveUp(Error(getString(R.string.capture_error_mic_lost)), retry = true)
                    }
                }
            },
        )
        if (!cap.start()) return giveUp(Error(getString(R.string.capture_error_mic)), retry = true)
        capture = cap
        book.withLock { closeGapLocked() }
        publishLive(0f)
        refreshNotification()
    }

    /** Suelta el micro (se apaga el indicador) sin salir de primer plano; una alarma exacta reanuda. */
    private suspend fun pauseUntil(until: Long) {
        stopCapture()
        pausedUntil = until
        // Sin latido: si el sistema nos mata en pausa no se pierde nada, el hueco PAUSED ya lo cubre.
        prefs.edit {
            putLong(KEY_PAUSED_UNTIL, until)
            remove(KEY_ALIVE)
        }
        val am = getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, resumeAlarm())
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, resumeAlarm())
        }
        book.withLock { openGapLocked(GapReason.PAUSED) }
        ListeningController.publish(Paused(until))
        refreshNotification()
    }

    private fun clearPause() {
        pausedUntil = 0
        prefs.edit { remove(KEY_PAUSED_UNTIL) }
        getSystemService(AlarmManager::class.java).cancel(resumeAlarm())
    }

    private fun resumeAlarm() = PendingIntent.getService(
        this,
        RC_RESUME_ALARM,
        ListeningController.intent(this, ACTION_RESUME),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private suspend fun stop() {
        c.settings.update { it.copy(listeningEnabled = false) }
        ResumeNotifier.cancel(this)
        clearPause()
        stopCapture()
        ListeningController.publish(Off) // para la UI ya está detenida; la notificación dice "terminando"
        endSession(GapReason.STOPPED, drain = true)
    }

    /**
     * No se puede escuchar. [retry]: fallo del micro o del primer plano, que un toque en "Toca para reanudar"
     * puede arreglar. Si no (falta modelo o permiso, el modelo no carga), hace falta la app: deja de insistir.
     */
    private suspend fun giveUp(status: ListeningStatus, retry: Boolean = false) {
        clearPause()
        stopCapture()
        endSession(if (status is Error) GapReason.ERROR else null)
        if (!retry) c.settings.update { it.copy(listeningEnabled = false) }
        ListeningController.publish(status)
        if (retry) ResumeNotifier.show(this)
    }

    private suspend fun stopCapture() {
        val cap = capture ?: return
        capture = null
        withContext(Dispatchers.IO) { cap.stop() } // la VAD vuelca lo último al canal
        silenced = false
    }

    // ---- Sesión: cola, temporizador y ASR ----

    private fun openSession(): Channel<VoiceChunk> {
        chunks?.let { return it }
        val job = SupervisorJob(scope.coroutineContext.job)
        val ch = Channel<VoiceChunk>(Channel.UNLIMITED)
        val wake = Channel<Unit>(Channel.CONFLATED)
        val asr = Executors.newSingleThreadExecutor { Thread(it, "mysone-asr") }.asCoroutineDispatcher()
        val s = CoroutineScope(scope.coroutineContext + job)
        queueJob = s.launch(Dispatchers.IO) {
            for (chunk in ch) {
                book.withLock { guarded("Trozo perdido") { enqueueLocked(chunk) } }
                wake.trySend(Unit)
            }
        }
        s.launch {
            while (true) {
                delay(TIMER_MS)
                // Mientras la VAD oye voz no se cierra: el trozo en curso aún no ha llegado.
                book.withLock { guarded("Temporizador") { if (capture?.speaking != true) tracker.closeIfIdle(now()) } }
            }
        }
        asrJob = s.launch(asr) { transcribeLoop(wake) }
        session = job
        chunks = ch
        asrThread = asr
        this.wake = wake
        return ch
    }

    /**
     * Cierra la conversación y libera el modelo. Con [drain] (detener) termina antes de transcribir lo
     * pendiente, como mucho [DRAIN_MS]; lo que quede se queda en disco para la próxima escucha.
     */
    private suspend fun endSession(gap: String?, drain: Boolean = false) {
        chunks?.close()
        queueJob?.join() // lo que quedaba en el canal ya está en la cola de disco
        book.withLock {
            tracker.close()
            if (gap != null) openGapLocked(gap)
        }
        if (drain && asrJob != null) {
            draining = true
            refreshNotification()
            wake?.trySend(Unit)
            withTimeoutOrNull(DRAIN_MS) { asrJob?.join() }
        }
        session?.cancelAndJoin() // espera a que termine la transcripción en curso antes de liberar
        asrThread?.close()
        if (acquired) c.transcriber.release()
        acquired = false
        draining = false
        session = null
        chunks = null
        queueJob = null
        asrJob = null
        wake = null
        asrThread = null
        prefs.edit { remove(KEY_ALIVE) }
    }

    private suspend fun enqueueLocked(chunk: VoiceChunk) {
        // ponytail: el tope cuenta la cola entera con una suma por trozo; basta con miles de filas.
        if (queue.totalDurationMs() >= MAX_QUEUE_MS) return openGapLocked(GapReason.BATTERY)
        closeGapLocked(GapReason.BATTERY)
        val id = tracker.assign(chunk.startMs, chunk.endMs)
        queue.add(id, chunk.startMs, chunk.endMs, chunk.samples)
    }

    /** Hasta que se cancela la sesión o, al detener ([draining]), hasta vaciar la cola. */
    private suspend fun transcribeLoop(wake: Channel<Unit>) {
        while (true) {
            try {
                if (!waitWhileHeld()) return
                val p = queue.next()
                if (p != null) {
                    transcribe(p)
                } else {
                    if (draining) return
                    wake.receive()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // El intento ya cuenta: tras MAX_ATTEMPTS el trozo se descarta.
                Log.w(TAG, "Fallo al transcribir: ${e.javaClass.simpleName}")
                delay(RETRY_MS)
            }
        }
    }

    private suspend fun transcribe(p: PendingAudio) {
        if (p.attempts >= MAX_ATTEMPTS) {
            Log.w(TAG, "Trozo descartado tras $MAX_ATTEMPTS intentos")
            book.withLock {
                queue.remove(p)
                finishLocked(p.conversationId)
            }
            return
        }
        queue.bumpAttempts(p) // antes de decodificar: si el nativo mata el proceso, no se repite sin fin
        val text = queue.read(p)?.let { c.transcriber.transcribe(it) }.orEmpty()
        book.withLock {
            // "Olvidar" pudo quitar el trozo mientras se transcribía: entonces su texto no se guarda.
            if (text.isNotBlank() && queue.isQueued(p)) {
                val seg = Segment(conversationId = p.conversationId, startedAt = p.startedAt, endedAt = p.endedAt, text = text)
                c.ai.onSegmentTranscribed(seg.copy(id = db.segments().insert(seg)))
            }
            queue.remove(p)
            finishLocked(p.conversationId)
        }
    }

    /**
     * Batería baja sin cargar (si el ahorro está activo) o móvil muy caliente: se encola sin transcribir.
     * false si hay que esperar pero estamos deteniendo: lo pendiente queda para la próxima escucha.
     */
    private suspend fun waitWhileHeld(): Boolean {
        while (true) {
            val reason = holdReason()
            if (reason != hold) {
                hold = reason
                refreshNotification()
            }
            if (reason == 0) return true
            if (draining) return false
            delay(HOLD_POLL_MS) // ponytail: sondeo cada minuto en vez de escuchar ACTION_POWER_CONNECTED
        }
    }

    private suspend fun holdReason(): Int {
        if (getSystemService(PowerManager::class.java).currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            return R.string.capture_hold_thermal
        }
        val bm = getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) // MIN_VALUE si no se sabe
        if (c.settings.current().batterySaver && !bm.isCharging && level in 0 until LOW_BATTERY) return R.string.capture_hold_battery
        return 0
    }

    /** Conversación READY sin audio pendiente: a la IA, o fuera si no quedó ningún segmento. Con [book]. */
    private suspend fun finishLocked(id: Long) {
        val conv = db.conversations().get(id) ?: return
        if (conv.state != ConvState.READY || db.pendingAudio().countForConversation(id) > 0) return
        if (db.segments().countForConversation(id) == 0) db.conversations().delete(id) else c.ai.onConversationReady(id)
    }

    // ---- Huecos (con [book]). Como mucho uno abierto: abrir otro cierra el anterior. ----

    private suspend fun openGapLocked(reason: String, at: Long = now()) {
        val open = db.gaps().findOpen()
        if (open?.reason == reason) return
        // El latido puede ser anterior al inicio del hueco abierto (KILLED justo tras un MIC_BUSY).
        if (open != null) db.gaps().setEnded(open.id, maxOf(at, open.startedAt))
        db.gaps().insert(ListeningGap(startedAt = at, reason = reason))
    }

    /** Sin [reason] cierra el que haya; con él, solo si es de ese motivo. */
    private suspend fun closeGapLocked(reason: String? = null) {
        val open = db.gaps().findOpen() ?: return
        if (reason == null || open.reason == reason) db.gaps().setEnded(open.id, now())
    }

    // ---- Estado y notificación ----

    /** Desde el hilo de captura, 4 veces por segundo. */
    private fun publishLive(level: Float) {
        ListeningController.publish(if (silenced) MicBusy else Listening(level, pending))
    }

    private fun onSilenced(s: Boolean) {
        if (capture == null || s == silenced) return
        silenced = s
        publishLive(0f)
        refreshNotification()
        scope.launch { book.withLock { if (s) openGapLocked(GapReason.MIC_BUSY) else closeGapLocked(GapReason.MIC_BUSY) } }
    }

    private fun enterForeground(): Boolean = try {
        ServiceCompat.startForeground(this, ListeningNotification.ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        foreground = true
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException, SecurityException (sin permiso "mientras se usa"),
        // InvalidForegroundServiceTypeException, IllegalArgumentException… (02 §2)
        Log.w(TAG, "startForeground falló: ${e.javaClass.simpleName}")
        false
    }

    /** Cambios de estado al momento; contadores con el latido de 10 s. Solo publica si cambia algo. */
    private fun refreshNotification() {
        if (capture != null) prefs.edit { putLong(KEY_ALIVE, now()) } // latido para el hueco KILLED
        if (!foreground) return
        val st = ListeningController.status.value
        val key = when (st) {
            is Listening -> "L"
            is Paused -> "P${st.untilMs}"
            else -> st.toString()
        } + "|$fragmentsToday|$pending|$hold|$draining"
        if (key == lastNotification) return
        lastNotification = key
        getSystemService(NotificationManager::class.java).notify(ListeningNotification.ID, notification())
    }

    private fun notification() =
        ListeningNotification.build(this, ListeningController.status.value, fragmentsToday, pending, hold, finishing = draining)

    private suspend inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what: ${e.javaClass.simpleName}") // nunca el mensaje: podría llevar texto
        }
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TAG = "ListeningService"
        const val PREFS = "capture"
        const val KEY_ALIVE = "lastAliveAt"
        const val KEY_PAUSED_UNTIL = "pausedUntil"
        const val RC_RESUME_ALARM = 20
        const val NOTIFY_EVERY_MS = 10_000L
        const val TIMER_MS = 15_000L
        const val HOLD_POLL_MS = 60_000L
        const val RETRY_MS = 5_000L
        const val DRAIN_MS = 30_000L
        const val MAX_ATTEMPTS = 3
        const val MAX_QUEUE_MS = 4 * 3_600_000L
        const val LOW_BATTERY = 20
    }
}

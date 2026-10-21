package dev.javieraguilera.mysone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.StrictMode
import dev.javieraguilera.mysone.ai.ProcessWorker
import dev.javieraguilera.mysone.asr.fallbackModelId
import dev.javieraguilera.mysone.tasks.MaintenanceWorker
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MysoneApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Solo en debug: avisa en logcat (con la pila de quien lo abrió) de cada Closeable o cursor sin cerrar.
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects().penaltyLog().build(),
            )
        }
        initSentry()
        container = AppContainer(this)
        createChannels()
        MaintenanceWorker.schedule(this)
        // Al configurar la IA (o al arrancar ya configurada) se procesa lo que esperaba en READY.
        container.appScope.launch { container.ai.isConfigured.filter { it }.collect { ProcessWorker.enqueue(this@MysoneApp) } }
        // Escucha e importación usan solo el modelo elegido: si falta y hay otro instalado, se elige ese.
        container.appScope.launch {
            val models = container.models
            combine(models.installs, container.settings.data.map { it.asrModelId }) { installs, chosen -> fallbackModelId(installs, chosen) }
                .filterNotNull()
                .collect { container.settings.update { s -> fallbackModelId(models.installs.value, s.asrModelId)?.let { s.copy(asrModelId = it) } ?: s } }
        }
    }

    /** Solo con DSN en la compilación. Ni PII, ni breadcrumbs, ni adjuntos, ni textos de error: solo tipo y pila. */
    @Suppress("DEPRECATION")
    private fun initSentry() {
        if (BuildConfig.SENTRY_DSN.isEmpty()) return
        SentryAndroid.init(this) { o ->
            o.dsn = BuildConfig.SENTRY_DSN
            // Deprecado en 8.x a favor de dataCollection, pero tocar dataCollection hace que todo lo que
            // no se configure pase a recogerse (DataCollectionResolver). Sin tocarlo, esto es lo más privado.
            o.isSendDefaultPii = false
            o.maxBreadcrumbs = 0
            o.setBeforeBreadcrumb { _, _ -> null }
            o.isAttachScreenshot = false
            o.isAttachViewHierarchy = false
            o.setBeforeSend { event, _ ->
                event.message = null
                event.setExtras(emptyMap())
                event.user = null
                event.request = null
                event.breadcrumbs = null
                // Los mensajes de excepción pueden llevar texto transcrito o JSON de la IA.
                event.exceptions?.forEach { it.value = null }
                event.contexts.keys().toList().filterNot { it in STANDARD_CONTEXTS }.forEach { event.contexts.remove(it) }
                event
            }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                channel(CHANNEL_LISTENING, R.string.channel_listening_name, R.string.channel_listening_desc, NotificationManager.IMPORTANCE_LOW),
                channel(CHANNEL_REMINDERS, R.string.channel_reminders_name, R.string.channel_reminders_desc, NotificationManager.IMPORTANCE_HIGH),
                channel(CHANNEL_ALERTS, R.string.channel_alerts_name, R.string.channel_alerts_desc, NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun channel(id: String, name: Int, description: Int, importance: Int) =
        NotificationChannel(id, getString(name), importance).apply { this.description = getString(description) }

    companion object {
        /** Notificación de la escucha (importancia baja, siempre visible mientras escucha). */
        const val CHANNEL_LISTENING = "listening"

        /** Recordatorios de tareas (importancia alta). */
        const val CHANNEL_REMINDERS = "reminders"

        /** "La escucha se ha detenido. Toca para reanudar" (importancia normal). */
        const val CHANNEL_ALERTS = "alerts"

        private val STANDARD_CONTEXTS = setOf("app", "device", "os", "runtime", "trace", "gpu")
    }
}

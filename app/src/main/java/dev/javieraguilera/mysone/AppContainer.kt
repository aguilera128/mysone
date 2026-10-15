package dev.javieraguilera.mysone

import android.app.Application
import android.content.Context
import dev.javieraguilera.mysone.ai.AiProcessing
import dev.javieraguilera.mysone.ai.AskEngine
import dev.javieraguilera.mysone.asr.ModelManager
import dev.javieraguilera.mysone.asr.Transcriber
import dev.javieraguilera.mysone.data.AppDatabase
import dev.javieraguilera.mysone.data.SecretStore
import dev.javieraguilera.mysone.data.SettingsStore
import dev.javieraguilera.mysone.tasks.DataManager
import dev.javieraguilera.mysone.tasks.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** DI manual. Todo perezoso: nada se crea hasta que se usa y el orden de declaración no importa. */
class AppContainer(val app: Application) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.create(app) }
    val settings: SettingsStore by lazy { SettingsStore(app) }
    val secrets: SecretStore by lazy { SecretStore(app) }
    val models: ModelManager by lazy { ModelManager(app) }
    val transcriber: Transcriber by lazy { Transcriber(app, models, settings) }
    val ai: AiProcessing by lazy { AiProcessing(this) }
    val ask: AskEngine by lazy { AskEngine(this) }
    val reminders: ReminderScheduler by lazy { ReminderScheduler(app) }
    val data: DataManager by lazy { DataManager(this) }
}

/** Atajo de `(context.applicationContext as MysoneApp).container`. */
val Context.container: AppContainer get() = (applicationContext as MysoneApp).container

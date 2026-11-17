package dev.javieraguilera.mysone.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val onboardingDone: Boolean = false,
    val legalAcceptedAt: Long? = null,
    val listeningEnabled: Boolean = false,          // intención del usuario; sirve para "Toca para reanudar"
    val audioSource: String = "VOICE_RECOGNITION",  // o "MIC" (voz lejana)
    val conversationGapMin: Int = 4,                // silencio que cierra una conversación (2–15)
    val asrModelId: String = "canary-180m-es",
    val speechLanguage: String = "",                // idioma en que se habla (Canary): "" = el del teléfono
    val downloadWifiOnly: Boolean = true,           // modelos ASR: cientos de MB
    val batterySaver: Boolean = true,               // batería < 20 % y sin cargar → se encola sin transcribir
    val retentionDays: Int = 90,                    // transcripción literal; 0 = no borrar
    val secureScreen: Boolean = true,               // FLAG_SECURE
    val quickReminders: Boolean = true,             // detectar "recuérdame…" al momento
    val callFolder: String? = null,                 // URI (árbol SAF) de la carpeta de grabaciones de llamadas
    val callDeleteAfter: Boolean = true,            // borrar la grabación cuando ya está transcrita
    val ai: AiSettings = AiSettings(),
)

@Serializable
data class AiSettings(
    val enabled: Boolean = false,
    val consentAt: Long? = null,
    val presetId: String = "anthropic",
    val provider: String = "ANTHROPIC",             // o "OPENAI_COMPAT" (ver ai/AiPresets.kt)
    val baseUrl: String = "https://api.anthropic.com",
    val modelBackground: String = "claude-haiku-5-5",
    val modelAnswer: String = "claude-opus-5-5",
    val jsonMode: String = "schema",                // "schema" (json_schema estricto) | "object" (json_object)
)

private val Context.settingsStore by preferencesDataStore("settings")

/** Ajustes en DataStore Preferences. Los valores ausentes toman el valor por defecto de las data class. */
class SettingsStore(private val context: Context) {

    val data: Flow<AppSettings> = context.settingsStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::read)
        .distinctUntilChanged()

    suspend fun current(): AppSettings = data.first()

    /** Lectura-modificación-escritura atómica: `update { it.copy(secureScreen = false) }`. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private object K {
        val onboardingDone = booleanPreferencesKey("onboardingDone")
        val legalAcceptedAt = longPreferencesKey("legalAcceptedAt")
        val listeningEnabled = booleanPreferencesKey("listeningEnabled")
        val audioSource = stringPreferencesKey("audioSource")
        val conversationGapMin = intPreferencesKey("conversationGapMin")
        val asrModelId = stringPreferencesKey("asrModelId")
        val speechLanguage = stringPreferencesKey("speechLanguage")
        val downloadWifiOnly = booleanPreferencesKey("downloadWifiOnly")
        val batterySaver = booleanPreferencesKey("batterySaver")
        val retentionDays = intPreferencesKey("retentionDays")
        val secureScreen = booleanPreferencesKey("secureScreen")
        val quickReminders = booleanPreferencesKey("quickReminders")
        val callFolder = stringPreferencesKey("callFolder")
        val callDeleteAfter = booleanPreferencesKey("callDeleteAfter")
        val aiEnabled = booleanPreferencesKey("ai.enabled")
        val aiConsentAt = longPreferencesKey("ai.consentAt")
        val aiPresetId = stringPreferencesKey("ai.presetId")
        val aiProvider = stringPreferencesKey("ai.provider")
        val aiBaseUrl = stringPreferencesKey("ai.baseUrl")
        val aiModelBackground = stringPreferencesKey("ai.modelBackground")
        val aiModelAnswer = stringPreferencesKey("ai.modelAnswer")
        val aiJsonMode = stringPreferencesKey("ai.jsonMode")
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        val a = d.ai
        return AppSettings(
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            legalAcceptedAt = p[K.legalAcceptedAt],
            listeningEnabled = p[K.listeningEnabled] ?: d.listeningEnabled,
            audioSource = p[K.audioSource] ?: d.audioSource,
            conversationGapMin = (p[K.conversationGapMin] ?: d.conversationGapMin).coerceIn(2, 15),
            asrModelId = p[K.asrModelId] ?: d.asrModelId,
            speechLanguage = p[K.speechLanguage] ?: d.speechLanguage,
            downloadWifiOnly = p[K.downloadWifiOnly] ?: d.downloadWifiOnly,
            batterySaver = p[K.batterySaver] ?: d.batterySaver,
            retentionDays = (p[K.retentionDays] ?: d.retentionDays).coerceAtLeast(0),
            secureScreen = p[K.secureScreen] ?: d.secureScreen,
            quickReminders = p[K.quickReminders] ?: d.quickReminders,
            callFolder = p[K.callFolder],
            callDeleteAfter = p[K.callDeleteAfter] ?: d.callDeleteAfter,
            ai = AiSettings(
                enabled = p[K.aiEnabled] ?: a.enabled,
                consentAt = p[K.aiConsentAt],
                presetId = p[K.aiPresetId] ?: a.presetId,
                provider = p[K.aiProvider] ?: a.provider,
                baseUrl = p[K.aiBaseUrl] ?: a.baseUrl,
                modelBackground = p[K.aiModelBackground] ?: a.modelBackground,
                modelAnswer = p[K.aiModelAnswer] ?: a.modelAnswer,
                jsonMode = p[K.aiJsonMode] ?: a.jsonMode,
            ),
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[K.onboardingDone] = s.onboardingDone
        p.setOrRemove(K.legalAcceptedAt, s.legalAcceptedAt)
        p[K.listeningEnabled] = s.listeningEnabled
        p[K.audioSource] = s.audioSource
        p[K.conversationGapMin] = s.conversationGapMin
        p[K.asrModelId] = s.asrModelId
        p[K.speechLanguage] = s.speechLanguage
        p[K.downloadWifiOnly] = s.downloadWifiOnly
        p[K.batterySaver] = s.batterySaver
        p[K.retentionDays] = s.retentionDays
        p[K.secureScreen] = s.secureScreen
        p[K.quickReminders] = s.quickReminders
        p.setOrRemove(K.callFolder, s.callFolder)
        p[K.callDeleteAfter] = s.callDeleteAfter
        p[K.aiEnabled] = s.ai.enabled
        p.setOrRemove(K.aiConsentAt, s.ai.consentAt)
        p[K.aiPresetId] = s.ai.presetId
        p[K.aiProvider] = s.ai.provider
        p[K.aiBaseUrl] = s.ai.baseUrl
        p[K.aiModelBackground] = s.ai.modelBackground
        p[K.aiModelAnswer] = s.ai.modelAnswer
        p[K.aiJsonMode] = s.ai.jsonMode
    }

    private fun <T> MutablePreferences.setOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else set(key, value)
    }
}

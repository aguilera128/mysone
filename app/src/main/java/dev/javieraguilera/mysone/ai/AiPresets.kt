package dev.javieraguilera.mysone.ai

import androidx.annotation.StringRes
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.data.AiSettings

object AiProvider {
    const val ANTHROPIC = "ANTHROPIC"         // Messages API nativa
    const val OPENAI_COMPAT = "OPENAI_COMPAT" // POST {baseUrl}/chat/completions
}

object JsonMode {
    const val SCHEMA = "schema" // json_schema estricto
    const val OBJECT = "object" // json_object (DeepSeek y compatibles sin esquema)
}

/** Proveedor preconfigurado. Los textos visibles van en strings.xml. */
data class AiPreset(
    val id: String,
    @StringRes val nameRes: Int,
    val provider: String,
    val baseUrl: String,
    val modelBackground: String,
    val modelAnswer: String,
    val jsonMode: String,
    @StringRes val noticeRes: Int?,  // aviso de privacidad que se muestra al elegirlo
    val discouraged: Boolean = false, // se marca como desaconsejado en la UI
    val editable: Boolean = false,    // URL y modelos los escribe el usuario
) {
    /** Ajustes de IA con este preset, conservando activación y consentimiento. */
    fun applyTo(ai: AiSettings): AiSettings = ai.copy(
        presetId = id,
        provider = provider,
        baseUrl = baseUrl,
        modelBackground = modelBackground,
        modelAnswer = modelAnswer,
        jsonMode = jsonMode,
    )
}

/** Tabla de docs/arquitectura.md §7.2 (coste y privacidad en docs/investigacion/05). */
object AiPresets {
    val all: List<AiPreset> = listOf(
        AiPreset(
            id = "anthropic",
            nameRes = R.string.ai_preset_anthropic,
            provider = AiProvider.ANTHROPIC,
            baseUrl = "https://api.anthropic.com",
            modelBackground = "claude-haiku-5-5",
            modelAnswer = "claude-opus-5-5",
            jsonMode = JsonMode.SCHEMA,
            noticeRes = R.string.ai_notice_anthropic,
        ),
        AiPreset(
            id = "mistral",
            nameRes = R.string.ai_preset_mistral,
            provider = AiProvider.OPENAI_COMPAT,
            baseUrl = "https://api.mistral.ai/v1",
            modelBackground = "mistral-small-latest",
            modelAnswer = "mistral-medium-latest",
            jsonMode = JsonMode.SCHEMA,
            noticeRes = R.string.ai_notice_mistral,
        ),
        AiPreset(
            id = "deepseek",
            nameRes = R.string.ai_preset_deepseek,
            provider = AiProvider.OPENAI_COMPAT,
            baseUrl = "https://api.deepseek.com",
            modelBackground = "deepseek-flash",
            modelAnswer = "deepseek-flash",
            jsonMode = JsonMode.OBJECT,
            noticeRes = R.string.ai_notice_deepseek,
            discouraged = true,
        ),
        AiPreset(
            id = "custom",
            nameRes = R.string.ai_preset_custom,
            provider = AiProvider.OPENAI_COMPAT,
            baseUrl = "",
            modelBackground = "",
            modelAnswer = "",
            jsonMode = JsonMode.OBJECT,
            noticeRes = null,
            editable = true,
        ),
    )

    fun byId(id: String): AiPreset? = all.firstOrNull { it.id == id }
}

package dev.javieraguilera.mysone.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/**
 * `POST {baseUrl}/chat/completions` compatible con OpenAI: Mistral, DeepSeek y "Personalizado"
 * (docs/investigacion/05 §6.2). Con [JsonMode.OBJECT] el prompt ya describe el JSON y lleva la palabra JSON.
 */
class OpenAiCompatClient(
    baseUrl: String,
    private val apiKey: String,
    private val jsonMode: String,
    private val http: OkHttpClient = defaultHttp,
) : LlmClient {
    private val url = baseUrl.trimEnd('/') + "/chat/completions"

    // DeepSeek piensa por defecto; para estas tareas se apaga (05 §2.2).
    private val deepSeek = "deepseek.com" in baseUrl

    /** `finish_reason: length` → un reintento con el doble. */
    override suspend fun complete(req: LlmRequest): LlmResponse =
        send(req, req.maxTokens) ?: send(req, req.maxTokens * 2)
            ?: throw LlmException(LlmErrorKind.BAD_OUTPUT, "length")

    private suspend fun send(req: LlmRequest, maxTokens: Int): LlmResponse? = withRetries {
        val reply = http.postJson(url, mapOf("Authorization" to "Bearer $apiKey"), openAiBody(req, maxTokens, jsonMode, deepSeek))
        if (reply.code != 200) throw httpError(reply)
        parseOpenAi(reply.body)
    }
}

// ponytail: `max_tokens` vale para Mistral, DeepSeek, Groq y OpenRouter; los modelos de razonamiento de
// OpenAI piden `max_completion_tokens`. Añadirlo si alguien usa OpenAI con el preset personalizado.
internal fun openAiBody(req: LlmRequest, maxTokens: Int, jsonMode: String, deepSeek: Boolean): JsonObject = buildJsonObject {
    put("model", req.model)
    put("max_tokens", maxTokens)
    putJsonArray("messages") {
        addJsonObject { put("role", "system"); put("content", req.system) }
        req.messages.forEach { addJsonObject { put("role", it.role); put("content", it.text) } }
    }
    req.schema?.let { schema ->
        putJsonObject("response_format") {
            if (jsonMode == JsonMode.SCHEMA) {
                put("type", "json_schema")
                putJsonObject("json_schema") { put("name", "mysone"); put("schema", schema); put("strict", true) }
            } else {
                put("type", "json_object")
            }
        }
    }
    if (deepSeek) putJsonObject("thinking") { put("type", "disabled") }
}

/** null = cortada (`finish_reason: length`). Contenido vacío → BAD_OUTPUT (DeepSeek a veces lo devuelve así). */
internal fun parseOpenAi(body: String): LlmResponse? {
    val root = parseObject(body)
    val choice = (root["choices"] as? JsonArray)?.firstOrNull().obj()
        ?: throw LlmException(LlmErrorKind.BAD_OUTPUT, "Sin choices")
    when (choice.str("finish_reason")) {
        "length" -> return null
        "content_filter" -> throw LlmException(LlmErrorKind.REFUSAL, "content_filter")
    }
    val message = choice["message"].obj()
    if (message.str("refusal") != null) throw LlmException(LlmErrorKind.REFUSAL, "refusal")
    // Algunos modelos (los de razonamiento de Mistral) devuelven el contenido como lista de partes.
    val text = when (val content = message?.get("content")) {
        is JsonArray -> content.filter { it.obj().str("type") == "text" }.joinToString("") { it.obj().str("text").orEmpty() }
        is JsonPrimitive -> content.takeIf { it.isString }?.content
        else -> null
    }?.takeIf { it.isNotBlank() } ?: throw LlmException(LlmErrorKind.BAD_OUTPUT, "Respuesta vacía")
    val usage = root["usage"].obj()
    val cached = usage?.get("prompt_tokens_details").obj().int("cached_tokens")
        .takeIf { it > 0 } ?: usage.int("prompt_cache_hit_tokens") // OpenAI y Mistral · DeepSeek
    return LlmResponse(text, usage.int("prompt_tokens"), usage.int("completion_tokens"), cached)
}

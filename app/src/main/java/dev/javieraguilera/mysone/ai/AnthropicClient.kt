package dev.javieraguilera.mysone.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

/**
 * Messages API de Anthropic por HTTP (docs/investigacion/05 §6.1). Sin temperature/top_p/prefill/thinking:
 * en los modelos 5.5 dan 400. El esfuerzo y el formato JSON van en `output_config`.
 */
class AnthropicClient(
    baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = defaultHttp,
) : LlmClient {
    private val url = baseUrl.trimEnd('/') + "/v1/messages"

    /** `stop_reason: max_tokens` → un reintento con el doble. */
    override suspend fun complete(req: LlmRequest): LlmResponse =
        send(req, req.maxTokens) ?: send(req, req.maxTokens * 2)
            ?: throw LlmException(LlmErrorKind.BAD_OUTPUT, "max_tokens")

    private suspend fun send(req: LlmRequest, maxTokens: Int): LlmResponse? = withRetries {
        val fallbacks = req.model in FALLBACK_MODELS && !fallbacksRejected
        var reply = post(req, maxTokens, fallbacks)
        if (fallbacks && reply.code == 400 && reply.body.contains("fallback", ignoreCase = true)) {
            fallbacksRejected = true
            reply = post(req, maxTokens, fallbacks = false)
        }
        if (reply.code != 200) throw httpError(reply)
        parseAnthropic(reply.body)
    }

    private suspend fun post(req: LlmRequest, maxTokens: Int, fallbacks: Boolean): HttpReply {
        val headers = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", API_VERSION)
            if (fallbacks) put("anthropic-beta", FALLBACK_BETA)
        }
        return http.postJson(url, headers, anthropicBody(req, maxTokens, fallbacks))
    }

    internal companion object {
        const val API_VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Haiku 5.5 no tiene fallback en servidor: con él no se manda. */
        val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-sonnet-5-5")

        /** La API rechazó `fallbacks` con un 400: no se vuelve a mandar hasta que se reinicie la app. */
        @Volatile var fallbacksRejected = false
    }
}

internal fun anthropicBody(req: LlmRequest, maxTokens: Int, fallbacks: Boolean): JsonObject = buildJsonObject {
    put("model", req.model)
    put("max_tokens", maxTokens)
    putJsonArray("system") {
        addJsonObject {
            put("type", "text")
            put("text", req.system)
            if (req.cacheSystem) putJsonObject("cache_control") { put("type", "ephemeral") }
        }
    }
    putJsonArray("messages") {
        req.messages.forEach { addJsonObject { put("role", it.role); put("content", it.text) } }
    }
    if (req.effort != null || req.schema != null) {
        putJsonObject("output_config") {
            req.effort?.let { put("effort", it) }
            req.schema?.let { schema ->
                putJsonObject("format") { put("type", "json_schema"); put("schema", schema) }
            }
        }
    }
    if (fallbacks) put("fallbacks", "default")
}

/**
 * Texto del primer bloque `text` (puede haber antes bloques `thinking` o `fallback`).
 * null = cortada por `max_tokens`. `refusal` → [LlmErrorKind.REFUSAL].
 */
internal fun parseAnthropic(body: String): LlmResponse? {
    val root = parseObject(body)
    when (root.str("stop_reason")) {
        "refusal" -> throw LlmException(LlmErrorKind.REFUSAL, "refusal")
        "max_tokens" -> return null
    }
    val text = runCatching { root["content"]?.jsonArray }.getOrNull()
        ?.firstOrNull { it.obj().str("type") == "text" }.obj()
        ?.get("text")?.let { (it as? JsonPrimitive)?.content }
        ?: throw LlmException(LlmErrorKind.BAD_OUTPUT, "Sin bloque de texto")
    val usage = root["usage"].obj()
    return LlmResponse(text, usage.int("input_tokens"), usage.int("output_tokens"), usage.int("cache_read_input_tokens"))
}

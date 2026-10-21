package dev.javieraguilera.mysone.ai

import androidx.annotation.StringRes
import dev.javieraguilera.mysone.R
import dev.javieraguilera.mysone.core.AppJson
import dev.javieraguilera.mysone.data.AiSettings
import java.io.IOException
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Clientes de IA por HTTP directo (§7.1). Nunca se registran cuerpos de petición ni de respuesta.

data class LlmMessage(val role: String /* user | assistant */, val text: String)

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val model: String,
    val maxTokens: Int,
    val schema: JsonObject? = null,
    val effort: String? = null,
    val cacheSystem: Boolean = true,
)

data class LlmResponse(val text: String, val inputTokens: Int, val outputTokens: Int, val cachedTokens: Int)

interface LlmClient {
    /** Lanza [LlmException]. */
    suspend fun complete(req: LlmRequest): LlmResponse
}

enum class LlmErrorKind(@StringRes val messageRes: Int) {
    AUTH(R.string.ai_error_auth),
    RATE(R.string.ai_error_rate),
    NETWORK(R.string.ai_error_network),
    SERVER(R.string.ai_error_server),
    BAD_OUTPUT(R.string.ai_error_bad_output),
    REFUSAL(R.string.ai_error_refusal),
    /** http:// que el networkSecurityConfig no deja (release): hay que cambiar la URL a https://. */
    INSECURE_URL(R.string.ai_error_insecure_url),
    /** El servidor no da una conexión TLS válida (certificado, o sin https en esa dirección). Hay red: no es NETWORK. */
    TLS(R.string.ai_error_tls),
}

private val RETRYABLE = setOf(LlmErrorKind.RATE, LlmErrorKind.NETWORK, LlmErrorKind.SERVER)

/**
 * [message] es corto y técnico (código HTTP, tipo de error): nunca lleva contenido. Para la UI, [LlmErrorKind.messageRes].
 * [truncated]: BAD_OUTPUT porque la respuesta se cortó por el límite de tokens también con el doble; repetir la misma
 * petición volvería a cortarse.
 */
class LlmException(
    val kind: LlmErrorKind,
    message: String,
    val retryAfterMs: Long? = null,
    val retryable: Boolean = kind in RETRYABLE,
    val httpCode: Int? = null,
    val truncated: Boolean = false,
) : Exception(message)

/**
 * 400, 413, 422…: el servidor no acepta esta petición en concreto (contenido que filtra, demasiado grande). Otra
 * conversación puede pasar; 404 es la URL o el modelo, y clave, cuota y tiempo de espera tienen su propio tipo.
 */
internal fun LlmException.isRequestError(): Boolean =
    kind == LlmErrorKind.SERVER && !retryable && httpCode != null && httpCode in 400..499 && httpCode != 404

/** Cliente según el proveedor de los ajustes. */
internal fun llmClient(ai: AiSettings, apiKey: String, http: OkHttpClient = defaultHttp): LlmClient =
    when (ai.provider) {
        AiProvider.ANTHROPIC -> AnthropicClient(ai.baseUrl, apiKey, http)
        else -> OpenAiCompatClient(ai.baseUrl, apiKey, ai.jsonMode, http)
    }

/** Uno para toda la app: comparte el pool de conexiones. */
internal val defaultHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
}

private val JSON_TYPE = "application/json".toMediaType()

internal class HttpReply(val code: Int, val body: String, val retryAfter: String?)

/** POST con cuerpo JSON. El cuerpo va como bytes para que la cabecera sea exactamente `application/json`. */
internal suspend fun OkHttpClient.postJson(url: String, headers: Map<String, String>, body: JsonObject): HttpReply =
    withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toString().encodeToByteArray().toRequestBody(JSON_TYPE))
                .build()
        } catch (_: IllegalArgumentException) {
            throw LlmException(LlmErrorKind.SERVER, "URL no válida", retryable = false)
        }
        try {
            newCall(request).execute().use { HttpReply(it.code, it.body.string(), it.header("retry-after")) }
        } catch (_: UnknownServiceException) {
            // http:// que el networkSecurityConfig no deja (release): reintentar no lo arregla, hay que cambiar la URL.
            throw LlmException(LlmErrorKind.INSECURE_URL, "Texto plano no permitido", retryable = false)
        } catch (e: SSLException) {
            // Certificado no válido o caducado, o un servidor sin TLS en una URL https://: reintentar no lo arregla.
            throw LlmException(LlmErrorKind.TLS, e.javaClass.simpleName)
        } catch (e: IOException) {
            throw LlmException(LlmErrorKind.NETWORK, e.javaClass.simpleName)
        }
    }

/** Error HTTP → [LlmException]. Del cuerpo solo se toma `error.type`, que no lleva contenido. */
internal fun httpError(reply: HttpReply): LlmException {
    val type = runCatching { (AppJson.parseToJsonElement(reply.body) as? JsonObject)?.get("error").obj().str("type") }
        .getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,40}")) }
    val msg = "HTTP ${reply.code}" + (type?.let { " ($it)" } ?: "")
    val retryAfter = reply.retryAfter?.toDoubleOrNull()?.let { (it * 1000).toLong() }
    return when (reply.code) {
        401, 403 -> LlmException(LlmErrorKind.AUTH, msg)
        408 -> LlmException(LlmErrorKind.NETWORK, msg)
        429 -> LlmException(LlmErrorKind.RATE, msg, retryAfter)
        in 500..599 -> LlmException(LlmErrorKind.SERVER, msg, retryAfter)
        else -> LlmException(LlmErrorKind.SERVER, msg, retryable = false, httpCode = reply.code) // 400, 404…: URL, modelo o petición
    }
}

/** 2 reintentos con espera exponencial (2 s, 4 s) o la de `retry-after` si no pasa de 1 min. */
internal suspend fun <T> withRetries(block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: LlmException) {
            val wait = e.retryAfterMs ?: (2_000L shl attempt)
            if (!e.retryable || attempt == 2 || wait > 60_000) throw e
            delay(wait)
            attempt++
        }
    }
}

/** Cuerpo de respuesta como objeto JSON; si no lo es, BAD_OUTPUT. */
internal fun parseObject(body: String): JsonObject =
    runCatching { AppJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
        ?: throw LlmException(LlmErrorKind.BAD_OUTPUT, "Respuesta no JSON")

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject?.int(key: String): Int = (this?.get(key) as? JsonPrimitive)?.intOrNull ?: 0

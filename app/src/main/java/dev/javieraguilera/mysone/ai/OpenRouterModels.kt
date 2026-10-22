package dev.javieraguilera.mysone.ai

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** Modelo del catálogo de OpenRouter. Precios en $ por millón de tokens (null si no lo da). */
data class OpenRouterModel(
    val id: String,
    val name: String,
    val createdAt: Long, // ms: cuándo llegó a OpenRouter
    val promptPerM: Double?,
    val completionPerM: Double?,
    val contextLength: Int,
)

/**
 * Catálogo vivo de OpenRouter (`GET /models`, público: no se envía la clave). Así los modelos que van saliendo se
 * pueden elegir sin actualizar la app. Solo los que admiten salida estructurada (Mysone pide JSON con esquema), los
 * más nuevos primero.
 */
object OpenRouterModels {
    const val BASE_URL = "https://openrouter.ai/api/v1"
    private const val MAX_BYTES = 8L * 1024 * 1024 // la lista entera ocupa ~0,7 MB; esto acota una respuesta rara

    fun isOpenRouter(baseUrl: String): Boolean = runCatching { URI(baseUrl.trim()).host }.getOrNull().equals("openrouter.ai", ignoreCase = true)

    /** Lanza [LlmException] (NETWORK, SERVER, BAD_OUTPUT) si no se puede leer. */
    suspend fun fetch(http: OkHttpClient = defaultHttp): List<OpenRouterModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE_URL/models?supported_parameters=structured_outputs&sort=newest").get().build()
        val (code, body) = try {
            http.newCall(request).execute().use { r ->
                val source = r.body.source()
                if (source.request(MAX_BYTES + 1)) throw LlmException(LlmErrorKind.BAD_OUTPUT, "Lista demasiado grande", retryable = false)
                r.code to source.buffer.readUtf8()
            }
        } catch (e: IOException) {
            throw LlmException(LlmErrorKind.NETWORK, e.javaClass.simpleName)
        }
        if (code != 200) throw httpError(HttpReply(code, body, null))
        parseOpenRouterModels(body)
    }
}

/** `data[]` → modelos, sin las entradas `:batch` (solo valen para la Batch API), del más nuevo al más antiguo. */
internal fun parseOpenRouterModels(body: String): List<OpenRouterModel> {
    val data = parseObject(body)["data"] as? JsonArray ?: throw LlmException(LlmErrorKind.BAD_OUTPUT, "Sin data")
    return data.mapNotNull { e ->
        val m = e.obj() ?: return@mapNotNull null
        val id = m.str("id")?.takeIf { it.isNotBlank() && !it.endsWith(":batch") } ?: return@mapNotNull null
        val pricing = m["pricing"].obj()
        fun perM(key: String) = pricing.str(key)?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { it * 1_000_000 }
        OpenRouterModel(
            id = id,
            name = m.str("name") ?: id,
            createdAt = ((m["created"] as? JsonPrimitive)?.longOrNull ?: 0L) * 1000,
            promptPerM = perM("prompt"),
            completionPerM = perM("completion"),
            contextLength = (m["context_length"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0,
        )
    }.sortedByDescending { it.createdAt }
}

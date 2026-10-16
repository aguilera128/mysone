package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.core.AppJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Esquemas JSON de salida, compatibles con structured outputs de Anthropic y `strict` de OpenAI:
 * `additionalProperties: false`, todo `required` y los nulos como `["string","null"]`.
 * Son constantes y van con su prompt: cambiar la estructura invalida la gramática y la caché.
 */
object Schemas {
    private val STRING = type("string")
    private val NULLABLE_STRING = buildJsonObject { putJsonArray("type") { add("string"); add("null") } }
    private val BOOLEAN = type("boolean")

    private val TASK = obj(
        "text" to STRING,
        "explicitReminder" to BOOLEAN,
        "dueAt" to NULLABLE_STRING,
        "remindAt" to NULLABLE_STRING,
        "quote" to STRING,
    )

    val PROCESS: JsonObject = obj(
        "title" to STRING,
        "summary" to STRING,
        "keyPoints" to array(STRING),
        "isNoise" to BOOLEAN,
        "tasks" to array(TASK),
        "facts" to array(obj("text" to STRING)),
    )

    val REMINDER: JsonObject = obj("tasks" to array(TASK))

    val PLAN: JsonObject = obj("keywords" to array(STRING), "from" to NULLABLE_STRING, "to" to NULLABLE_STRING)

    val DIGEST: JsonObject = obj("summary" to STRING, "highlights" to array(STRING))

    private fun type(name: String) = buildJsonObject { put("type", name) }

    private fun array(items: JsonElement) = buildJsonObject { put("type", "array"); put("items", items) }

    private fun obj(vararg props: Pair<String, JsonElement>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (name, schema) -> put(name, schema) } }
        putJsonArray("required") { props.forEach { add(it.first) } }
        put("additionalProperties", false)
    }
}

// Salidas. Todo con valor por defecto: en modo json_object el proveedor no garantiza la forma.

@Serializable
data class TaskOut(
    val text: String = "",
    val explicitReminder: Boolean = false,
    val dueAt: String? = null,
    val remindAt: String? = null,
    val quote: String = "",
)

@Serializable
data class FactOut(val text: String = "")

@Serializable
data class ProcessOut(
    val title: String = "",
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val isNoise: Boolean = false,
    val tasks: List<TaskOut> = emptyList(),
    val facts: List<FactOut> = emptyList(),
)

@Serializable
data class ReminderOut(val tasks: List<TaskOut> = emptyList())

@Serializable
data class PlanOut(val keywords: List<String> = emptyList(), val from: String? = null, val to: String? = null)

@Serializable
data class DigestOut(val summary: String = "", val highlights: List<String> = emptyList())

/** Decodifica el objeto JSON de la respuesta, aunque venga entre ``` o con texto alrededor. */
internal inline fun <reified T> decodeOutput(text: String): T {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end < start) throw LlmException(LlmErrorKind.BAD_OUTPUT, "La salida no es JSON")
    return try {
        AppJson.decodeFromString<T>(text.substring(start, end + 1))
    } catch (_: IllegalArgumentException) { // SerializationException incluida
        throw LlmException(LlmErrorKind.BAD_OUTPUT, "JSON con otra forma")
    }
}

/** Pide JSON y lo decodifica. Un fallo de formato se reintenta una vez (§7.3). */
internal suspend inline fun <reified T> LlmClient.completeJson(req: LlmRequest): T = try {
    decodeOutput<T>(complete(req).text)
} catch (e: LlmException) {
    if (e.kind != LlmErrorKind.BAD_OUTPUT) throw e
    decodeOutput<T>(complete(req).text)
}

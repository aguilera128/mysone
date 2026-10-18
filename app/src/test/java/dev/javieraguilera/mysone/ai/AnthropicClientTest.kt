package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.core.AppJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.ConnectionSpec
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class AnthropicClientTest {
    private val server = MockWebServer()

    private val schema = Schemas.DIGEST
    private val req = LlmRequest(
        system = "mysone:prueba v1",
        messages = listOf(LlmMessage("user", "Hola")),
        model = "claude-haiku-5-5",
        maxTokens = 1000,
        schema = schema,
        effort = "low",
    )

    @Before fun setUp() {
        server.start()
        AnthropicClient.fallbacksRejected = false
    }

    @After fun tearDown() = server.close()

    private fun client() = AnthropicClient(server.url("/").toString(), "sk-test")

    private fun ok(body: String) = MockResponse(200, Headers.headersOf("content-type", "application/json"), body)

    private fun message(text: String, stop: String = "end_turn") =
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
           "content":[{"type":"thinking","thinking":"","signature":"x"},{"type":"text","text":${JsonPrimitive(text)}}],
           "stop_reason":"$stop","usage":{"input_tokens":120,"output_tokens":30,"cache_read_input_tokens":2000,"cache_creation_input_tokens":0}}"""

    @Test fun `petición con la forma y cabeceras exactas`() = runTest {
        server.enqueue(ok(message("""{"summary":"S","highlights":[]}""")))
        client().complete(req)

        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/v1/messages", r.url.encodedPath)
        assertEquals("sk-test", r.headers["x-api-key"])
        assertEquals("2023-06-01", r.headers["anthropic-version"])
        assertEquals("application/json", r.headers["content-type"])
        assertNull(r.headers["anthropic-beta"])
        assertNull(r.headers["authorization"])

        val expected = """{"model":"claude-haiku-5-5","max_tokens":1000,
            "system":[{"type":"text","text":"mysone:prueba v1","cache_control":{"type":"ephemeral"}}],
            "messages":[{"role":"user","content":"Hola"}],
            "output_config":{"effort":"low","format":{"type":"json_schema","schema":$schema}}}"""
        assertEquals(AppJson.parseToJsonElement(expected), AppJson.parseToJsonElement(r.body!!.utf8()))
    }

    @Test fun `sin esquema ni caché no manda format ni cache_control`() = runTest {
        server.enqueue(ok(message("Respuesta")))
        client().complete(req.copy(schema = null, effort = "medium", cacheSystem = false))

        val body = AppJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(AppJson.parseToJsonElement("""[{"type":"text","text":"mysone:prueba v1"}]"""), body["system"])
        assertEquals(AppJson.parseToJsonElement("""{"effort":"medium"}"""), body["output_config"])
        for (forbidden in listOf("temperature", "top_p", "top_k", "thinking", "fallbacks")) assertFalse(forbidden in body)
    }

    @Test fun `opus y sonnet llevan fallbacks con la cabecera beta`() = runTest {
        server.enqueue(ok(message("Hola")))
        client().complete(req.copy(model = "claude-opus-5-5"))

        val r = server.takeRequest()
        assertEquals("server-side-fallback-2026-07-01", r.headers["anthropic-beta"])
        assertEquals("\"default\"", body(r.body!!.utf8())["fallbacks"].toString())
    }

    @Test fun `si la API rechaza fallbacks reintenta sin él y no lo vuelve a mandar`() = runTest {
        server.enqueue(MockResponse(400, Headers.headersOf(), """{"type":"error","error":{"type":"invalid_request_error","message":"fallbacks: Extra inputs are not permitted"}}"""))
        server.enqueue(ok(message("Uno")))
        server.enqueue(ok(message("Dos")))
        val c = client()
        assertEquals("Uno", c.complete(req.copy(model = "claude-sonnet-5-5")).text)
        assertEquals("Dos", c.complete(req.copy(model = "claude-sonnet-5-5")).text)

        assertTrue("fallbacks" in body(server.takeRequest().body!!.utf8()))
        val retry = server.takeRequest()
        assertNull(retry.headers["anthropic-beta"])
        assertFalse("fallbacks" in body(retry.body!!.utf8()))
        assertFalse("fallbacks" in body(server.takeRequest().body!!.utf8()))
    }

    @Test fun `usa el primer bloque de texto y lee el uso`() = runTest {
        server.enqueue(ok(message("""{"summary":"Día tranquilo","highlights":[]}""")))
        val res = client().complete(req)
        assertEquals("""{"summary":"Día tranquilo","highlights":[]}""", res.text)
        assertEquals(120, res.inputTokens)
        assertEquals(30, res.outputTokens)
        assertEquals(2000, res.cachedTokens)
    }

    @Test fun `max_tokens reintenta una vez con el doble`() = runTest {
        server.enqueue(ok(message("{\"summ", stop = "max_tokens")))
        server.enqueue(ok(message("OK")))
        assertEquals("OK", client().complete(req).text)
        assertEquals(1000, body(server.takeRequest().body!!.utf8())["max_tokens"].toString().toInt())
        assertEquals(2000, body(server.takeRequest().body!!.utf8())["max_tokens"].toString().toInt())
    }

    @Test fun `refusal es REFUSAL y no se reintenta`() = runTest {
        server.enqueue(ok(message("", stop = "refusal")))
        assertKind(LlmErrorKind.REFUSAL) { client().complete(req) }
        assertEquals(1, server.requestCount)
    }

    @Test fun `errores HTTP`() = runTest {
        server.enqueue(MockResponse(401, Headers.headersOf(), """{"type":"error","error":{"type":"authentication_error","message":"x"}}"""))
        assertKind(LlmErrorKind.AUTH) { client().complete(req) }

        // 529 (sobrecarga) se reintenta; a la tercera vez se rinde.
        repeat(3) { server.enqueue(MockResponse(529, Headers.headersOf(), "{}")) }
        assertKind(LlmErrorKind.SERVER) { client().complete(req) }
        assertEquals(4, server.requestCount)
    }

    @Test fun `http sin texto plano permitido es INSECURE_URL y no se reintenta`() = runTest {
        // Como en release (networkSecurityConfig sin cleartext): OkHttp lanza UnknownServiceException sin conectar.
        val tlsOnly = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).build()
        assertKind(LlmErrorKind.INSECURE_URL) { AnthropicClient(server.url("/").toString(), "sk-test", tlsOnly).complete(req) }
        assertEquals(0, server.requestCount)
    }

    @Test fun `parseAnthropic sin bloque de texto es BAD_OUTPUT`() {
        assertKind(LlmErrorKind.BAD_OUTPUT) {
            parseAnthropic("""{"content":[{"type":"thinking","thinking":""}],"stop_reason":"end_turn","usage":{}}""")
        }
        assertNull(parseAnthropic("""{"content":[],"stop_reason":"max_tokens"}"""))
    }

    private fun body(json: String): JsonObject = AppJson.parseToJsonElement(json).jsonObject
}

internal inline fun assertKind(kind: LlmErrorKind, block: () -> Unit) {
    try {
        block()
        fail("Se esperaba LlmException($kind)")
    } catch (e: LlmException) {
        assertEquals(kind, e.kind)
    }
}

package dev.javieraguilera.mysone.ai

import dev.javieraguilera.mysone.core.AppJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class OpenAiCompatClientTest {
    private val server = MockWebServer()

    private val req = LlmRequest(
        system = "mysone:planificar v1",
        messages = listOf(LlmMessage("user", "¿Qué dijo Ana?")),
        model = "mistral-small-latest",
        maxTokens = 1024,
        schema = Schemas.PLAN,
        effort = "low",
    )

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun client(jsonMode: String) = OpenAiCompatClient(server.url("/v1").toString(), "sk-test", jsonMode)

    private fun completion(content: String, finish: String = "stop") = MockResponse(
        200,
        Headers.headersOf("content-type", "application/json"),
        """{"id":"x","object":"chat.completion","model":"m","choices":[{"index":0,"finish_reason":"$finish",
           "message":{"role":"assistant","content":${JsonPrimitive(content)}}}],
           "usage":{"prompt_tokens":800,"completion_tokens":40,"total_tokens":840,"prompt_tokens_details":{"cached_tokens":512}}}""",
    )

    @Test fun `petición json_schema estricto con forma y cabeceras exactas`() = runTest {
        server.enqueue(completion("""{"keywords":["Ana"],"from":null,"to":null}"""))
        val res = client(JsonMode.SCHEMA).complete(req)

        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertEquals("/v1/chat/completions", r.url.encodedPath)
        assertEquals("Bearer sk-test", r.headers["authorization"])
        assertEquals("application/json", r.headers["content-type"])
        val expected = """{"model":"mistral-small-latest","max_tokens":1024,
            "messages":[{"role":"system","content":"mysone:planificar v1"},{"role":"user","content":"¿Qué dijo Ana?"}],
            "response_format":{"type":"json_schema","json_schema":{"name":"mysone","schema":${Schemas.PLAN},"strict":true}}}"""
        assertEquals(AppJson.parseToJsonElement(expected), AppJson.parseToJsonElement(r.body!!.utf8()))

        assertEquals("""{"keywords":["Ana"],"from":null,"to":null}""", res.text)
        assertEquals(800, res.inputTokens)
        assertEquals(40, res.outputTokens)
        assertEquals(512, res.cachedTokens)
    }

    @Test fun `modo json_object y texto libre sin response_format`() {
        val obj = openAiBody(req, 1024, JsonMode.OBJECT, deepSeek = false)
        assertEquals(AppJson.parseToJsonElement("""{"type":"json_object"}"""), obj["response_format"])
        assertFalse("thinking" in obj)

        assertFalse("response_format" in openAiBody(req.copy(schema = null), 1024, JsonMode.SCHEMA, deepSeek = false))
    }

    @Test fun `DeepSeek apaga el razonamiento`() {
        val obj = openAiBody(req, 1024, JsonMode.OBJECT, deepSeek = true)
        assertEquals(AppJson.parseToJsonElement("""{"type":"disabled"}"""), obj["thinking"])
    }

    @Test fun `OpenRouter solo enruta a proveedores que cumplen el esquema y no entrenan con los datos`() {
        val obj = openAiBody(req, 1024, JsonMode.SCHEMA, deepSeek = false, openRouter = true)
        assertEquals(AppJson.parseToJsonElement("""{"require_parameters":true,"data_collection":"deny"}"""), obj["provider"])
        assertFalse("provider" in openAiBody(req, 1024, JsonMode.SCHEMA, deepSeek = false))
        assertEquals(true, OpenRouterModels.isOpenRouter("https://openrouter.ai/api/v1"))
        assertEquals(false, OpenRouterModels.isOpenRouter("https://openrouter.ai.ejemplo.com/v1"))
        assertEquals(true, OpenRouterModels.isOpenRouter(" https://OpenRouter.ai/api/v1"))
    }

    @Test fun `OpenRouter - moderación es de la conversación y sin saldo espera sin gastar intentos`() {
        val moderated = httpError(HttpReply(403, """{"error":{"code":403,"message":"flagged","metadata":{"reasons":["x"],"flagged_input":"texto"}}}""", null))
        assertEquals(LlmErrorKind.REFUSAL, moderated.kind)
        assertFalse(moderated.message!!.contains("texto"))
        assertEquals(LlmErrorKind.AUTH, httpError(HttpReply(403, """{"error":{"code":403,"message":"key"}}""", null)).kind)
        val credits = httpError(HttpReply(402, """{"error":{"code":402,"message":"Insufficient credits"}}""", null))
        assertEquals(LlmErrorKind.RATE, credits.kind)
    }

    @Test fun `catálogo de OpenRouter sin batch, del más nuevo al más antiguo y con precio por millón`() {
        val body = """{"data":[
            {"id":"a/viejo","name":"Viejo","created":1000,"pricing":{"prompt":"0.0000001","completion":"0.0000005"},"context_length":8192},
            {"id":"a/nuevo","name":"Nuevo","created":2000,"pricing":{"prompt":"0","completion":"0"},"context_length":1000000},
            {"id":"a/nuevo:batch","name":"Nuevo (batch)","created":2000,"pricing":{"prompt":"0","completion":"0"}},
            {"name":"sin id"}
        ]}"""
        val models = parseOpenRouterModels(body)
        assertEquals(listOf("a/nuevo", "a/viejo"), models.map { it.id })
        assertEquals(2_000_000L, models[0].createdAt)
        assertEquals(0.1, models[1].promptPerM!!, 1e-9)
        assertEquals(0.5, models[1].completionPerM!!, 1e-9)
        assertEquals(1_000_000, models[0].contextLength)
    }

    @Test fun `length reintenta con el doble`() = runTest {
        server.enqueue(completion("{\"keyw", finish = "length"))
        server.enqueue(completion("{}"))
        assertEquals("{}", client(JsonMode.OBJECT).complete(req).text)
        server.takeRequest()
        val second = AppJson.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("2048", second["max_tokens"].toString())
    }

    @Test fun `contenido vacío es BAD_OUTPUT y la caché de DeepSeek se lee`() {
        assertKind(LlmErrorKind.BAD_OUTPUT) {
            parseOpenAi("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":""}}]}""")
        }
        val res = parseOpenAi(
            """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],
               "usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_cache_hit_tokens":7,"prompt_cache_miss_tokens":3}}""",
        )!!
        assertEquals(7, res.cachedTokens)
    }

    @Test fun `401 es AUTH`() = runTest {
        server.enqueue(MockResponse(401, Headers.headersOf(), """{"error":{"type":"invalid_api_key"}}"""))
        assertKind(LlmErrorKind.AUTH) { client(JsonMode.SCHEMA).complete(req) }
    }
}

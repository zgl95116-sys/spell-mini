package com.logan.spellmini.net

import com.logan.spellmini.BuildConfig
import com.logan.spellmini.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String) : IOException("HTTP $code: $message")

data class DecisionResult(val answers: JsonObject, val model: String?, val latencyMs: Long, val costUsd: Double?)
data class ToolCall(val id: String, val name: String, val arguments: String)
data class Source(val title: String, val url: String, val content: String)
data class ChatResult(
    val content: String,
    val toolCalls: List<ToolCall>,
    val sources: List<Source>,
    val costUsd: Double?,
    val latencyMs: Long,
)

data class SearchResult(val summary: String, val sources: List<Source>, val costUsd: Double?)

// Null-tolerant JSON accessors: providers return JsonNull for absent fields, which the strict casts reject.
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

/**
 * How much the model may think before answering. Measured on short replies: OFF ~1.6s, LOW ~1.7s, ON ~3.7s.
 * OFF proved unreliable for actions inside a long chat (it claimed things were done without calling a tool).
 */
enum class Reasoning { OFF, LOW, ON }

class OpenRouter(private val settings: Settings) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    // JEV answers in well under a second; a slow call is better treated as a failure than waited on.
    private val jevHttp = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val pageHttp = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).followRedirects(true).build()

    val hasKey: Boolean get() = BuildConfig.OPENROUTER_API_KEY.isNotBlank()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    /**
     * One retry for failures that are usually momentary: a dropped connection, a DNS hiccup on a phone switching
     * networks, a gateway error. On a real phone six verdicts in a day were lost to exactly these, with no second try.
     */
    private suspend fun postJson(client: OkHttpClient, request: Request): JsonObject =
        try {
            postOnce(client, request)
        } catch (error: IOException) {
            if (error is ApiException && error.code !in RETRYABLE_CODES) throw error
            delay(RETRY_DELAY_MS)
            postOnce(client, request)
        }

    private suspend fun postOnce(client: OkHttpClient, request: Request): JsonObject {
        val response = client.newCall(request).await()
        return withContext(Dispatchers.IO) {
            response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw ApiException(it.code, body.take(400))
                val parsed = json.parseToJsonElement(body) as? JsonObject
                    ?: throw ApiException(it.code, "non-object response")
                // OpenRouter can return 200 with an error envelope when the upstream provider failed.
                parsed.obj("error")?.let { err -> throw ApiException(it.code, err.str("message") ?: err.toString().take(300)) }
                parsed
            }
        }
    }

    /** One structured-decision call. Throws on any transport or protocol failure; callers decide the fallback. */
    suspend fun decide(state: JsonElement, questions: JsonObject): DecisionResult {
        val body = buildJsonObject {
            put("model", settings.jevModel)
            put("state", state)
            put("questions", questions)
        }
        val request = Request.Builder().url("$BASE/api/alpha/decisions")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + BuildConfig.OPENROUTER_API_KEY)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        val started = System.nanoTime()
        val parsed = postJson(jevHttp, request)
        val latency = (System.nanoTime() - started) / 1_000_000
        val answers = parsed.obj("answers") ?: throw ApiException(200, "missing answers")
        return DecisionResult(answers, parsed.str("model"), latency, parsed.obj("usage")?.dbl("cost"))
    }

    private fun chatBody(
        messages: JsonArray,
        tools: JsonArray?,
        responseFormat: JsonObject?,
        plugins: JsonArray?,
        maxTokens: Int?,
        reasoning: Reasoning,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", settings.chatModel)
        put("messages", messages)
        tools?.let { put("tools", it) }
        responseFormat?.let { put("response_format", it) }
        plugins?.let { put("plugins", it) }
        maxTokens?.let { put("max_tokens", it) }
        putJsonObject("reasoning") {
            when (reasoning) {
                Reasoning.OFF -> put("enabled", false)
                Reasoning.LOW -> put("effort", "low")
                Reasoning.ON -> put("enabled", true)
            }
        }
        putJsonObject("usage") { put("include", true) }
        // OpenRouter load-balances this model across providers by price, and some are very slow: the same 700-token
        // search reply took 13s on one and 93s on another. Sorting by throughput measured 4.5-7.4s, for ~20% more cost.
        putJsonObject("provider") { put("sort", "throughput") }
        if (stream) put("stream", true)
    }

    private fun chatRequest(body: JsonObject): Request = Request.Builder()
        .url("$BASE/api/v1/chat/completions")
        .header("Authorization", "Bearer " + BuildConfig.OPENROUTER_API_KEY)
        .header("X-Title", "Spell Mini")
        .post(body.toString().toRequestBody(jsonMedia))
        .build()

    private fun parseSources(message: JsonObject?): List<Source> =
        message?.arr("annotations").orEmpty().mapNotNull { it as? JsonObject }
            .mapNotNull { it.obj("url_citation") }
            .mapNotNull { c ->
                val url = c.str("url") ?: return@mapNotNull null
                Source(c.str("title").orEmpty(), url, c.str("content").orEmpty())
            }
            .distinctBy { it.url }

    suspend fun chat(
        messages: JsonArray,
        tools: JsonArray? = null,
        responseFormat: JsonObject? = null,
        plugins: JsonArray? = null,
        maxTokens: Int? = null,
        reasoning: Reasoning = Reasoning.OFF,
    ): ChatResult {
        val started = System.nanoTime()
        val parsed = postJson(http, chatRequest(chatBody(messages, tools, responseFormat, plugins, maxTokens, reasoning, false)))
        val latency = (System.nanoTime() - started) / 1_000_000
        val message = (parsed.arr("choices")?.firstOrNull() as? JsonObject)?.obj("message")
        val calls = message?.arr("tool_calls").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { call ->
            val fn = call.obj("function") ?: return@mapNotNull null
            ToolCall(call.str("id") ?: "call_${System.nanoTime()}", fn.str("name").orEmpty(), fn.str("arguments") ?: "{}")
        }
        return ChatResult(
            content = message?.str("content").orEmpty().trim(),
            toolCalls = calls,
            sources = parseSources(message),
            costUsd = parsed.obj("usage")?.dbl("cost"),
            latencyMs = latency,
        )
    }

    /**
     * A structured-output call that must come back as a JSON object. With reasoning on, the model can spend its whole
     * token budget thinking and return empty or truncated content (seen: ~3,800 reasoning tokens against a 4,000 cap),
     * so a failed parse is retried once with reasoning off before giving up. Returns the object and the total cost.
     */
    suspend fun chatJson(messages: JsonArray, schema: JsonObject, maxTokens: Int, reasoning: Reasoning = Reasoning.OFF): Pair<JsonObject, Double> {
        var cost = 0.0
        val attempts = if (reasoning != Reasoning.OFF) listOf(reasoning, Reasoning.OFF) else listOf(Reasoning.OFF)
        for (withReasoning in attempts) {
            val result = chat(messages, responseFormat = schema, maxTokens = maxTokens, reasoning = withReasoning)
            cost += result.costUsd ?: 0.0
            val parsed = runCatching { json.parseToJsonElement(result.content) as? JsonObject }.getOrNull()
            if (parsed != null) return parsed to cost
        }
        throw ApiException(200, "模型没有返回完整的 JSON")
    }

    /** Streams a reply; [onText] receives the accumulated text so far. Tool-call argument fragments are joined by index. */
    suspend fun chatStream(
        messages: JsonArray,
        tools: JsonArray?,
        reasoning: Reasoning = Reasoning.OFF,
        onText: suspend (String) -> Unit,
    ): ChatResult = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val call = http.newCall(chatRequest(chatBody(messages, tools, null, null, null, reasoning, true)))
        val cancelHook = coroutineContext[Job]?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        val content = StringBuilder()
        val ids = sortedMapOf<Int, String>()
        val names = sortedMapOf<Int, String>()
        val args = sortedMapOf<Int, StringBuilder>()
        var cost: Double? = null
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, response.body?.string().orEmpty().take(400))
                val source = response.body?.source() ?: throw ApiException(response.code, "empty body")
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    // SSE comments (": OPENROUTER PROCESSING") and blank keep-alives are not data.
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val chunk = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
                    chunk.obj("error")?.let { throw ApiException(200, it.str("message") ?: it.toString().take(300)) }
                    chunk.obj("usage")?.dbl("cost")?.let { cost = it }
                    val delta = (chunk.arr("choices")?.firstOrNull() as? JsonObject)?.obj("delta") ?: continue
                    delta.str("content")?.takeIf { it.isNotEmpty() }?.let {
                        content.append(it)
                        onText(content.toString())
                    }
                    delta.arr("tool_calls")?.mapNotNull { it as? JsonObject }?.forEach { part ->
                        val index = (part["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
                        part.str("id")?.let { ids[index] = it }
                        val fn = part.obj("function")
                        fn?.str("name")?.takeIf { it.isNotEmpty() }?.let { names[index] = it }
                        fn?.str("arguments")?.let { args.getOrPut(index) { StringBuilder() }.append(it) }
                    }
                }
            }
        } finally {
            cancelHook?.dispose()
        }
        val calls = names.map { (index, name) ->
            ToolCall(ids[index] ?: "call_$index", name, args[index]?.toString()?.ifBlank { "{}" } ?: "{}")
        }
        ChatResult(content.toString().trim(), calls, emptyList(), cost, (System.nanoTime() - started) / 1_000_000)
    }

    /**
     * Web search through OpenRouter's `web` plugin. Exa (the default engine) returned fresh Chinese pages in testing;
     * the cheaper Parallel engine returned mostly English sites for Chinese queries.
     */
    suspend fun webSearch(query: String): SearchResult {
        val messages = buildJsonArray {
            add(msg("system", "你是检索助手。只依据搜索结果，用中文列出与查询最相关的事实要点（含日期、数字、名称），" +
                "每条一行。不要寒暄，不要编造搜索结果里没有的内容；结果不足就直说。"))
            add(msg("user", query))
        }
        val plugins = buildJsonArray { add(buildJsonObject { put("id", "web"); put("max_results", 5) }) }
        val result = chat(messages, plugins = plugins, maxTokens = 700)
        return SearchResult(result.content, result.sources, result.costUsd)
    }

    /** Best-effort cover image for a cited page. Returns null on any failure; feed cards simply show no image. */
    suspend fun fetchOgImage(pageUrl: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(pageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
                .build()
            pageHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val head = response.body?.source()?.let { src ->
                    src.request(HTML_HEAD_BYTES)
                    src.buffer.clone().readUtf8(minOf(src.buffer.size, HTML_HEAD_BYTES))
                } ?: return@use null
                val raw = OG_PATTERNS.firstNotNullOfOrNull { it.find(head)?.groupValues?.get(1) } ?: return@use null
                val resolved = response.request.url.resolve(raw.replace("&amp;", "&"))?.toString() ?: return@use null
                // Cleartext is blocked by the platform default, so only https images can render.
                resolved.replaceFirst("http://", "https://").takeIf { it.startsWith("https://") }
            }
        }.getOrNull()
    }

    companion object {
        const val BASE = "https://openrouter.ai"
        private const val HTML_HEAD_BYTES = 200_000L
        private const val RETRY_DELAY_MS = 800L
        private val RETRYABLE_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
        private val OG_PATTERNS = listOf(
            Regex("""<meta[^>]+property=["']og:image(?::secure_url)?["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*property=["']og:image["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+name=["']twitter:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        )

        fun msg(role: String, content: String): JsonObject = buildJsonObject {
            put("role", role)
            put("content", content)
        }
    }
}

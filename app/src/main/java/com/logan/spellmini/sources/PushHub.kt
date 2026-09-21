package com.logan.spellmini.sources

import android.util.Log
import com.logan.spellmini.data.FeedSource
import com.logan.spellmini.data.Secrets
import com.logan.spellmini.data.SourceConfig
import com.logan.spellmini.data.SourceKind
import com.logan.spellmini.data.SourceStore
import com.logan.spellmini.net.Web
import com.logan.spellmini.pipeline.Pipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Sources that push rather than wait to be asked: an ntfy topic, a server-sent-events stream, a WebSocket. One
 * connection per source that is switched on, reopened with a growing pause when it drops. Whatever arrives is addressed
 * to the user, so it enters the pipeline as a notification would.
 */
class PushHub(private val store: SourceStore, private val secrets: Secrets, private val pipeline: Pipeline, private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).pingInterval(40, TimeUnit.SECONDS).build()
    private val running = HashMap<Long, Pair<String, Job>>()
    private val recent = HashMap<Long, ArrayDeque<Long>>()

    /** Brings the open connections in line with the list of sources. Called on a timer and after the list was edited. */
    suspend fun sync() {
        val wanted = store.all().filter { it.enabled && it.kind in SourceKind.PUSHED && it.url.startsWith("http") || it.enabled && it.kind == SourceKind.WEBSOCKET }
        val signature = wanted.associate { it.id to it.url + it.config.toString() }
        synchronized(running) {
            running.filter { (id, entry) -> signature[id] != entry.first }.forEach { (id, entry) -> entry.second.cancel(); running.remove(id) }
            wanted.filter { it.id !in running }.forEach { source -> running[source.id] = signature.getValue(source.id) to scope.launch(Dispatchers.IO) { keepOpen(source) } }
        }
    }

    private suspend fun keepOpen(source: FeedSource) {
        var pause = FIRST_PAUSE_MS
        while (scope.isActive) {
            val began = System.currentTimeMillis()
            val error = runCatching { if (source.kind == SourceKind.WEBSOCKET) socket(source) else lines(source) }.exceptionOrNull()
            // A connection that held for a while was healthy: start the pauses over.
            pause = if (System.currentTimeMillis() - began > HEALTHY_MS) FIRST_PAUSE_MS else (pause * 2).coerceAtMost(MAX_PAUSE_MS)
            runCatching { store.get(source.id)?.let { store.save(it.copy(lastError = error?.message?.take(120) ?: "连接断开，稍后重连", lastPolledAt = System.currentTimeMillis())) } }
            Log.i(TAG, "${source.name} dropped (${error?.message}); retrying in ${pause / 1000}s")
            delay(pause)
        }
    }

    private fun request(source: FeedSource, url: String): Request = Request.Builder().url(url)
        .apply { Adapters.headers(source, secrets.get(Secrets.forSource(source.id))).forEach { (name, value) -> header(name, value) } }.build()

    /** ntfy's JSON stream is one message per line; a plain SSE stream puts the payload after "data:". */
    private suspend fun lines(source: FeedSource) = withContext(Dispatchers.IO) {
        val ntfy = source.kind == SourceKind.NTFY
        val url = Web.checked(if (ntfy) source.url.trimEnd('/') + "/json" else source.url, ownNetwork = true)
        http.newCall(request(source, url).newBuilder().header("Accept", if (ntfy) "application/x-ndjson" else "text/event-stream").build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            markConnected(source)
            val reader = response.body?.source() ?: throw java.io.IOException("空响应")
            val data = StringBuilder()
            while (isActive) {
                val line = reader.readUtf8Line() ?: break
                when {
                    ntfy -> if (line.isNotBlank()) arrived(source, line)
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                    line.isBlank() && data.isNotEmpty() -> { arrived(source, data.toString().trim()); data.clear() }
                }
            }
        }
    }

    private suspend fun socket(source: FeedSource): Unit = suspendCancellableCoroutine { continuation ->
        val url = Web.checked(source.url.replaceFirst(Regex("^ws", RegexOption.IGNORE_CASE), "http"), ownNetwork = true).replaceFirst("http", "ws")
        val socket = http.newWebSocket(request(source, url), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch { markConnected(source) }
                source.config[SourceConfig.SEND]?.takeIf { it.isNotBlank() }?.let { webSocket.send(it) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { arrived(source, text) } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (continuation.isActive) continuation.resume(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (continuation.isActive) continuation.resume(Unit) }
        })
        continuation.invokeOnCancellation { socket.cancel() }
    }

    private suspend fun markConnected(source: FeedSource) {
        runCatching { store.get(source.id)?.let { store.save(it.copy(lastError = "", lastPolledAt = System.currentTimeMillis())) } }
    }

    private suspend fun arrived(source: FeedSource, payload: String) {
        val json: JsonElement? = runCatching { Json.parseToJsonElement(payload) }.getOrNull()
        if (source.kind == SourceKind.NTFY && JsonPath.text(json, "event") != "message") return // open and keepalive lines
        // A stream gone wild must not turn into a hundred model calls.
        val window = synchronized(recent) { recent.getOrPut(source.id) { ArrayDeque() } }
        val now = System.currentTimeMillis()
        synchronized(window) {
            while (window.isNotEmpty() && now - window.first() > 60_000) window.removeFirst()
            if (window.size >= MAX_PER_MINUTE) return
            window.addLast(now)
        }
        val (title, text) = when {
            source.kind == SourceKind.NTFY -> JsonPath.text(json, "title").ifBlank { source.name } to JsonPath.text(json, "message")
            json != null -> JsonPath.fill(json, source.config[SourceConfig.TITLE].orEmpty()).ifBlank { source.name } to
                JsonPath.fill(json, source.config[SourceConfig.TEXT].orEmpty()).ifBlank { payload.take(600) }
            else -> source.name to payload.take(600)
        }
        if (text.isBlank()) return
        pipeline.ingestPush(source.name, JsonPath.text(json, "id").ifBlank { payload.hashCode().toString() + now }, title.take(120), text.take(1_500))
        runCatching { store.get(source.id)?.let { store.save(it.copy(taken = it.taken + 1)) } }
    }

    companion object {
        private const val TAG = "SpellPush"
        private const val FIRST_PAUSE_MS = 5_000L
        private const val MAX_PAUSE_MS = 5 * 60_000L
        private const val HEALTHY_MS = 2 * 60_000L
        private const val MAX_PER_MINUTE = 20
    }
}

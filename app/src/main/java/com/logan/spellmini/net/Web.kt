package com.logan.spellmini.net

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

data class PageText(val title: String, val text: String, val url: String)
data class FeedItem(
    val id: String, val title: String, val link: String, val date: String, val summary: String,
    val category: String = "", val author: String = "",
)

/** A feed as a whole: what it calls itself, and its items. */
data class Feed(val title: String, val items: List<FeedItem>)

/**
 * Plain reading of the open web for the research and watch tools: a page as text, a feed as items. No JavaScript is
 * run and no third-party reader service is involved, so pages that only exist after scripts run come back thin, and
 * the caller is told so rather than handed an empty success.
 */
object Web {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true).build()

    private const val MAX_BYTES = 1_500_000L
    private const val MAX_TEXT = 6_000
    private const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** The same address check, for callers that open their own connection (streams). */
    fun checked(url: String, ownNetwork: Boolean = false): String = safe(url, ownNetwork)

    /** Cleartext is blocked by the platform, and a phone has no business fetching its own network's admin pages. */
    private fun safe(url: String, ownNetwork: Boolean = false): String {
        val fixed = url.trim().replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        if (!fixed.startsWith("https://", ignoreCase = true)) throw IOException("只能读 http(s) 网址")
        val host = fixed.removePrefix("https://").substringBefore('/').substringBefore(':').lowercase()
        if (ownNetwork) return fixed
        if (host == "localhost" || host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)) {
            throw IOException("不读本机和内网地址")
        }
        return fixed
    }

    private suspend fun fetch(url: String, maxBytes: Long = MAX_BYTES, headers: Map<String, String> = emptyMap(), ownNetwork: Boolean = false): Triple<ByteArray, String?, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(safe(url, ownNetwork)).header("User-Agent", AGENT).header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            .apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空响应")
            val source = body.source()
            source.request(maxBytes)
            val bytes = source.buffer.clone().readByteArray(minOf(source.buffer.size, maxBytes))
            Triple(bytes, body.contentType()?.charset()?.name(), response.request.url.toString())
        }
    }

    /** Many Chinese sites still serve GBK and only say so inside the page. */
    private fun decode(bytes: ByteArray, declared: String?): String {
        val sniffed = Regex("""charset\s*=\s*["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(String(bytes, 0, minOf(bytes.size, 4_096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val name = declared ?: sniffed ?: "UTF-8"
        return String(bytes, runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8))
    }

    private val DROP_BLOCKS = Regex("""(?is)<(script|style|noscript|svg|nav|footer|header|aside|form|iframe|template)\b.*?</\1>""")
    private val COMMENTS = Regex("""(?s)<!--.*?-->""")
    private val BREAKS = Regex("""(?i)<br\s*/?>|</(p|div|li|tr|h[1-6]|section|article|blockquote|dd|dt|table)>""")
    private val TAGS = Regex("""<[^>]+>""")
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|\w+);""")
    private val NAMED = mapOf("nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·", "ldquo" to "“", "rdquo" to "”")

    fun plain(html: String): String = html.replace(COMMENTS, " ").replace(DROP_BLOCKS, " ").replace(BREAKS, "\n").replace(TAGS, " ")
        .replace(ENTITY) { match ->
            val code = match.groupValues[1]
            when {
                code.startsWith("#x") || code.startsWith("#X") -> code.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                code.startsWith("#") -> code.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> NAMED[code]
            } ?: " "
        }

    /**
     * The readable text of a page. Menus and link lists are short lines; body text is long lines or lines that end in
     * punctuation, so keeping those gets the article without a DOM parser or a new dependency.
     */
    suspend fun readPage(url: String, headers: Map<String, String> = emptyMap(), ownNetwork: Boolean = false): PageText {
        val (bytes, charset, finalUrl) = fetch(url, headers = headers, ownNetwork = ownNetwork)
        val html = decode(bytes, charset)
        val title = Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.get(1)?.let { plain(it).trim() }.orEmpty().take(120)
        val lines = plain(html).lines().map { it.replace(Regex("[ \\t\\u00A0\\u3000]+"), " ").trim() }
            .filter { line -> line.length >= 24 || (line.length >= 8 && line.last() in "。！？；.!?:：") }
            .distinct()
        val text = lines.joinToString("\n").take(MAX_TEXT)
        if (text.length < 200) throw IOException("这个网页读不到正文（多半是靠脚本渲染或需要登录），换一个来源")
        return PageText(title, text, finalUrl)
    }

    /** A response body as text, for public JSON endpoints read as if they were feeds. Returns the text and the final address. */
    /**
     * [ownNetwork] lets the address be on the phone's own network. Only for addresses the user typed in himself (his
     * Home Assistant, his NAS); anything a model asks for stays barred from there.
     */
    suspend fun readText(url: String, maxBytes: Long = MAX_BYTES, headers: Map<String, String> = emptyMap(), ownNetwork: Boolean = false): Pair<String, String> {
        val (bytes, charset, finalUrl) = fetch(url, maxBytes, headers, ownNetwork)
        return decode(bytes, charset) to finalUrl
    }

    /** The raw page, for finding the feed a site advertises in its head. */
    suspend fun readHtml(url: String): Pair<String, String> = readText(url)

    suspend fun readFeed(url: String): List<FeedItem> = readFeedFull(url).items

    /** RSS 2.0 and Atom, newest first as the feed gives them. GitHub releases (`/releases.atom`) and arXiv are Atom. */
    suspend fun readFeedFull(url: String, limit: Int = 30): Feed {
        val (bytes, charset, _) = fetch(url)
        val xml = decode(bytes, charset).trimStart('﻿', ' ', '\n', '\r')
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }
        val items = mutableListOf<FeedItem>()
        var fields: MutableMap<String, String>? = null
        var channelTitle = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && items.size < limit) {
            val name = parser.name?.lowercase()
            when (event) {
                XmlPullParser.START_TAG -> when {
                    name == "item" || name == "entry" -> fields = mutableMapOf()
                    // The first title outside any item is the feed's own name.
                    fields == null && name == "title" && channelTitle.isEmpty() && items.isEmpty() ->
                        channelTitle = runCatching { parser.nextText() }.getOrNull()?.let { plain(it).trim().take(60) }.orEmpty()
                    fields != null && name == "link" && parser.getAttributeValue(null, "href") != null -> {
                        // Atom: the first link, or the one marked as the page itself.
                        val rel = parser.getAttributeValue(null, "rel")
                        if (fields["link"] == null || rel == "alternate") fields["link"] = parser.getAttributeValue(null, "href")
                    }
                    fields != null && name != null -> runCatching { parser.nextText() }.getOrNull()?.let { text ->
                        if (text.isNotBlank() && fields!![name] == null) fields!![name] = text.trim()
                    }
                }
                XmlPullParser.END_TAG -> if ((name == "item" || name == "entry") && fields != null) {
                    val f = fields!!
                    val link = f["link"].orEmpty()
                    items += FeedItem(
                        id = f["guid"] ?: f["id"] ?: link.ifBlank { f["title"].orEmpty() },
                        title = plain(f["title"].orEmpty()).trim().take(160), link = link,
                        date = (f["pubdate"] ?: f["updated"] ?: f["published"] ?: f["dc:date"]).orEmpty().take(40),
                        summary = plain(f["description"] ?: f["summary"] ?: f["content"] ?: f["content:encoded"] ?: "").replace(Regex("\\s+"), " ").trim().take(280),
                        category = plain(f["category"].orEmpty()).trim().take(24),
                        author = plain(f["author"] ?: f["dc:creator"] ?: "").trim().take(60),
                    )
                    fields = null
                }
            }
            // A malformed tail is common in the wild; what was parsed so far is still good.
            event = try { parser.next() } catch (broken: Exception) { XmlPullParser.END_DOCUMENT }
        }
        if (items.isEmpty()) throw IOException("这个地址不是 RSS 或 Atom 订阅源，或者里面没有条目")
        return Feed(channelTitle, items)
    }
}

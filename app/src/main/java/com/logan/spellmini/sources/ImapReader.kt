package com.logan.spellmini.sources

import android.util.Base64
import com.logan.spellmini.BuildConfig
import com.logan.spellmini.net.Web
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.nio.charset.Charset
import javax.net.ssl.SSLSocketFactory

data class Mail(val uid: Long, val from: String, val subject: String, val date: String, val text: String)

/**
 * Just enough IMAP to read what is new in an inbox: log in, look, fetch the headers and the first kilobytes of each new
 * message, leave. Nothing is marked as read, moved or deleted (every fetch is a PEEK). Written by hand because the only
 * alternative is a mail library several times the size of this whole app for four commands.
 *
 * Experimental: MIME in the wild is endless. What this reads well is what matters here, the machine-written mail of
 * airlines, hotels, shops and banks; a message it cannot decode still arrives with its sender and subject.
 */
object ImapReader {
    private const val TIMEOUT_MS = 20_000
    private const val BODY_BYTES = 8_192
    private const val MAX_PER_LOOK = 8

    /**
     * Messages with a UID above [afterUid], oldest first, and the highest UID seen. With [afterUid] 0 only the newest
     * [taste] are returned: the first look at a mailbox must not replay years of mail.
     */
    suspend fun newMail(url: String, user: String, password: String, afterUid: Long, taste: Int = 3): Pair<List<Mail>, Long> = withContext(Dispatchers.IO) {
        val secure = url.startsWith("imaps://", ignoreCase = true)
        // Plain IMAP would send the password in the clear; it exists for the test server on the development machine only.
        if (!secure && !(BuildConfig.DEBUG && url.startsWith("imap://"))) throw IOException("邮箱地址要以 imaps:// 开头，如 imaps://imap.qq.com:993")
        val hostPort = url.substringAfter("://").trimEnd('/')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', if (secure) "993" else "143").toIntOrNull() ?: 993
        val socket: Socket = if (secure) SSLSocketFactory.getDefault().createSocket(host, port) else Socket(host, port)
        socket.soTimeout = TIMEOUT_MS
        socket.use {
            val session = Session(BufferedInputStream(socket.getInputStream()), socket.getOutputStream())
            session.line() // greeting
            session.run("LOGIN ${quote(user)} ${quote(password)}").also { if (!it.ok) throw IOException("邮箱登录失败：${it.status.take(80)}（QQ、163 要用授权码，不是登录密码）") }
            // 163 and a few others refuse SELECT from a client that has not introduced itself.
            session.run("ID (\"name\" \"spellmini\" \"version\" \"${BuildConfig.VERSION_NAME}\")")
            val selected = session.run("SELECT INBOX").also { if (!it.ok) throw IOException("打不开收件箱：${it.status.take(80)}") }
            val next = Regex("""UIDNEXT (\d+)""").find(selected.text)?.groupValues?.get(1)?.toLongOrNull() ?: 1
            val from = if (afterUid > 0) afterUid + 1 else (next - taste).coerceAtLeast(1)
            val found = session.run("UID SEARCH UID $from:*")
            val uids = Regex("""\* SEARCH([ \d]*)""").find(found.text)?.groupValues?.get(1).orEmpty().trim().split(' ')
                .mapNotNull { it.toLongOrNull() }.filter { it > afterUid && it >= from }.sorted().takeLast(MAX_PER_LOOK)
            val mails = uids.mapNotNull { uid ->
                val fetched = session.run("UID FETCH $uid (BODY.PEEK[HEADER.FIELDS (SUBJECT FROM DATE CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] BODY.PEEK[TEXT]<0.$BODY_BYTES>)")
                if (!fetched.ok) return@mapNotNull null
                val header = fetched.literals.firstOrNull { it.first.contains("HEADER", ignoreCase = true) }?.second ?: return@mapNotNull null
                val body = fetched.literals.firstOrNull { it.first.contains("[TEXT]", ignoreCase = true) }?.second ?: ByteArray(0)
                read(uid, header, body)
            }
            runCatching { session.run("LOGOUT") }
            mails to (uids.maxOrNull() ?: maxOf(afterUid, next - 1))
        }
    }

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private class Reply(val ok: Boolean, val status: String, val text: String, val literals: List<Pair<String, ByteArray>>)

    /** One connection. IMAP mixes lines with counted blocks of bytes ("{123}" then 123 bytes), so both are read from the same stream. */
    private class Session(private val input: BufferedInputStream, private val output: OutputStream) {
        private var counter = 0

        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("邮箱服务器断开了连接")
                if (b == '\n'.code) break
                if (b != '\r'.code) bytes.write(b)
            }
            return bytes.toString("ISO-8859-1")
        }

        fun run(command: String): Reply {
            val tag = "A${++counter}"
            output.write("$tag $command\r\n".toByteArray()); output.flush()
            val text = StringBuilder()
            val literals = mutableListOf<Pair<String, ByteArray>>()
            while (true) {
                val line = line()
                if (line.startsWith("$tag ")) return Reply(line.startsWith("$tag OK", ignoreCase = true), line.removePrefix("$tag "), text.toString(), literals)
                text.append(line).append('\n')
                val size = Regex("""\{(\d+)\}$""").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val block = ByteArray(size)
                var read = 0
                while (read < size) { val n = input.read(block, read, size - read); if (n < 0) throw IOException("邮件没读完"); read += n }
                literals += line to block
            }
        }
    }

    // ------------------------------------------------------------------ MIME, as much of it as machine-written mail uses

    private fun read(uid: Long, headerBytes: ByteArray, bodyBytes: ByteArray): Mail {
        val headers = headersOf(String(headerBytes, Charsets.ISO_8859_1))
        val from = words(headers["from"].orEmpty())
        val name = from.substringBefore('<').trim().trim('"').ifBlank { from.substringAfter('<').substringBefore('>') }
        val text = runCatching { part(headers["content-type"].orEmpty(), headers["content-transfer-encoding"].orEmpty(), bodyBytes, depth = 0) }.getOrDefault("")
        return Mail(uid, name.take(60), words(headers["subject"].orEmpty()).take(200), headers["date"].orEmpty().take(40), text.replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim().take(1_500))
    }

    private fun headersOf(block: String): Map<String, String> {
        val unfolded = block.replace("\r\n", "\n").replace(Regex("\n[ \t]+"), " ")
        return unfolded.lines().mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.take(it).trim().lowercase() to line.drop(it + 1).trim() } }.toMap()
    }

    private fun param(headerValue: String, name: String): String? =
        Regex("""$name\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(headerValue)?.groupValues?.get(1)?.trim()

    /** The readable text of a body: itself when it is text, otherwise its first text part, looking one level into nested multiparts. */
    private fun part(type: String, encoding: String, bytes: ByteArray, depth: Int): String {
        if (type.startsWith("multipart/", ignoreCase = true) && depth < 3) {
            val boundary = param(type, "boundary") ?: return ""
            val raw = String(bytes, Charsets.ISO_8859_1)
            val pieces = raw.split("--$boundary").drop(1).filterNot { it.startsWith("--") }
            val parsed = pieces.map { piece ->
                val cut = Regex("\r?\n\r?\n").find(piece)
                val head = headersOf(piece.take(cut?.range?.first ?: piece.length))
                Triple(head["content-type"].orEmpty().ifBlank { "text/plain" }, head["content-transfer-encoding"].orEmpty(), piece.drop(cut?.range?.last?.plus(1) ?: piece.length))
            }
            val best = parsed.firstOrNull { it.first.startsWith("text/plain", true) } ?: parsed.firstOrNull { it.first.startsWith("multipart/", true) } ?: parsed.firstOrNull { it.first.startsWith("text/html", true) } ?: return ""
            return part(best.first, best.second, best.third.toByteArray(Charsets.ISO_8859_1), depth + 1)
        }
        if (type.isNotBlank() && !type.startsWith("text/", ignoreCase = true)) return ""
        val charset = runCatching { Charset.forName(param(type, "charset") ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        val decoded = when (encoding.trim().lowercase()) {
            "base64" -> {
                // Only the first kilobytes were fetched, so the last line may be cut short: keep whole groups of four.
                val clean = String(bytes, Charsets.ISO_8859_1).filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
                runCatching { Base64.decode(clean.take(clean.length - clean.length % 4), Base64.DEFAULT) }.getOrDefault(ByteArray(0))
            }
            "quoted-printable" -> quotedPrintable(String(bytes, Charsets.ISO_8859_1))
            else -> bytes
        }
        val text = String(decoded, charset)
        return if (type.startsWith("text/html", ignoreCase = true) || text.contains("<html", ignoreCase = true)) Web.plain(text) else text
    }

    private fun quotedPrintable(text: String, underscoreIsSpace: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val body = text.replace("=\r\n", "").replace("=\n", "")
        var i = 0
        while (i < body.length) {
            val ch = body[i]
            val hex = if (ch == '=' && i + 2 < body.length) body.substring(i + 1, i + 3).toIntOrNull(16) else null
            when {
                hex != null -> { out.write(hex); i += 3 }
                underscoreIsSpace && ch == '_' -> { out.write(' '.code); i++ }
                else -> { out.write(ch.code and 0xFF); i++ }
            }
        }
        return out.toByteArray()
    }

    private val ENCODED = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")

    /** "=?UTF-8?B?5L2g5aW9?=" in subjects and sender names. Two encoded words in a row are one word split for length. */
    private fun words(value: String): String = ENCODED.replace(value.replace(Regex("""\?=\s+=\?"""), "?==?")) { match ->
        val charset = runCatching { Charset.forName(match.groupValues[1]) }.getOrDefault(Charsets.UTF_8)
        val bytes = if (match.groupValues[2].equals("B", true)) runCatching { Base64.decode(match.groupValues[3], Base64.DEFAULT) }.getOrDefault(ByteArray(0))
        else quotedPrintable(match.groupValues[3], underscoreIsSpace = true)
        String(bytes, charset)
    }.trim()
}

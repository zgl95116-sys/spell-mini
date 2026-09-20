package com.logan.spellmini.data

import com.logan.spellmini.net.arr
import com.logan.spellmini.net.obj
import com.logan.spellmini.net.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A cited page shown under a chat message as a small card: cover image when the page has one, a play mark for video sites. */
data class LinkPreview(val title: String, val url: String, val image: String? = null, val video: Boolean = false)

/** A phone action offered as a button under a message. It runs when tapped; the label is built from [args], not by the model. */
data class ActionChip(val tool: String, val args: JsonObject, val label: String, val state: String = ChipState.OPEN)

object ChipState {
    const val OPEN = "open"
    const val DONE = "done"
    const val FAILED = "failed"

    /** A drafted reply that could not be sent in place: it was put on the clipboard and the chat was opened instead. */
    const val COPIED = "copied"
}

/**
 * What hangs under a text message. Stored as JSON in [ChatMsg.cardJson], which a text row never used before, so old
 * databases keep working without a schema change.
 */
data class Attachments(val links: List<LinkPreview> = emptyList(), val actions: List<ActionChip> = emptyList()) {
    val isEmpty: Boolean get() = links.isEmpty() && actions.isEmpty()

    fun toJson(): String = buildJsonObject {
        putJsonArray("links") {
            links.forEach { link ->
                addJsonObject {
                    put("title", link.title); put("url", link.url); put("video", link.video)
                    link.image?.let { put("image", it) }
                }
            }
        }
        putJsonArray("actions") {
            actions.forEach { chip -> addJsonObject { put("tool", chip.tool); put("args", chip.args); put("label", chip.label); put("state", chip.state) } }
        }
    }.toString()

    companion object {
        private val VIDEO_HOSTS = listOf(
            "bilibili.com", "b23.tv", "youtube.com", "youtu.be", "douyin.com", "ixigua.com", "v.qq.com", "youku.com",
            "kuaishou.com", "iqiyi.com", "acfun.cn", "vimeo.com", "tiktok.com",
        )

        fun isVideo(url: String): Boolean = VIDEO_HOSTS.any { url.contains(it, ignoreCase = true) }

        /** Null for rows that carry something else in the column (confirm cards, action notes) or nothing at all. */
        fun parse(json: String?): Attachments? {
            if (json.isNullOrBlank()) return null
            val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
            if (root["links"] == null && root["actions"] == null) return null
            val links = root.arr("links").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { item ->
                val url = item.str("url") ?: return@mapNotNull null
                LinkPreview(item.str("title").orEmpty(), url, item.str("image"), (item["video"] as? JsonPrimitive)?.booleanOrNull == true)
            }
            val actions = root.arr("actions").orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { item ->
                val tool = item.str("tool") ?: return@mapNotNull null
                ActionChip(tool, item.obj("args") ?: JsonObject(emptyMap()), item.str("label") ?: tool, item.str("state") ?: ChipState.OPEN)
            }
            return Attachments(links, actions)
        }
    }
}

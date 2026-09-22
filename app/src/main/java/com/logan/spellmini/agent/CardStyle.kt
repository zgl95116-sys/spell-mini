package com.logan.spellmini.agent

import com.logan.spellmini.net.OpenRouter
import com.logan.spellmini.net.dbl
import com.logan.spellmini.net.obj
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The one way a feed card is written, whoever writes it: a subscribed item, a notification with a search behind it,
 * or the interest patrol. Three writers used to carry three slightly different copies of the rules, and the cards read
 * like three products. The rules live here, the writers quote them, and [grade] asks JEV whether the result reads the
 * way the rules say before the card is shown.
 */
object CardStyle {
    const val TITLE = 16
    const val BODY = 60
    const val BULLET = 18
    const val REASON = 24

    // Backstops for the times the model ignores the numbers above. Loose on purpose: a bullet cut in the middle of a
    // date reads worse than one that runs four characters long.
    const val TITLE_CAP = 32
    const val BODY_CAP = 84
    const val BULLET_CAP = 28
    const val REASON_CAP = 40

    /** A card whose relevance the writer could not name; the feed shows no "why" line for it. */
    const val DEFAULT_REASON = "来自你订阅的源"

    /** Below this, on either question, the writer gets one more try; still below, the card is not made. */
    const val PASS = 2.0

    val RULES = """
        |这是他在手机上扫一眼的信息流，每张卡都按同一个样子写，像秘书给老板的简报：
        |- 一张卡只说一件事：一个发布、一个数字、一个变化、一个日期。不做综述、合集、攻略、清单、解读；来源是合集时，挑和他最相关的那一件写。
        |- emoji：一个贴题的 emoji，只放在这个字段里
        |- title：这件事本身，主语 + 动作 + 对象，不超过 $TITLE 个字。不用「实操」「指南」「盘点」「解析」「一文看懂」这类栏目词，不用问句，不标题党；原标题是英文就译成中文，专有名词保留原文
        |- body：不超过 $BODY 个字，最多两句。第一句是结论：发生了什么、结果是什么；第二句是这对他意味着什么、要不要做什么。不铺垫、不讲背景、不放「预计约」「近年来」这类泛数据，不重复标题
        |- bullets：2 到 3 条，每条不超过 $BULLET 个字，每条必须带一个硬事实：日期、数字、名字、价格、版本号。不写建议句（「先备好」「建议」「可以」），不写网址。内容里硬事实不够就只写一条
        |- reason：不超过 $REASON 个字，用「你」开头，指向他具体的事或关注（「你十一去日本」「你在盯 JEV」）；看不出关系就写「$DEFAULT_REASON」
        |- subject：这张卡的主题词，不超过 12 个字，只写事情的主体：产品或模型名（MiMo-V2.6）、公司（智谱）、事件（十一台风）；同一件事的不同报道要写成同一个词
        |- 口吻：直接说事，用「你」不用「用户」，不用感叹号，不用「值得关注」「不容错过」「总之」，正文里不放 emoji
        |- 一个硬事实都没有、或只能写成泛泛的建议时，不做这张卡：enough_material 填 false，skip_reason 写「没有具体事实」
    """.trimMargin()

    data class Text(val title: String, val body: String, val bullets: List<String>, val reason: String, val subject: String = "")

    /** What the code can fix without asking: lengths, stray punctuation, bullets that are links or the title again. */
    fun tidy(title: String, body: String, bullets: List<String>, reason: String, subject: String = ""): Text {
        val cleanTitle = clip(title.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().trimEnd('。', '！', '!', '.'), TITLE_CAP)
        val cleanBullets = bullets.asSequence()
            .map { it.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().trimStart('-', '•', '·', ' ') }
            .filter { it.isNotBlank() && !isLink(it) && it != cleanTitle }
            .map { clip(it, BULLET_CAP) }
            .distinct().take(3).toList()
        return Text(cleanTitle, clip(body, BODY_CAP), cleanBullets, clip(reason, REASON_CAP), subject.trim().take(SUBJECT_CAP))
    }

    /** "MiMo-V2.6" and "小米 MiMo V2.6 Pro" are one subject: compared without case, spaces or punctuation, by containment. */
    fun sameSubject(a: String, b: String): Boolean {
        val x = key(a); val y = key(b)
        if (x.length < 2 || y.length < 2) return false
        return x == y || x.contains(y) || y.contains(x) || TextSim.similarity(x, y) >= 0.6
    }

    private fun key(subject: String) = subject.lowercase().filter { it.isLetterOrDigit() }

    const val SUBJECT_CAP = 16

    private fun isLink(text: String) = Regex("https?://|www\\.|\\.(com|cn|org|net|io)(/|\\b)", RegexOption.IGNORE_CASE).containsMatchIn(text)

    /** Cuts at the last sentence end that fits; failing that, at the last pause, never inside a number or a date. */
    fun clip(text: String, limit: Int): String {
        val clean = text.trim()
        if (clean.length <= limit) return clean
        val head = clean.take(limit)
        val end = head.indexOfLast { it in "。！？；" }
        if (end >= limit / 2) return head.take(end + 1)
        val pause = head.indexOfLast { it in "，、,； " }
        val cut = if (pause >= limit / 2) head.take(pause) else head
        return cut.trimEnd('，', '、', ',', ' ', '-', '/', '~', '至') + "…"
    }

    class Grade(val clear: Double, val concrete: Double, val costUsd: Double?) {
        val ok: Boolean get() = clear >= PASS && concrete >= PASS
        override fun toString() = "一眼明白 %.1f / 具体 %.1f".format(clear, concrete)
    }

    /** Two quick JEV scores on the written card. Null when JEV could not be reached: the card then goes out unchecked. */
    suspend fun grade(api: OpenRouter, text: Text): Grade? {
        val state = buildJsonObject {
            putJsonObject("card") {
                put("title", text.title)
                put("body", text.body)
                put("bullets", buildJsonArray { text.bullets.forEach { add(it) } })
            }
        }
        val questions = buildJsonObject {
            putJsonObject(CLEAR) {
                put("type", "score")
                put("instructions", "Rate card on how much a reader who glances at title and body for two seconds learns. Judge the text only.")
                put("criteria", buildJsonArray { CLEAR_LEVELS.forEach { add(JsonPrimitive(it)) } })
            }
            putJsonObject(CONCRETE) {
                put("type", "score")
                put("instructions", "Rate card on how much of it is hard fact (a date, number, name, price or version) rather than background, generic advice or filler. Judge the text only.")
                put("criteria", buildJsonArray { CONCRETE_LEVELS.forEach { add(JsonPrimitive(it)) } })
            }
        }
        val result = runCatching { api.decide(state, questions) }.getOrNull() ?: return null
        val clear = result.answers.obj(CLEAR)?.dbl("score") ?: return null
        val concrete = result.answers.obj(CONCRETE)?.dbl("score") ?: return null
        return Grade(clear, concrete, result.costUsd)
    }

    /** What the writer is told when its first draft did not pass. */
    fun feedback(text: Text, grade: Grade): String = """
        |
        |你上一版被打回（$grade，两项都要到 ${PASS.toInt()} 分以上）：
        |title：${text.title}
        |body：${text.body}
        |bullets：${text.bullets.joinToString("；").ifBlank { "（空）" }}
        |重写：第一句直接说发生了什么、结果是什么，只说一件事；每条 bullet 都带日期、数字、名字、价格或版本号，删掉建议和背景。材料里没有硬事实就填 enough_material=false。
    """.trimMargin()

    private const val CLEAR = "clear"
    private const val CONCRETE = "concrete"

    private val CLEAR_LEVELS = listOf(
        "The reader cannot tell what happened or what is being claimed",
        "Names a topic or a theme but no specific event, result or change",
        "States one specific event, result or change; what it means for the reader is missing or vague",
        "States one specific event, result or change and what it means for the reader, within the first two sentences",
    )

    private val CONCRETE_LEVELS = listOf(
        "No hard facts at all: background, general advice or slogans",
        "One hard fact; the rest is background or advice",
        "Hard facts carry the card, with some filler or one generic line",
        "Every sentence and bullet carries a date, number, name, price or version; no advice, no filler",
    )
}

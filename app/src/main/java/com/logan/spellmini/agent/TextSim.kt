package com.logan.spellmini.agent

/**
 * Overlap of character bigrams, with dates stripped because every search query carries the current month. Measured on
 * real pairs: the same topic reworded scored 0.35-0.6, a different angle on the same interest ~0.27, unrelated 0.00.
 */
internal object TextSim {
    private val DATE_NOISE = Regex("20\\d\\d年?|\\d+月(上旬|中旬|下旬)?|\\d+日|这个周末|本周|最新")

    // \p{P} and \p{S} are understood by both the JVM and Android's ICU regex engine; \p{IsPunctuation} is JVM-only.
    private val NON_WORD = Regex("[\\s\\p{P}\\p{S}]+")

    private fun grams(text: String): Set<String> {
        val clean = text.lowercase().replace(DATE_NOISE, "").replace(NON_WORD, "")
        return (0 until (clean.length - 1).coerceAtLeast(0)).map { clean.substring(it, it + 2) }.toSet()
    }

    fun similarity(a: String, b: String): Double {
        val x = grams(a); val y = grams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.intersect(y).size.toDouble() / minOf(x.size, y.size)
    }
}

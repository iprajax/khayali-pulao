package com.khayali.pulao.ai

/**
 * Text → words the natural voice can say. Kokoro (through sherpa-onnx 2) reads only words in its pronunciation list
 * and silently skips the rest — every digit, "rupees", "Sensex", "HDFC". So before speaking, numbers become words
 * (Indian style: lakh and crore), dates and ordinals are spelled out, short acronyms are read as letters, market
 * names are respelled with words it knows, and anything else unknown is split into known pieces or spelled.
 * Pure: [known] answers "is this word in the voice's list?".
 */
object Pronounce {
    private val ONES = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val MONTHS = mapOf("jan" to "January", "feb" to "February", "mar" to "March", "apr" to "April", "jun" to "June", "jul" to "July",
        "aug" to "August", "sep" to "September", "sept" to "September", "oct" to "October", "nov" to "November", "dec" to "December")

    /** Market words the voice doesn't know, respelled with words it does. */
    private val RESPELL = mapOf(
        "rupees" to "rue peas", "rs" to "rupees", "inr" to "rupees", "sensex" to "sen sex", "infosys" to "info sis", "sebi" to "say bee",
        "tata" to "ta ta", "khayali" to "kia lee", "pulao" to "pull ow", "ltd" to "limited", "vs" to "versus", "yoy" to "year on year",
        "qoq" to "quarter on quarter", "ytd" to "year to date", "approx" to "about", "etc" to "et cetera", "eg" to "for example",
        "ie" to "that is", "mf" to "mutual fund", "mfs" to "mutual funds", "pe" to "P E", "eps" to "E P S", "nav" to "N A V",
    )

    fun prepare(text: String, known: (String) -> Boolean): String {
        var t = text.replace('’', '\'').replace('−', '-')
        // amounts are said number first: "₹1,250" / "rupees 1,250" / "Rs 1,250" → "1,250 rupees"
        t = Regex("""(?i)(?:₹|\brupees\b|\brs\.?|\binr\b)\s*(\d[\d,]*(?:\.\d+)?(?:\s*(?:crores?|cr|lakhs?|lacs?|k)\b)?)""").replace(t) { m -> m.groupValues[1] + " rupees" }
        t = t.replace("₹", " rupees ").replace("%", " percent").replace("&", " and ").replace("×", " times ").replace("@", " ")
        // "17 Nov 2026" / "Nov 17": month names in full next to a number
        t = Regex("""(?i)\b(jan|feb|mar|apr|jun|jul|aug|sept?|oct|nov|dec)\b\.?(?=\s*\d)|(?<=\d\s?)\b(jan|feb|mar|apr|jun|jul|aug|sept?|oct|nov|dec)\b\.?""").replace(t) { m ->
            MONTHS[m.value.trimEnd('.').lowercase()] ?: m.value }
        t = Regex("""(?<![\w.])[-](?=\d)""").replace(t, "minus ")
        t = Regex("""(?<![\w.])\+(?=\d)""").replace(t, "plus ")
        // letters glued to digits ("FY26", "Q2") are read apart
        t = Regex("""(?<=[A-Za-z])(?=\d)""").replace(t, " ")
        t = Regex("""\b(\d+)(st|nd|rd|th)\b""").replace(t) { m -> ordinal(words(m.groupValues[1].toLong())) }
        t = Regex("""(?<![\w.])(\d{1,3}(?:,\d{2,3})+|\d+)(\.\d+)?(?![\w])""").replace(t) { m -> number(m.groupValues[1], m.groupValues[2]) }
        t = Regex("""[A-Za-z][A-Za-z']*(?:-[A-Za-z']+)*""").replace(t) { m -> word(m.value, known) }
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    /** "1,42,64,804" → Indian words; "17" + ".8" → "seventeen point eight"; a lone 1900–2099 reads as a year. */
    fun number(int: String, frac: String = ""): String {
        val digits = int.replace(",", "")
        if (digits.length > 12) return digits.map { ONES[it - '0'] }.joinToString(" ")
        val n = digits.toLong()
        val whole = if (frac.isEmpty() && !int.contains(',') && digits.length == 4 && n in 1900..2099) year(n.toInt()) else words(n)
        return if (frac.length > 1) whole + " point " + frac.drop(1).map { ONES[it - '0'] }.joinToString(" ") else whole
    }

    private fun year(y: Int): String = when {
        y in 2000..2009 -> "two thousand" + if (y > 2000) " " + ONES[y - 2000] else ""
        y % 100 == 0 -> words((y / 100).toLong()) + " hundred"
        else -> words((y / 100).toLong()) + " " + (if (y % 100 < 10) "oh " + ONES[y % 100] else words((y % 100).toLong()))
    }

    /** Indian system: crore, lakh, thousand, hundred. */
    fun words(n: Long): String {
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + if (n % 10 > 0) " " + ONES[(n % 10).toInt()] else ""
        if (n < 1000) return ONES[(n / 100).toInt()] + " hundred" + if (n % 100 > 0) " " + words(n % 100) else ""
        val parts = listOf(10_000_000L to "crore", 100_000L to "lakh", 1000L to "thousand")
        for ((size, name) in parts) if (n >= size) return words(n / size) + " " + name + if (n % size > 0) " " + words(n % size) else ""
        return ""
    }

    private fun ordinal(w: String): String {
        val last = w.substringAfterLast(' '); val head = w.dropLast(last.length)
        val o = when (last) { "one" -> "first"; "two" -> "second"; "three" -> "third"; "five" -> "fifth"; "eight" -> "eighth"; "nine" -> "ninth"; "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th" }
        return head + o
    }

    private fun word(w: String, known: (String) -> Boolean): String {
        val lw = w.lowercase()
        RESPELL[lw]?.let { return it }
        if (known(lw)) return w
        if (lw.endsWith("'s")) return word(w.dropLast(2), known)                    // "INFY's" → "I N F Y"
        if (lw.contains('-')) return lw.split('-').joinToString(" ") { word(it, known) }
        if (w.length in 2..5 && w.all { it.isUpperCase() }) return lw.toList().joinToString(" ")    // acronyms read as letters
        segment(lw, known)?.let { return it }
        return if (lw.length <= 6) lw.toList().joinToString(" ") else w                  // a long unknown word: the voice skips it
    }

    /** The fewest known pieces (2+ letters each, at most 3) that spell the word: "nifties" → "nifty" isn't, "info" + "sis" is. */
    fun segment(w: String, known: (String) -> Boolean): String? {
        val n = w.length
        val best = arrayOfNulls<List<String>>(n + 1); best[0] = emptyList()
        for (i in 0 until n) {
            val sofar = best[i] ?: continue
            if (sofar.size >= 3) continue
            for (j in i + 2..n) {
                val piece = w.substring(i, j)
                if (known(piece) && (best[j] == null || best[j]!!.size > sofar.size + 1)) best[j] = sofar + piece
            }
        }
        return best[n]?.takeIf { it.size > 1 }?.joinToString(" ")
    }
}

package com.khayali.pulao.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * On-device memory in the style of Agentic Context Engineering (ACE): instead of rewriting one
 * summary, the chat keeps a PLAYBOOK of small bullets and evolves it in three roles.
 *
 *  - Generate: each turn the most useful bullets are put in the model's context ([select]).
 *  - Reflect:  after a turn, lessons and durable facts are extracted — by rules from what actually
 *              happened ([lessons], [reflectRules]) and by the model on a throw-away copy of the
 *              conversation ([REFLECT_PROMPT], [parseReflection]).
 *  - Curate:   deterministic merge ([curate]): near-duplicates raise a bullet's helpful count
 *              instead of adding text, unsafe or sensitive notes are refused, and the weakest
 *              bullets are dropped at the cap. Feedback ([feedback]) moves helpful / harmful counts.
 *
 * Small incremental deltas keep the playbook from collapsing into a vague summary. Everything is
 * pure and unit-tested; storage is a private file on the phone (MemoryStore).
 */
object Ace {
    enum class Section(val title: String, val tag: String) {
        FACT("About you", "FACT"), PREF("Preferences", "PREF"), LESSON("Lessons the chat learned", "LESSON")
    }

    data class Bullet(val id: Int, val section: Section, val text: String, val helpful: Int = 1, val harmful: Int = 0, val created: Long = 0, val used: Long = 0) {
        val score get() = helpful - harmful
    }

    data class Delta(val section: Section, val text: String)
    data class Playbook(val bullets: List<Bullet> = emptyList(), val nextId: Int = 1)

    const val CAP = 24

    // ---------------------------------------------------------------- generate

    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "my", "i", "me", "it", "at", "with", "that", "this", "be", "as", "if", "what", "how", "you", "your", "user", "when", "always", "into")
    private fun words(s: String) = Regex("""[a-z0-9%₹]+""").findAll(s.lowercase()).map { it.value.removeSuffix("s") }.filter { it.length > 1 && it !in STOP }.toSet()

    /** Bullets worth showing the model for this message: proven ones first, then ones about the same words. */
    fun select(pb: Playbook, query: String, max: Int = 6): List<Bullet> {
        val q = words(query)
        return pb.bullets.filter { it.harmful <= it.helpful }
            .sortedByDescending { b -> b.score + 2 * words(b.text).count { it in q } + if (b.section != Section.LESSON) 1 else 0 }
            .take(max)
    }

    fun prompt(bullets: List<Bullet>): String {
        if (bullets.isEmpty()) return ""
        val facts = bullets.filter { it.section != Section.LESSON }
        val lessons = bullets.filter { it.section == Section.LESSON }
        return buildString {
            if (facts.isNotEmpty()) { append("What you remember about this user (use it; don't repeat it back unless relevant):\n"); facts.forEach { append("- ").append(it.text).append('\n') } }
            if (lessons.isNotEmpty()) { append("Lessons from earlier answers (follow them):\n"); lessons.forEach { append("- ").append(it.text).append('\n') } }
        }.trim()
    }

    // ----------------------------------------------------------------- reflect

    /** What happened in a turn, as observed by the app — the execution feedback ACE reflects on. */
    data class Signals(
        val percentCorrected: Boolean = false,
        val toolErrors: List<String> = emptyList(),
        val numbersWithoutTool: Boolean = false,
        val ungroundedWebNumbers: Boolean = false,
        val askedForFetchableData: Boolean = false,
    )

    /** Strategy bullets derived from real failures, so the same mistake costs less next time. */
    fun lessons(s: Signals): List<Delta> = buildList {
        if (s.percentCorrected) add(Delta(Section.LESSON, "Pass the percentage exactly as the user typed it, with its sign: 'falls 20%' means percent = -20."))
        if (s.numbersWithoutTool) add(Delta(Section.LESSON, "Never state rupee figures from your own arithmetic; call a calculator tool first."))
        if (s.ungroundedWebNumbers) add(Delta(Section.LESSON, "With web results, copy figures and dates exactly from the snippets and cite them like [1]."))
        if (s.askedForFetchableData) add(Delta(Section.LESSON, "Don't ask the user for holdings or prices that get_current_stock, kite_account or web can fetch."))
        if (s.toolErrors.any { it.contains("No stock selected", true) || it.contains("needs a holding", true) })
            add(Delta(Section.LESSON, "When no stock is in view, call get_current_stock first, or pass symbol / amounts to the calculator."))
        if (s.toolErrors.any { it.contains("isn't an amount", true) || it.contains("isn't a number", true) })
            add(Delta(Section.LESSON, "Give tool amounts in plain words like '5 lakh' or '1 crore', and rates as plain numbers."))
    }

    private val SELF = Regex("""(?i)\b(i am|i'm|i have|i want|i need|i plan|i prefer|i like|i don't|i do not|i invest|i hold|i will|my goal|my target|my horizon|my age|my risk|my salary|my sip|my income|retire|remember|call me|years old)\b""")

    /** Only messages where the user says something about themselves are worth a model reflection pass. */
    fun worthReflecting(userText: String) = userText.length in 12..400 && !userText.trimStart().startsWith("/") && SELF.containsMatchIn(userText)

    /** Explicit statements the app can file without a model. */
    fun reflectRules(userText: String): List<Delta> {
        val t = userText.trim()
        val out = mutableListOf<Delta>()
        Regex("""(?i)^(?:please\s+)?remember(?:\s+that)?[:,]?\s+(.{6,160})$""").find(t)?.let { out += Delta(Section.FACT, sentence(person(it.groupValues[1]))) }
        Regex("""(?i)\bmy (?:goal|target) is\s+([^.!?\n]{4,100})""").find(t)?.let { out += Delta(Section.FACT, "Goal: ${it.groupValues[1].trim()}.") }
        Regex("""(?i)\b(?:i am|i'm)\s+(\d{2})\s*(?:years old|yrs old|yo)\b""").find(t)?.let { out += Delta(Section.FACT, "Is ${it.groupValues[1]} years old.") }
        Regex("""(?i)\bi (?:want|plan) to retire\s+([^.!?\n]{3,60})""").find(t)?.let { out += Delta(Section.FACT, "Plans to retire ${it.groupValues[1].trim()}.") }
        Regex("""(?i)\bi (?:invest|am investing) for\s+([^.!?\n]{3,60})""").find(t)?.let { out += Delta(Section.FACT, "Invests for ${it.groupValues[1].trim()}.") }
        Regex("""(?i)\bi prefer\s+([^.!?\n]{4,100})""").find(t)?.let { out += Delta(Section.PREF, "Prefers ${it.groupValues[1].trim()}.") }
        Regex("""(?i)\b(?:keep (?:it|answers) (short|brief|detailed)|(short|brief|detailed) answers)\b""").find(t)?.let { out += Delta(Section.PREF, "Likes ${(it.groupValues[1] + it.groupValues[2]).lowercase()} answers.") }
        return out
    }

    private fun person(s: String) = s.trim().replace(Regex("""(?i)\bI am\b|\bI'm\b"""), "is").replace(Regex("""(?i)\bmy\b"""), "their").replace(Regex("""(?i)^I\b"""), "They").replaceFirstChar { it.uppercase() }
    private fun sentence(s: String) = s.trim().trimEnd('.', '!', ' ') + "."

    const val REFLECT_PROMPT = "Reflect on the user's last message only. List durable things worth remembering for future chats " +
        "about THIS user: goals, time horizon, age, risk comfort, income or SIP habits, how they like answers. " +
        "One per line, starting with FACT: or PREF:, at most 3 lines, each under 15 words, third person. " +
        "Do not list portfolio numbers, prices, or anything not said by the user. If nothing is worth remembering, reply NONE."

    fun parseReflection(reply: String): List<Delta> = reply.lines().mapNotNull { raw ->
        val l = raw.trim().trimStart('-', '*', '•', ' ')
        val m = Regex("""(?i)^(FACT|PREF|LESSON)\s*[:\-]\s*(.+)$""").find(l) ?: return@mapNotNull null
        Delta(Section.entries.first { it.tag.equals(m.groupValues[1], true) }, sentence(m.groupValues[2].trim().trim('"')))
    }.take(3)

    // ------------------------------------------------------------------ curate

    private val UNSAFE = Regex("""(?i)\b(password|passcode|otp|pin|cvv|aadhaar|aadhar|pan (?:number|no|card)|bank account|account number|upi id|totp|api key|token)\b""")
    private val IDS = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b|\b\d{9,}\b|[\w.+-]+@[\w-]+\.\w+""")
    private val ADVICE = Regex("""(?i)\b(should|must|recommend(ed)?|advis(e|ed)) (to )?(buy|sell|hold|exit|accumulate)\b|\btarget price\b""")

    /** True if a note may be stored: not empty, not a secret or identifier, not a buy/sell call. */
    fun acceptable(text: String) = text.trim().length in 8..180 && !UNSAFE.containsMatchIn(text) && !IDS.containsMatchIn(text) && !ADVICE.containsMatchIn(text)
        && !text.equals("NONE.", true)

    fun similarity(a: String, b: String): Double {
        val x = words(a); val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return x.count { it in y }.toDouble() / (x.size + y.size - x.count { it in y })
    }

    /**
     * Merge deltas into the playbook. A delta that restates an existing bullet (same section,
     * similarity ≥ 0.6) strengthens it; a new one is appended; over [cap] the weakest, oldest go.
     */
    fun curate(pb: Playbook, deltas: List<Delta>, now: Long, cap: Int = CAP): Playbook {
        var bullets = pb.bullets.toMutableList(); var next = pb.nextId
        for (d in deltas) {
            val text = d.text.trim().replace(Regex("""\s+"""), " ")
            if (!acceptable(text)) continue
            val i = bullets.indexOfFirst { it.section == d.section && similarity(it.text, text) >= 0.6 }
            if (i >= 0) bullets[i] = bullets[i].copy(helpful = bullets[i].helpful + 1, used = now, text = if (text.length > bullets[i].text.length && d.section != Section.LESSON) text else bullets[i].text)
            else bullets += Bullet(next++, d.section, text, 1, 0, now, now)
        }
        if (bullets.size > cap) bullets = bullets.sortedWith(compareByDescending<Bullet> { it.score }.thenByDescending { it.used }).take(cap).sortedBy { it.id }.toMutableList()
        return Playbook(bullets, next)
    }

    /** The user kept going (good) or asked to regenerate (bad) after an answer that used these bullets. */
    fun feedback(pb: Playbook, ids: Collection<Int>, good: Boolean, now: Long): Playbook =
        pb.copy(bullets = pb.bullets.map { b ->
            if (b.id !in ids) b else if (good) b.copy(helpful = b.helpful + 1, used = now) else b.copy(harmful = b.harmful + 1, used = now)
        }.filterNot { it.harmful >= it.helpful + 3 })      // repeatedly unhelpful bullets are retired

    fun remove(pb: Playbook, id: Int) = pb.copy(bullets = pb.bullets.filterNot { it.id == id })

    // ------------------------------------------------------------------- store

    fun toJson(pb: Playbook): String = JsonObject().apply {
        addProperty("next", pb.nextId)
        add("bullets", JsonArray().apply {
            pb.bullets.forEach { b -> add(JsonObject().apply {
                addProperty("id", b.id); addProperty("s", b.section.tag); addProperty("t", b.text)
                addProperty("h", b.helpful); addProperty("x", b.harmful); addProperty("c", b.created); addProperty("u", b.used)
            }) }
        })
    }.toString()

    fun fromJson(json: String?): Playbook = runCatching {
        val o = JsonParser.parseString(json).asJsonObject
        Playbook(o.getAsJsonArray("bullets").map { it.asJsonObject }.map { b ->
            Bullet(b["id"].asInt, Section.entries.first { it.tag == b["s"].asString }, b["t"].asString, b["h"].asInt, b["x"].asInt, b["c"].asLong, b["u"].asLong)
        }, o["next"].asInt)
    }.getOrDefault(Playbook())
}

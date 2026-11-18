package com.khayali.pulao.ai

/**
 * Live voice, as a pure state machine. The view renders the phase; the recognizer, the chat and the
 * speaker feed it events. Talk → (pause) → think → speak the answer → listen again, until the user ends it.
 *
 *  IDLE ─Start─▶ LISTENING ─Heard(text)─▶ THINKING ─Answer─▶ SPEAKING ─Spoken─▶ LISTENING …
 *  LISTENING ─Silence─▶ LISTENING (keeps waiting)     any ─Interrupt─▶ LISTENING     any ─End─▶ ENDED
 *  any ─Failed(msg)─▶ ERROR ─Start─▶ LISTENING
 */
object VoiceFlow {
    enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, ERROR, ENDED }

    sealed class Ev {
        data object Start : Ev()
        data class Heard(val text: String) : Ev()
        data object Silence : Ev()
        data object Answer : Ev()
        data object Spoken : Ev()
        data object Interrupt : Ev()
        data class Failed(val message: String) : Ev()
        data object End : Ev()
    }

    fun next(p: Phase, e: Ev): Phase = when (e) {
        Ev.End -> Phase.ENDED
        Ev.Start -> if (p == Phase.ENDED) p else Phase.LISTENING
        is Ev.Heard -> if (p == Phase.LISTENING && e.text.isNotBlank()) Phase.THINKING else p
        Ev.Silence -> p
        Ev.Answer -> if (p == Phase.THINKING) Phase.SPEAKING else p
        Ev.Spoken -> if (p == Phase.SPEAKING) Phase.LISTENING else p
        Ev.Interrupt -> if (p == Phase.ENDED) p else Phase.LISTENING
        is Ev.Failed -> if (p == Phase.ENDED) p else Phase.ERROR
    }

    /** What the screen says under the orb in each phase. */
    fun hint(p: Phase): String = when (p) {
        Phase.IDLE -> "Tap to talk"
        Phase.LISTENING -> "Listening…"
        Phase.THINKING -> "Working it out…"
        Phase.SPEAKING -> "Tap the orb to interrupt"
        Phase.ERROR -> "Tap the orb to try again"
        Phase.ENDED -> ""
    }

    /**
     * An answer as it should be spoken: markdown, links, citations, emoji and table syntax removed; action links
     * keep their words; numbers kept exactly. Long answers are cut at a sentence after [max] characters — the full
     * answer, with its cards, stays in the chat.
     */
    fun speakable(md: String, max: Int = 600): String {
        var t = md
        t = Regex("""\[([^\]]+)]\(cmd:[^)]*\)""").replace(t) { it.groupValues[1] }            // action links → their words
        t = Regex("""\[([^\]]+)]\((https?://)?[^)]*\)""").replace(t) { it.groupValues[1] }     // plain links
        t = Regex("""\[\d{1,2}]""").replace(t, "")                                             // citations
        t = Regex("""(?m)^\s*\|.*\|\s*$""").replace(t, "")                                     // table rows
        t = Regex("""(?m)^\s*[-*•]\s+""").replace(t, "")                                       // bullets
        t = Regex("""(?m)^#+\s*""").replace(t, "")
        t = t.replace("**", "").replace("__", "").replace("`", "").replace("_", " ")
        t = Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}\x{2B50}\x{2B06}\x{2194}-\x{21FF}▸▲▼↻✓✗✕⚡⬇⏹🗑]""").replace(t, "")
        t = t.replace("−", "minus ").replace("₹", "rupees ").replace("%", " percent").replace("&", " and ")
        t = Regex("""\s+""").replace(t, " ").trim()
        if (t.length <= max) return t
        val cut = t.lastIndexOf(". ", max).takeIf { it > max / 3 } ?: t.lastIndexOf(' ', max)
        return t.substring(0, if (cut > 0) cut + 1 else max).trim() + " The rest is in the chat."
    }
}

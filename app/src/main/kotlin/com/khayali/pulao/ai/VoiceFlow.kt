package com.khayali.pulao.ai

/**
 * Live voice, as a pure state machine. The view renders the phase; the recognizer, the chat and the
 * speaker feed it events. Talk → (pause) → think → speak the answer → listen again, until the user ends it.
 *
 *  IDLE ─Start─▶ LISTENING ─Heard(text)─▶ THINKING ─Answer─▶ SPEAKING ─Spoken─▶ LISTENING …
 *  LISTENING ─Silence─▶ LISTENING (hands-free: keeps waiting)   SPEAKING ─Interrupt (you talk or tap)─▶ LISTENING
 *  any ─Pause (mute, a long quiet, the app left)─▶ IDLE ─Start─▶ LISTENING        any ─End─▶ ENDED
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
        data object Pause : Ev()
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
        Ev.Pause -> if (p == Phase.ENDED) p else Phase.IDLE
        is Ev.Failed -> if (p == Phase.ENDED) p else Phase.ERROR
    }

    /** What the voice dock says in each phase. */
    fun hint(p: Phase, talkOver: Boolean = true): String = when (p) {
        Phase.IDLE -> "Paused · tap the orb to talk"
        Phase.LISTENING -> "Listening…"
        Phase.THINKING -> "Working it out…"
        Phase.SPEAKING -> if (talkOver) "Talk or tap to interrupt" else "Tap the orb to interrupt"
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

    /** After this long without hearing anything, listening pauses itself (the mic shouldn't stay open for nobody). */
    const val QUIET_PAUSE_MS = 3 * 60_000L

    /**
     * The part of a long spoken answer to show as the caption: the sentence holding the word being spoken,
     * trimmed to about [max] characters around it, so the lit word is always on screen.
     */
    fun window(text: String, at: Int, max: Int = 170): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val i = at.coerceIn(0, text.length - 1)
        var a = Regex("""[.!?]\s""").findAll(text.substring(0, i)).lastOrNull()?.let { it.range.last + 1 } ?: 0
        var b = Regex("""[.!?](\s|$)""").find(text, i)?.let { it.range.first + 1 } ?: text.length
        while (a < text.length && text[a] == ' ') a++
        if (b - a > max) {                                                     // a long sentence: keep the word in view
            val from = (i - max / 3).coerceAtLeast(a)
            a = text.lastIndexOf(' ', from).takeIf { it >= a }?.plus(1) ?: from
            b = (a + max).coerceAtMost(b).let { e -> text.lastIndexOf(' ', e).takeIf { it > i } ?: e }
        }
        return a until b
    }

    /**
     * Notices you talking over the phone's own voice, from loudness alone (30 ms frames of RMS; no words, nothing kept).
     * The first [calibrate] frames, while the answer has just started playing, measure how loud the phone hears itself
     * (after the phone's echo cancelling); after that, [need] of the last [window] frames must be clearly louder than that
     * echo — [ratio] times its 90th percentile, and never below [floor] — so the phone's own voice doesn't interrupt it.
     */
    class TalkOver(private val calibrate: Int = 20, private val window: Int = 10, private val need: Int = 7,
                   private val ratio: Double = 2.8, private val floor: Double = 0.012) {
        private val seen = ArrayList<Double>(calibrate)
        private val recent = ArrayDeque<Boolean>()
        var threshold = Double.NaN; private set

        /** Feeds one frame's RMS (0–1); true once you're talking. */
        fun feed(rms: Double): Boolean {
            if (threshold.isNaN()) {
                seen += rms
                if (seen.size >= calibrate) threshold = maxOf(floor, seen.sorted()[(seen.size * 9 / 10).coerceAtMost(seen.size - 1)] * ratio)
                return false
            }
            recent.addLast(rms > threshold); if (recent.size > window) recent.removeFirst()
            return recent.size == window && recent.count { it } >= need
        }
    }
}

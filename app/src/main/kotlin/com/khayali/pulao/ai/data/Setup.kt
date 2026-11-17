package com.khayali.pulao.ai.data

/**
 * The one-tap setup agent's life cycle. The user picks what they want; "Set up everything" starts it all
 * at once; each step's status is read from the real state of what it sets up (so it survives restarts and
 * never drifts), and the setup as a whole moves NOT_STARTED → RUNNING → NEEDS_YOU → DONE (or FAILED,
 * with retry). Pure and unit-tested; the chat renders it and does the starting.
 */
object Setup {
    enum class Step(val icon: String, val title: String, val size: String, val why: String, val byDefault: Boolean) {
        MODEL("🧠", "On-device AI (Gemma 4 E2B)", "2.6 GB · waits for Wi-Fi", "Answers in plain words, on your phone", true),
        MARKET("📈", "Market history · 3 years", "about 75 MB, straight from NSE", "Powers the brief, strategies and hidden patterns", true),
        KNOWLEDGE("📚", "Strategy knowledge bank", "289 KB", "Searches 500 community stock screens", true),
        WEB("🌐", "Web lookups", "no download", "News, calendar, IPOs, live market views — topic words only", false),
        KITE("🔌", "Connect Kite (read-only)", "your Zerodha login", "Reviews your own portfolio", false),
    }

    sealed class S {
        data object Skipped : S()
        data object Waiting : S()
        data class Running(val pct: Int?, val note: String = "") : S()
        data class Done(val note: String = "") : S()
        data class NeedsYou(val action: String) : S()
        data class Failed(val message: String) : S()
    }

    enum class Phase { NOT_STARTED, RUNNING, NEEDS_YOU, DONE, FAILED }

    /** What is true right now, from the app's own state. */
    data class Facts(val model: Artifacts.State, val market: Artifacts.State, val knowledge: Artifacts.State, val web: Boolean, val kite: Boolean)

    private fun download(a: Artifacts.State, chosen: Boolean): S = when (a) {
        is Artifacts.State.Ready, is Artifacts.State.Stale -> S.Done(if (a is Artifacts.State.Ready) a.version else "ready")
        is Artifacts.State.Downloading -> S.Running(if (a.total > 0) (a.done * 100 / a.total).toInt() else null)
        is Artifacts.State.Updating -> S.Running(if (a.total > 0) a.done * 100 / a.total else null)
        Artifacts.State.Verifying -> S.Running(null, "checking the download")
        is Artifacts.State.Building -> S.Running(null, "preparing")
        is Artifacts.State.Paused -> S.Running(null, a.reason)
        is Artifacts.State.Failed -> if (chosen) S.Failed(a.message) else S.Skipped
        Artifacts.State.NotInstalled -> if (chosen) S.Waiting else S.Skipped
    }

    fun status(step: Step, chosen: Boolean, started: Boolean, f: Facts): S {
        val s = when (step) {
            Step.MODEL -> download(f.model, chosen)
            Step.MARKET -> download(f.market, chosen)
            Step.KNOWLEDGE -> download(f.knowledge, chosen)
            Step.WEB -> if (f.web) S.Done("on") else if (chosen) S.Waiting else S.Skipped
            Step.KITE -> if (f.kite) S.Done("connected") else if (chosen && started) S.NeedsYou("Connect Kite") else if (chosen) S.Waiting else S.Skipped
        }
        // something already set up counts as done even if not chosen this time
        return s
    }

    fun phase(started: Boolean, states: Map<Step, S>): Phase = when {
        !started -> Phase.NOT_STARTED
        states.values.any { it is S.Failed } -> Phase.FAILED
        states.values.any { it is S.Running || it is S.Waiting } -> Phase.RUNNING
        states.values.any { it is S.NeedsYou } -> Phase.NEEDS_YOU
        else -> Phase.DONE
    }

    /** Steps the agent should start now: chosen and not yet underway or done. */
    fun toStart(chosen: Set<Step>, states: Map<Step, S>): List<Step> = chosen.filter { states[it] is S.Waiting || states[it] is S.Failed }

    fun encode(chosen: Set<Step>) = chosen.joinToString(",") { it.name }
    fun decode(s: String?): Set<Step>? = s?.split(',')?.mapNotNull { runCatching { Step.valueOf(it) }.getOrNull() }?.toSet()

    /** A one-line summary for the card's headline. */
    fun headline(phase: Phase, states: Map<Step, S>): String {
        val done = states.values.count { it is S.Done }; val total = states.values.count { it !is S.Skipped }
        return when (phase) {
            Phase.NOT_STARTED -> "Set up everything in one tap"
            Phase.RUNNING -> "Setting up… $done of $total ready"
            Phase.NEEDS_YOU -> "Almost there — one step needs you"
            Phase.DONE -> "✓ You're all set"
            Phase.FAILED -> "Something didn't finish — tap Retry"
        }
    }
}

package com.khayali.pulao.ai

/**
 * The chat's lifecycle as an explicit state machine, plus the router that decides what a
 * submitted message should do. Pure and unit-tested: the view only renders [Phase] and
 * executes [Action]s, so every way of interacting (typing, pills, /commands, @mentions,
 * retries, reconnects) goes through the same transitions.
 *
 *   NEEDS_MODEL ──ModelReady──▶ IDLE ──StartApp──▶ FETCHING ──Finished/Failed──▶ IDLE
 *                                 │
 *                                 └─StartModel(cold)─▶ WARMING ─EngineReady─▶ THINKING
 *                                 └─StartModel(warm)─────────────────────────▶ THINKING
 *                       THINKING ⇄ TOOL ──Token──▶ STREAMING ──Finished/Failed/Stopped──▶ IDLE
 */
object ChatFlow {

    enum class Phase { NEEDS_MODEL, IDLE, FETCHING, WARMING, THINKING, TOOL, STREAMING }

    sealed class Ev {
        data object ModelMissing : Ev()
        data object ModelReady : Ev()
        /** App-only work that needs no model: a Kite read, a web lookup, a calculator command. */
        data object StartApp : Ev()
        data class StartModel(val engineLoaded: Boolean) : Ev()
        data object EngineReady : Ev()
        data object ToolCall : Ev()
        data object ToolResult : Ev()
        data object Token : Ev()
        data object Finished : Ev()
        data object Failed : Ev()
        data object Stopped : Ev()
        data object Reset : Ev()
    }

    val Phase.busy get() = this != Phase.IDLE && this != Phase.NEEDS_MODEL
    /** Work is in flight (model or network), so the send button becomes Stop. */
    val Phase.canStop get() = busy

    /** Illegal events leave the phase unchanged, so a late callback can never corrupt the UI. */
    fun next(p: Phase, e: Ev): Phase = when (e) {
        Ev.Reset, Ev.Finished, Ev.Failed, Ev.Stopped -> if (p == Phase.NEEDS_MODEL) p else Phase.IDLE
        // Commands that need no model keep working while the model is absent, so only an idle chat flips.
        Ev.ModelMissing -> if (p == Phase.IDLE) Phase.NEEDS_MODEL else p
        Ev.ModelReady -> if (p == Phase.NEEDS_MODEL) Phase.IDLE else p
        Ev.StartApp -> if (!p.busy) Phase.FETCHING else p
        is Ev.StartModel -> if (p == Phase.IDLE) (if (e.engineLoaded) Phase.THINKING else Phase.WARMING) else p
        Ev.EngineReady -> if (p == Phase.WARMING) Phase.THINKING else p
        Ev.ToolCall -> if (p == Phase.THINKING || p == Phase.STREAMING || p == Phase.WARMING) Phase.TOOL else p
        Ev.ToolResult -> if (p == Phase.TOOL) Phase.THINKING else p
        Ev.Token -> if (p == Phase.THINKING || p == Phase.TOOL || p == Phase.WARMING) Phase.STREAMING else p
    }

    // ------------------------------------------------------------------ routing

    enum class Kite { OFF, CONNECTED, EXPIRED }
    /** [ready]: installed artifacts ("market", "knowledge"); null = not tracked (everything counts as installed). */
    data class Env(val modelReady: Boolean, val kite: Kite, val webOn: Boolean, val ready: Set<String>? = null)
    enum class WebKind { SEARCH, PRICE, EVENTS, CALENDAR, IPO, GMP }
    enum class Source { KITE, WEB }

    sealed class Action {
        data object Ignore : Action()
        data object Clear : Action()
        data object Help : Action()
        /** Kite is needed but off or expired; [pending] is re-run automatically once connected. */
        data class ConnectKite(val pending: String?, val expired: Boolean) : Action()
        data class KiteRead(val section: String) : Action()
        data class Chart(val symbol: String, val period: String?, val source: Source) : Action()
        /** A calculator run directly by the app — exact, instant, no model needed. */
        data class Tool(val name: String, val args: String) : Action()
        data class Web(val kind: WebKind, val query: String) : Action()
        /** Web access is off; ask once, then re-run [pending]. */
        data class EnableWeb(val pending: String) : Action()
        data class Remember(val text: String) : Action()
        data object ShowMemory : Action()
        data object ForgetAll : Action()
        /** Rebuild the model's context from a short summary of the thread. */
        data object Compact : Action()
        /** The one-tap setup agent. */
        data object Setup : Action()
        data class Term(val id: String, val arg: String) : Action()
        /** A broad question: run a workflow of steps, then wrap up. */
        data class Plan(val plan: Planner.Plan, val question: String) : Action()
        /** The command needs an artifact that isn't on the phone yet; offer it, then re-run [pending]. */
        data class NeedArtifact(val artifact: String, val pending: String) : Action()
        data class Model(val prompt: String) : Action()
        /** Free-form question but the model isn't downloaded. */
        data object NeedModel : Action()
        data class Unknown(val name: String) : Action()
    }

    /** The artifact a feature needs that isn't installed, if any. */
    fun missing(featureId: String, env: Env): String? {
        val need = com.khayali.pulao.ai.term.Terminal.find(featureId)?.requires ?: return null
        return if (env.ready == null || need in env.ready) null else need
    }

    fun route(raw: String, env: Env): Action {
        val text = raw.trim()
        if (text.isEmpty()) return Action.Ignore
        if (text.startsWith("Connect Kite") || text.startsWith("Reconnect Kite")) return Action.ConnectKite(null, env.kite == Kite.EXPIRED)
        if (Regex("""(?i)^(/setup|set ?up( everything| the app)?|get started|onboard(ing)?)\s*[.!?]*$""").matches(text)) return Action.Setup
        if (!Commands.isCommand(text)) {
            // A broad question becomes a plan of several steps. If its first step needs a download, offer that first.
            Planner.plan(text)?.let { p ->
                val first = p.steps.first().needs
                if (first in setOf("market", "knowledge") && env.ready != null && first !in env.ready) return Action.NeedArtifact(first!!, text)
                return Action.Plan(p, text)
            }
            // A short plain request that means one command runs it exactly as if it had been typed.
            Intents.match(text)?.let { cmd -> route(cmd, env).let { if (it !is Action.Unknown && it !is Action.Help || cmd == "/help") return it } }
            // A short question that plainly means one terminal feature runs it directly: exact, instant, no model needed.
            com.khayali.pulao.ai.term.Terminal.match(text)?.let { (f, arg) ->
                missing(f.id, env)?.let { return Action.NeedArtifact(it, text) }
                if (env.webOn || !f.needsWeb) return Action.Term(f.id, arg) else if (!env.modelReady) return Action.EnableWeb("/${f.id} $arg".trim()) }
            return if (env.modelReady) Action.Model(text) else Action.NeedModel
        }
        return when (val c = Commands.parse(text)) {
            Commands.Parsed.Clear -> Action.Clear
            Commands.Parsed.Help -> Action.Help
            is Commands.Parsed.Unknown -> Action.Unknown(c.name)
            is Commands.Parsed.Kite -> if (env.kite == Kite.CONNECTED) Action.KiteRead(c.section) else Action.ConnectKite(text, env.kite == Kite.EXPIRED)
            is Commands.Parsed.Chart -> when {
                env.kite == Kite.CONNECTED -> Action.Chart(c.symbol, c.period, Source.KITE)
                env.webOn -> Action.Chart(c.symbol, c.period, Source.WEB)
                else -> Action.EnableWeb(text)
            }
            is Commands.Parsed.Web -> if (env.webOn) Action.Web(c.kind, c.query) else Action.EnableWeb(text)
            is Commands.Parsed.Tool -> Action.Tool(c.name, c.args)
            is Commands.Parsed.Remember -> Action.Remember(c.text)
            Commands.Parsed.Memory -> Action.ShowMemory
            Commands.Parsed.Forget -> Action.ForgetAll
            Commands.Parsed.Compact -> Action.Compact
            is Commands.Parsed.Term -> if (missing(c.id, env) != null) Action.NeedArtifact(missing(c.id, env)!!, text) else if (!env.webOn && com.khayali.pulao.ai.term.Terminal.find(c.id)?.needsWeb != false) Action.EnableWeb(text) else Action.Term(c.id, c.arg)
            is Commands.Parsed.Prompt -> if (env.modelReady) Action.Model(c.text) else Action.NeedModel
        }
    }
}

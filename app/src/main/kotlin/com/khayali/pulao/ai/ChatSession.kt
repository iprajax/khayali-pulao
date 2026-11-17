package com.khayali.pulao.ai

import android.content.Context
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.google.gson.JsonParser
import com.khayali.pulao.Fmt
import com.khayali.pulao.Stock
import com.khayali.pulao.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * One chat thread: owns a LiteRT-LM Conversation with the app's tools attached, streams the
 * answer, and reports tool calls to the UI. UI-agnostic.
 *
 * Lifecycle rules (the things that used to break after the phone slept):
 *  - A Conversation dies with its engine. The engine is unloaded after idle time or when Android
 *    needs memory, so before every turn the conversation is checked against [AiEngine.generation]
 *    and rebuilt — seeded with the recent exchanges — when it is stale.
 *  - Context is small. When the conversation grows past [ROLL_AT] tokens it is rebuilt from the
 *    last few exchanges instead of failing mid-answer; a turn that fails before its first word is
 *    retried once on a fresh conversation.
 *  - Anything that changes what the model should know (Kite connected or expired, web switched,
 *    persona changed) calls [invalidate]; the thread itself survives.
 */
class ChatSession(
    private val ctx: Context,
    private val prefsPersona: () -> String? = { null },
    private val host: ToolHost,
    @Suppress("unused") private val tone: () -> Tone,
    private val scope: CoroutineScope,
    private val webOn: () -> Boolean = { false },
    private val memory: (query: String) -> List<Ace.Bullet> = { emptyList() },
    /** Runs one app command exactly as if the user had typed it (see ChatView.runForModel). */
    private val commands: (suspend (String) -> ToolEvent)? = null,
) {
    interface Listener {
        fun onThinking(warming: Boolean)
        fun onEngineReady() {}
        /** A slow tool (network) is about to run. */
        fun onToolStart(label: String) {}
        fun onTool(e: ToolEvent)
        fun onText(full: String)
        fun onDone(full: String, stats: TurnStats)
        fun onError(message: String)
    }

    data class Turn(val user: String, val answer: String)

    private var conversation: Conversation? = null
    private var convGeneration = -1
    private var stale = false
    private var tools: AppTools? = null
    /** Amounts produced by tools during the current turn — the only ₹ figures the model may quote. */
    private val known = mutableListOf<Double>()
    @Volatile private var currentListener: Listener? = null
    private val turnTools = mutableListOf<ToolEvent>()
    private var job: Job? = null
    private var aux: Job? = null
    @Volatile private var auxConversation: Conversation? = null
    val busy get() = job?.isActive == true

    /** Recent exchanges, used to re-seed a rebuilt conversation. */
    private val turns = ArrayDeque<Turn>()
    /** Exchanges the live conversation hasn't seen (app-only commands run between model turns). */
    private val unseen = mutableListOf<Turn>()
    /** Memory bullets already in the live conversation's context. */
    private val shownMemory = mutableSetOf<Int>()
    /** Bullets used for the latest answer — the view reports whether that answer was accepted. */
    var lastMemoryIds: Set<Int> = emptySet()
        private set
    /** True when the user's typed percentage replaced a different one from the model this turn. */
    val percentCorrected get() = tools?.percentCorrected == true

    /** Remember an exchange. [seenByModel] = false for app-only commands the model didn't take part in. */
    fun record(user: String, answer: String, seenByModel: Boolean) {
        val t = Turn(user.take(300), answer.replace(Regex("""\s+"""), " ").take(500))
        if (t.answer.isBlank()) return
        synchronized(turns) {
            turns.addLast(t); while (turns.size > Compact.KEEP) turns.removeFirst()
            if (!seenByModel) { unseen += t; while (unseen.size > 3) unseen.removeAt(0) }
        }
    }

    private fun systemPrompt(bullets: List<Ace.Bullet>): String {
        val s = host.stock
        val ctxLine = when (s) {
            null -> "No stock is selected right now."
            is Stock.Holding -> "The user is looking at their holding ${s.name}: ${Fmt.qty(s.qty)} shares, avg ₹${Fmt.price(s.avg)}, LTP ₹${Fmt.price(s.ltp)}."
            is Stock.Quote -> "The user is looking at ${s.name} on their watchlist (not owned), LTP ₹${Fmt.price(s.ltp)}."
            is Stock.Portfolio -> "The user is looking at their whole portfolio: invested ₹${Fmt.amount(s.invested)}, current ₹${Fmt.amount(s.current)}."
        }
        val persona = Persona.of(prefsPersona())
        val kite = KiteMcpClient.get(ctx)
        val kiteLine = when (kite.link) {
            ChatFlow.Kite.CONNECTED -> "The user's Zerodha Kite account is CONNECTED (read-only): use kite_account for their real holdings/positions/P&L, then the calculators on those numbers."
            ChatFlow.Kite.EXPIRED -> "The user's Kite login has expired; if they ask about their real account, tell them to tap 'Reconnect Kite'."
            ChatFlow.Kite.OFF -> "Kite is not connected; if they ask about their real account, suggest tapping 'Connect Kite'."
        }
        val webLine = if (webOn()) "Web access is ON: use run_command for any of the app's commands (account, prices, charts, calendar, IPOs, market reports, terminal views) and the web tool for news, the market calendar (results, dividends, splits, bonus, AGM dates, IPOs, holidays — action=calendar or ipo), facts you don't know, and prices Kite can't give. Answer only from what it returns and cite sources like [1]. Never put the user's amounts or holdings in a web query."
            else "Web access is OFF: for news, results dates or live prices you can't get, say they can switch on Web with the globe pill at the top. Never invent such facts."
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH))
        return """
            You are Khayali Pulao, a what-if calculator for Indian stock investors, running fully on the user's phone.
            You help users explore scenarios for their portfolio with exact maths. Today is $today.
            $ctxLine
            $kiteLine
            $webLine
            Rules:
            - ALWAYS call a tool for any number (moves, FD comparison, goals, compounding, tax, regret) — including follow-ups like "and at 15%?" or "in today's money?". Call the tool again with the new values. Never do maths yourself.
            - The app automatically shows each tool's exact numbers in a visual card above your reply. So DON'T repeat amounts; add 1–3 sentences of insight (what it means, what to try next). If you must mention one figure, copy it exactly from the tool's "summary".
            - For "my portfolio / my biggest holding / my best or worst stock", call get_current_stock (or kite_account holdings) and pick from the result — never ask the user for data the tools can fetch. Pass "symbol" to project_move to use a specific holding.
            - Only if a tool says the data isn't available, ask one short question.
            - Write amounts in Indian style with ₹ (e.g. ₹1,23,456, ₹12.5 lakh, ₹1.2 crore).
            - Keep answers short: 2–4 sentences, or up to 4 bullets. Use **bold** sparingly. Never use tables or headings — the app already shows the numbers in a card.
            - You can change settings and open screens with tools when the user asks. Confirm what you changed.
            - If the market is closed or a live price is missing, use the last traded price / last close the tools give — never apologise or stop; just say it's the last price.
            - You cannot buy, sell or place orders, and you NEVER recommend buying, selling or holding any security, nor give target prices. If asked, explain the scenarios and say you can't give investment advice.
            - You are educational only, not a SEBI-registered investment adviser.
            - Always reply in English.
            - Persona: ${persona.title}. ${persona.focus} Be professional, clear and concise. No jokes, slang, Hindi words or emojis.
        """.trimIndent() + Ace.prompt(bullets).let { if (it.isEmpty()) "" else "\n\n$it" }
    }

    /**
     * Compaction: replace the model's view of the thread with a short, exact summary (questions and
     * the calculators' own summaries), so a long chat stays fast and never overflows the context.
     * Returns the summary, or null if there is nothing worth compacting.
     */
    fun compact(): String? {
        val summary = synchronized(turns) {
            val text = Compact.summary(turns.toList()) ?: return null
            turns.clear(); unseen.clear()
            turns.addLast(Turn("Summarise our chat so far.", text))
            text
        }
        stale = true
        compactions++
        return summary
    }

    /** How many times this thread has been compacted (automatically or by /compact). */
    var compactions = 0
        private set
    /** Set when the last turn pushed the context past the roll-over point; the view compacts before the next one. */
    @Volatile var needsCompaction = false
        private set

    /** Next turn rebuilds the conversation (new system prompt and tools) but keeps the thread. */
    fun invalidate() { stale = true }

    private fun alive(c: Conversation) = !stale && convGeneration == AiEngine.generation && runCatching { c.isAlive }.getOrDefault(false)

    private suspend fun ensure(query: String): Conversation {
        conversation?.let { c -> if (alive(c)) return c else { conversation = null; withContext(Dispatchers.IO) { runCatching { c.close() } } } }
        val engine = AiEngine.acquire(ctx)
        val tools = AppTools(host).also { this.tools = it }
        tools.onEventSink = { e -> emit(e) }
        val providers = tools.defs.map { d ->
            tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString() = tools.schemaJson(d)
                override fun execute(paramsJsonString: String) = ToolJson.compact(tools.execute(d.name, paramsJsonString))
            })
        } + kiteTools() + webTools() + commandTools()
        val bullets = memory(query)
        needsCompaction = false
        val seed = synchronized(turns) { unseen.clear(); turns.takeLast(SEED_TURNS) }.flatMap { listOf(Message.user(it.user), Message.model(it.answer)) }
        return withContext(Dispatchers.IO) {
            engine.createConversation(
                ConversationConfig(
                    Contents.of(systemPrompt(bullets)), seed, providers,
                    SamplerConfig(40, 0.95, 0.4, 0),   // topK, topP, temperature (low: numbers matter), seed
                    true,                               // automatic tool calling
                ),
            )
        }.also {
            conversation = it; convGeneration = AiEngine.generation; stale = false
            shownMemory.clear(); shownMemory += bullets.map { b -> b.id }
        }
    }

    private fun emit(e: ToolEvent) {
        synchronized(known) { known += ResultCards.amounts(e.resultJson) }
        synchronized(turnTools) { turnTools += e }
        scope.launch(Dispatchers.Main) { currentListener?.onTool(e) }
    }

    private fun starting(label: String) { scope.launch(Dispatchers.Main) { currentListener?.onToolStart(label) } }
    private fun arg(json: String, key: String): String? = runCatching { JsonParser.parseString(json).asJsonObject[key]?.takeIf { !it.isJsonNull }?.asString }.getOrNull()
    private fun since(t: Long) = (System.nanoTime() - t) / 1_000_000

    /** Read-only Kite tools, added only when the user has connected Kite (MCP). */
    private fun kiteTools(): List<ToolProvider> {
        val kite = KiteMcpClient.get(ctx)
        if (!kite.connected) return emptyList()
        fun run(name: String, label: String, args: String, block: suspend () -> String): String {
            starting("$label…")
            val t = System.nanoTime()
            val json = runBlocking { block() }
            emit(ToolEvent(name, label, ToolJson.ok(json), json, args, since(t)))
            return ToolJson.compact(json)
        }
        val account = object : OpenApiTool {
            override fun getToolDescriptionJsonString() = """{"name":"kite_account","description":"Read the user's REAL Zerodha Kite account (read-only): holdings, positions, margins, profile, mutual_funds, orders or trades. Use for questions about 'my portfolio', 'my holdings', 'my P&L'.","parameters":{"type":"object","properties":{"section":{"type":"string","enum":["holdings","positions","margins","profile","mutual_funds","orders","trades"],"description":"Which part of the account"}},"required":["section"]}}"""
            override fun execute(paramsJsonString: String): String {
                val section = arg(paramsJsonString, "section") ?: "holdings"
                return run("kite_account", "Read Kite $section", paramsJsonString) { kite.account(section) }
            }
        }
        val quote = object : OpenApiTool {
            override fun getToolDescriptionJsonString() = """{"name":"kite_quote","description":"Live last traded price from Kite for one or more NSE symbols, e.g. INFY, TCS.","parameters":{"type":"object","properties":{"symbols":{"type":"string","description":"Comma-separated trading symbols"}},"required":["symbols"]}}"""
            override fun execute(paramsJsonString: String): String {
                val syms = arg(paramsJsonString, "symbols").orEmpty().split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.take(10)
                return run("kite_quote", "Got live price", paramsJsonString) { kite.ltp(syms) }
            }
        }
        val chart = object : OpenApiTool {
            override fun getToolDescriptionJsonString() = """{"name":"kite_chart","description":"Price history chart from Kite for one NSE symbol over a period. Use when the user asks how a stock has done, its trend, chart or past prices.","parameters":{"type":"object","properties":{"symbol":{"type":"string","description":"NSE trading symbol, e.g. INFY"},"period":{"type":"string","enum":["1w","1m","3m","6m","1y","5y"]}},"required":["symbol"]}}"""
            override fun execute(paramsJsonString: String): String {
                val sym = arg(paramsJsonString, "symbol").orEmpty()
                return run("kite_chart", "Charted $sym", paramsJsonString) { kite.history(sym, arg(paramsJsonString, "period")) }
            }
        }
        return listOf(tool(account), tool(quote), tool(chart))
    }

    /** One consolidated web tool (search / price / events), present only when the user switched Web on. */
    private fun webTools(): List<ToolProvider> {
        if (!webOn()) return emptyList()
        val web = WebClient.get(ctx)
        val t = object : OpenApiTool {
            override fun getToolDescriptionJsonString() = """{"name":"web","description":"Look up PUBLIC information on the internet. Use only for what other tools can't answer: news, quarterly results, AGM/dividend/results dates, facts about a company, or the latest price of a stock Kite can't give. action=search: web search with sources. action=price: latest share price and price history. action=calendar: dated market events from NSE (results, dividends, bonus, splits, AGMs, IPOs, holidays) — query is 'week', 'today', 'tomorrow', 'month', a date, or one NSE symbol. action=ipo: open and upcoming IPOs with grey-market premium (GMP); put an IPO's name in query for its GMP trend and implied listing price.","parameters":{"type":"object","properties":{"action":{"type":"string","enum":["search","price","calendar","ipo"]},"query":{"type":"string","description":"Company name, NSE symbol, period or search words. Never include the user's amounts or holdings."},"period":{"type":"string","enum":["1w","1m","3m","6m","1y","5y"],"description":"For action=price: history period"}},"required":["action","query"]}}"""
            override fun execute(paramsJsonString: String): String {
                val q = arg(paramsJsonString, "query").orEmpty()
                val t0 = System.nanoTime()
                return when (arg(paramsJsonString, "action")) {
                    "price" -> {
                        starting("Getting the latest price…")
                        val json = runBlocking { web.quote(q, arg(paramsJsonString, "period")) }
                        emit(ToolEvent("web_quote", "Got the price", ToolJson.ok(json), json, paramsJsonString, since(t0))); ToolJson.compact(json)
                    }
                    "calendar", "events" -> {
                        starting("Reading the market calendar…")
                        val json = runBlocking { web.calendar(q.ifBlank { "week" }, host.holdings.map { it.name.uppercase() }.toSet()) }
                        emit(ToolEvent("web_calendar", "Read the calendar", ToolJson.ok(json), json, paramsJsonString, since(t0))); ToolJson.compact(json, 12)
                    }
                    "ipo" -> {
                        starting("Checking IPOs…")
                        val named = q.isNotBlank() && !q.equals("ipo", true) && !q.contains("upcoming", true) && !q.contains("open", true)
                        val json = runBlocking { if (named) web.gmp(q.replace(Regex("(?i)\\b(ipo|gmp)\\b"), "").trim()) else web.ipo() }
                        emit(ToolEvent(if (named) "web_gmp" else "web_ipo", "Checked IPOs", ToolJson.ok(json), json, paramsJsonString, since(t0))); ToolJson.compact(json)
                    }
                    else -> {
                        starting("Searching the web…")
                        val (forModel, forCard) = runBlocking { web.search(q) }
                        emit(ToolEvent("web_search", "Searched the web", ToolJson.ok(forCard), forCard, paramsJsonString, since(t0))); forModel
                    }
                }
            }
        }
        return listOf(tool(t))
    }

    /**
     * One tool for every command in the app: the model types a command exactly as the user would, and it runs
     * through the same code — same checks, same card. Present whenever the view provides a runner.
     */
    private fun commandTools(): List<ToolProvider> {
        val run = commands ?: return emptyList()
        val names = (Commands.ALL.map { it.name }.filter { it !in setOf("clear", "help", "remember", "memory", "forget", "compact") } +
            com.khayali.pulao.ai.term.Terminal.FEATURES.map { it.id }).distinct()
        val c = object : OpenApiTool {
            override fun getToolDescriptionJsonString() = com.google.gson.JsonObject().apply {
                addProperty("name", "run_command")
                addProperty("description", "Run one of the app's commands and show its chart card — the same as the user typing it. " +
                    "Use for the user's account, prices, charts, the market calendar, IPOs, market reports and every terminal view. " +
                    "Commands: " + names.joinToString(", ") + ". Examples: 'holdings', 'chart INFY 1y', 'price TCS', 'calendar tomorrow', 'ipo', 'gmp Crystal Crop', " +
                    "'heatmap NIFTY BANK', 'flow INFY', 'report', 'sip 10000 12% 15y'. " +
                    "For 'what should I do', 'how can I make profit' or a plan for today, run 'brief' (market regime, the week, hidden patterns, the user's holdings, " +
                    "what each strategy flags); for strategies use 'strategies', 'screen <id>', 'learn <topic>'; for one stock's trend use 'health SYMBOL'. " +
                    "These run on the user's own market data on the phone. Explain what the data shows and what to review — never tell the user to buy or sell.")
                add("parameters", JsonParser.parseString("""{"type":"object","properties":{"command":{"type":"string","description":"The command without the slash, with its arguments"}},"required":["command"]}"""))
            }.toString()
            override fun execute(paramsJsonString: String): String {
                val cmd = arg(paramsJsonString, "command").orEmpty().trim().removePrefix("/")
                if (cmd.isBlank()) return """{"error":"Give a command, e.g. 'holdings' or 'chart INFY 1y'."}"""
                starting("Running /${cmd.substringBefore(' ')}…")
                val t0 = System.nanoTime()
                val e = runBlocking(Dispatchers.IO) { run(cmd) }
                emit(e.copy(ms = since(t0)))
                return ToolJson.compact(ToolJson.withoutCard(e.resultJson))
            }
        }
        return listOf(tool(c))
    }


    fun send(text: String, listener: Listener, retried: Boolean = false) {
        if (busy) return
        job = scope.launch {
            currentListener = listener
            synchronized(known) { known.clear() }
            synchronized(turnTools) { turnTools.clear() }
            val tSend = System.nanoTime()
            val wasLoaded = AiEngine.state.value is AiEngine.State.Ready
            listener.onThinking(!wasLoaded)
            val sb = StringBuilder()
            var tFirst = 0L      // decode speed is measured from the first token, not from "send"
            var chunks = 0
            AiEngine.begin()
            try {
                stopAux()
                val conv = ensure(text)
                if (!wasLoaded) withContext(Dispatchers.Main) { listener.onEngineReady() }
                tools?.userPercents = AppTools.percents(text)
                tools?.percentCorrected = false
                conv.sendMessageAsync(withContextNotes(text)).catch { throw it }.collect { msg ->
                    val piece = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (piece.isEmpty()) return@collect
                    // Engine may emit deltas or the full text so far — handle both.
                    if (piece.startsWith(sb) && piece.length >= sb.length) { sb.setLength(0); sb.append(piece) } else sb.append(piece)
                    if (chunks == 0) tFirst = System.nanoTime()
                    chunks++
                    withContext(Dispatchers.Main) { listener.onText(guard(sb.toString())) }
                }
                val secs = (System.nanoTime() - tFirst) / 1e9
                val tps = if (secs > 0.2 && chunks > 3) (chunks - 1) / secs else null   // ≈ tokens/s (one chunk ≈ one token)
                val ready = AiEngine.state.value as? AiEngine.State.Ready
                val stats = TurnStats((System.nanoTime() - tSend) / 1_000_000, if (tFirst > 0) (tFirst - tSend) / 1_000_000 else null, tps,
                    ready?.backend, if (!wasLoaded) ready?.loadMs else null, synchronized(turnTools) { turnTools.toList() })
                if (withContext(Dispatchers.IO) { runCatching { conv.getTokenCount() }.getOrDefault(0) } > ROLL_AT) needsCompaction = true     // compact before the context overflows
                withContext(Dispatchers.Main) { listener.onDone(guard(sb.toString().trim()), stats) }
            } catch (c: CancellationException) {
                withContext(Dispatchers.Main + NonCancellable) {
                    listener.onDone(sb.toString().trim(), TurnStats((System.nanoTime() - tSend) / 1_000_000, null, null, null, null, synchronized(turnTools) { turnTools.toList() }))
                }
            } catch (t: Throwable) {
                if (com.khayali.pulao.BuildConfig.DEBUG) android.util.Log.w("KhayaliAI", "turn failed (retried=$retried, tokens so far=${sb.length})", t)
                val nothingShown = sb.isEmpty() && synchronized(turnTools) { turnTools.isEmpty() }
                // A small model sometimes writes a tool call the runtime can't parse. No text has streamed, so asking again is safe.
                val fumbledToolCall = sb.isEmpty() && isToolCallError(t)
                if (!retried && (nothingShown || fumbledToolCall) && AiEngine.state.value !is AiEngine.State.Failed && ModelManager.get(ctx).isReady()) {
                    // Nothing reached the user yet, so recover silently: a GPU that can't run here falls back to CPU;
                    // anything else (dead conversation after an unload, context overflow) gets a fresh conversation.
                    conversation?.let { c -> runCatching { c.close() } }; conversation = null
                    if (AiEngine.onGpu && !isContextError(t) && !fumbledToolCall) AiEngine.fallBackToCpu()
                    job = null
                    // The sampler is seeded, so the same prompt would fumble the same way: say what went wrong.
                    val again = if (fumbledToolCall) "$text\n(Your last tool call was malformed. Call the tool again; give every argument a complete value and leave out optional arguments you don't need.)" else text
                    withContext(Dispatchers.Main) { send(again, listener, retried = true) }
                    return@launch
                }
                conversation?.let { c -> runCatching { c.close() } }; conversation = null       // never reuse a conversation that just failed
                withContext(Dispatchers.Main) { listener.onError(friendly(t)) }
            } finally {
                AiEngine.end()
            }
        }
    }

    private fun isToolCallError(t: Throwable) = (t.message ?: "").contains("parse tool call", true) || (t.message ?: "").contains("Failed to parse FC", true)
    private fun isContextError(t: Throwable) = !isToolCallError(t) &&
        Regex("""(?i)(context|tokens?|input).{0,40}(exceed|too long|too large|overflow|limit)|(exceed|maximum).{0,40}(context|tokens?)""").containsMatchIn(t.message ?: "")

    /** Web answers quote snippets (any format); calculator answers may only quote figures the tools produced. */
    private fun guard(text: String): String {
        val web = synchronized(turnTools) { turnTools.any { it.name.startsWith("web_") } }
        return if (web) NumberGuard.fix(text) else NumberGuard.verify(text, synchronized(known) { known.toList() })
    }

    /** Prepends what happened since the model's last reply, and any memory it hasn't been shown yet. */
    private fun withContextNotes(text: String): String {
        val notes = StringBuilder()
        val missed = synchronized(turns) { unseen.toList().also { unseen.clear() } }
        if (missed.isNotEmpty()) {
            notes.append("(Since your last reply the user ran these in the app:\n")
            missed.forEach { notes.append("- \"").append(it.user).append("\" → ").append(it.answer.take(240)).append('\n') }
            notes.append(")\n")
        }
        val fresh = memory(text).filter { it.id !in shownMemory }
        if (fresh.isNotEmpty()) {
            notes.append("(Also remember about this user: ").append(fresh.joinToString(" ") { it.text }).append(")\n")
            shownMemory += fresh.map { it.id }
        }
        lastMemoryIds = shownMemory.toSet()
        return if (notes.isEmpty()) text else "$notes\n$text"
    }

    // ------------------------------------------------- background model work

    private fun stopAux() {
        runCatching { auxConversation?.cancelProcess() }
        aux?.cancel()
    }

    /** Runs [prompt] on a CLONE of the conversation so the real thread isn't polluted. */
    private suspend fun onClone(prompt: String): String? = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext null
        if (busy || !alive(conv)) return@withContext null
        AiEngine.begin()
        try {
            runCatching {
                val c = conv.clone()
                auxConversation = c
                try { c.sendMessage(prompt).contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text } }
                finally { auxConversation = null; runCatching { c.close() } }
            }.getOrNull()
        } finally { AiEngine.end() }
    }

    /**
     * After an answer: model-written follow-up pills, then (only when the user said something about
     * themselves) a reflection pass for memory. Sequential, cancellable, and skipped while busy.
     */
    fun afterTurn(userText: String, onPills: (List<String>) -> Unit, onReflection: (List<Ace.Delta>) -> Unit, reflect: Boolean) {
        aux?.cancel()
        val finishing = job          // the turn that just produced the answer is still unwinding; wait for it, not for a later one
        aux = scope.launch {
            finishing?.join()
            if (busy) return@launch
            onClone(PILLS_PROMPT)
                ?.let { ContextPills.parseModel(it) }?.takeIf { it.isNotEmpty() && !busy }?.let { withContext(Dispatchers.Main) { onPills(it) } }
            if (reflect && Ace.worthReflecting(userText) && !busy)
                onClone(Ace.REFLECT_PROMPT)?.let { Ace.parseReflection(it) }?.takeIf { it.isNotEmpty() }?.let { withContext(Dispatchers.Main) { onReflection(it) } }
        }
    }

    fun stop() {
        runCatching { conversation?.cancelProcess() }
        job?.cancel()
    }

    /** Start a fresh thread (new chat, or the tapped stock changed). Forgets the exchanges. */
    fun reset() {
        stop(); stopAux()
        synchronized(turns) { turns.clear(); unseen.clear() }
        shownMemory.clear(); lastMemoryIds = emptySet()
        closeConversation()
    }

    /** The view is going away: release native resources. */
    fun close() { stop(); stopAux(); closeConversation() }

    private fun closeConversation() {
        val c = conversation ?: return
        conversation = null
        // Not on the caller's scope: it may already be cancelled, and the native handle must still be freed.
        Thread { runCatching { c.close() } }.start()
    }

    private fun friendly(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            "downloaded" in m -> "The AI model isn't downloaded yet."
            "memory" in m.lowercase() || t is OutOfMemoryError -> "Not enough memory right now. Close a few apps and try again."
            isContextError(t) -> "This chat got too long for the on-device model. Start a new chat and ask again."
            isToolCallError(t) -> "The on-device model fumbled that calculation twice. Try again, or use a command — type / to see them (for example /move -20)."
            else -> "Something went wrong running the model on your phone. Try again."
        }
    }

    companion object {
        /** Engine context is 4096 tokens; rebuild the conversation once it is about two-thirds full. */
        const val ROLL_AT = 2700
        const val SEED_TURNS = 3
        /** Pills are a reflection on the conversation, not a fixed list: what is the user trying to work out, and what is the next step? */
        const val PILLS_PROMPT = "Reflect silently on this chat: what is the user trying to work out, what did the last result show, and what do you " +
            "remember about them? Then write exactly 3 short follow-up questions (max 8 words each) that take the next step — one that goes deeper " +
            "into the last result, one that tests the opposite case, one that connects it to their goal or holdings. " +
            "One per line, no numbering, no explanations, nothing else."
    }
}

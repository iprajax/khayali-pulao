package com.khayali.pulao.ai

import android.content.Context
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import com.khayali.pulao.Fmt
import com.khayali.pulao.Stock
import com.khayali.pulao.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One chat thread: owns a LiteRT-LM Conversation with the app's tools attached,
 * streams the answer, and reports tool calls to the UI. UI-agnostic.
 */
class ChatSession(
    private val ctx: Context,
    private val host: ToolHost,
    private val tone: () -> Tone,
    private val scope: CoroutineScope,
) {
    interface Listener {
        fun onThinking()
        fun onTool(e: ToolEvent)
        fun onText(full: String)
        fun onDone(full: String, tokensPerSec: Double?)
        fun onError(message: String)
    }

    private var conversation: Conversation? = null
    /** Amounts produced by tools during the current turn — the only ₹ figures the model may quote. */
    private val known = mutableListOf<Double>()
    @Volatile private var currentListener: Listener? = null
    private var job: Job? = null
    val busy get() = job?.isActive == true

    private fun systemPrompt(): String {
        val s = host.stock
        val ctxLine = when (s) {
            null -> "No stock is selected right now."
            is Stock.Holding -> "The user is looking at their holding ${s.name}: ${Fmt.qty(s.qty)} shares, avg ₹${Fmt.price(s.avg)}, LTP ₹${Fmt.price(s.ltp)}."
            is Stock.Quote -> "The user is looking at ${s.name} on their watchlist (not owned), LTP ₹${Fmt.price(s.ltp)}."
            is Stock.Portfolio -> "The user is looking at their whole portfolio: invested ₹${Fmt.amount(s.invested)}, current ₹${Fmt.amount(s.current)}."
        }
        val style = when (tone()) {
            Tone.SERIOUS -> "Be plain, precise and brief. No jokes, no emojis."
            Tone.LIGHT -> "Be warm and calm. At most one light touch of humour."
            Tone.MASALA -> "Be playful and witty with desi humour (FD wale uncle, Sharma ji ka beta, Ballia plots) but keep numbers exact."
        }
        return """
            You are Khayali Pulao, a friendly daydream calculator for Indian stock investors, running fully on the user's phone.
            "Khayali pulao" means daydreaming. You help users imagine what-if scenarios with exact maths.
            $ctxLine
            ${if (KiteMcpClient.get(ctx).connected) "The user's Zerodha Kite account is CONNECTED (read-only): use kite_account for their real holdings/positions/P&L, then the calculators on those numbers." else "Kite is not connected; if they ask about their real account, suggest tapping 'Connect Kite'."}
            Rules:
            - ALWAYS call a tool for any number (moves, FD comparison, goals, compounding, tax, regret) — including follow-ups like "and at 15%?" or "in today's money?". Call the tool again with the new values. Never do maths yourself.
            - The app automatically shows each tool's exact numbers in a visual card above your reply. So DON'T repeat long amounts; say things like "as shown above" and add 1–3 sentences of insight (what it means, what to try next). If you must mention one figure, copy it exactly from the tool's "summary".
            - If information is missing (stock, amount, years held), ask one short question.
            - Write amounts in Indian style with ₹ (e.g. ₹1,23,456, ₹12.5 lakh, ₹1.2 crore).
            - Keep answers short: 2–5 sentences or a few bullets. Use **bold** for the key number.
            - You can change settings and open screens with tools when the user asks. Confirm what you changed.
            - You cannot buy, sell or place orders, and you never recommend buying or selling. If asked, say it's a daydream tool, not advice.
            - Reply in the same language the user wrote in (English → English, Hindi/Hinglish → Hinglish).
            - $style
        """.trimIndent()
    }

    private suspend fun ensure(listener: Listener): Conversation {
        conversation?.takeIf { it.isAlive }?.let { return it }
        val engine = AiEngine.acquire(ctx)
        val tools = AppTools(host) { e ->
            synchronized(known) { known += ResultCards.amounts(e.resultJson) }
            scope.launch(Dispatchers.Main) { currentListener?.onTool(e) }
        }
        val providers = tools.defs.map { d ->
            tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString() = tools.schemaJson(d)
                override fun execute(paramsJsonString: String) = tools.execute(d.name, paramsJsonString)
            })
        } + kiteTools()
        return withContext(Dispatchers.IO) {
            engine.createConversation(
                ConversationConfig(
                    Contents.of(systemPrompt()), emptyList(), providers,
                    SamplerConfig(40, 0.95, 0.4, 0),   // topK, topP, temperature (low: numbers matter), seed
                    true,                               // automatic tool calling
                ),
            )
        }.also { conversation = it }
    }

    /** Read-only Kite tools, added only when the user has connected Kite (MCP). */
    private fun kiteTools(): List<com.google.ai.edge.litertlm.ToolProvider> {
        val kite = KiteMcpClient.get(ctx)
        if (!kite.connected) return emptyList()
        fun emit(name: String, label: String, json: String) {
            val ok = !json.contains("\"error\"")
            synchronized(known) { known += ResultCards.amounts(json) }
            scope.launch(Dispatchers.Main) { currentListener?.onTool(ToolEvent(name, label, ok, json)) }
        }
        val account = object : OpenApiTool {
            override fun getToolDescriptionJsonString() = """{"name":"kite_account","description":"Read the user's REAL Zerodha Kite account (read-only): holdings, positions, margins, profile, mutual_funds, orders or trades. Use for questions about 'my portfolio', 'my holdings', 'my P&L'.","parameters":{"type":"object","properties":{"section":{"type":"string","enum":["holdings","positions","margins","profile","mutual_funds","orders","trades"],"description":"Which part of the account"}},"required":["section"]}}"""
            override fun execute(paramsJsonString: String): String {
                val section = runCatching { com.google.gson.JsonParser.parseString(paramsJsonString).asJsonObject["section"].asString }.getOrDefault("holdings")
                return kotlinx.coroutines.runBlocking { kite.account(section) }.also { emit("kite_account", "Read Kite $section", it) }
            }
        }
        val quote = object : OpenApiTool {
            override fun getToolDescriptionJsonString() = """{"name":"kite_quote","description":"Live last traded price from Kite for one or more NSE symbols, e.g. INFY, TCS.","parameters":{"type":"object","properties":{"symbols":{"type":"string","description":"Comma-separated trading symbols"}},"required":["symbols"]}}"""
            override fun execute(paramsJsonString: String): String {
                val syms = runCatching { com.google.gson.JsonParser.parseString(paramsJsonString).asJsonObject["symbols"].asString }.getOrDefault("")
                    .split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.take(10)
                return kotlinx.coroutines.runBlocking { kite.ltp(syms) }.also { emit("kite_quote", "Got live price", it) }
            }
        }
        return listOf(tool(account), tool(quote))
    }

    fun send(text: String, listener: Listener, retried: Boolean = false) {
        if (busy) return
        job = scope.launch {
            currentListener = listener
            synchronized(known) { known.clear() }
            listener.onThinking()
            val sb = StringBuilder()
            var tFirst = 0L      // decode speed is measured from the first token, not from "send"
            var chunks = 0
            try {
                val conv = ensure(listener)
                AiEngine.touch()
                conv.sendMessageAsync(text).catch { throw it }.collect { msg ->
                    val piece = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (piece.isEmpty()) return@collect
                    // Engine may emit deltas or the full text so far — handle both.
                    if (piece.startsWith(sb) && piece.length >= sb.length) { sb.setLength(0); sb.append(piece) } else sb.append(piece)
                    if (chunks == 0) tFirst = System.nanoTime()
                    chunks++
                    withContext(Dispatchers.Main) { listener.onText(NumberGuard.verify(sb.toString(), synchronized(known) { known.toList() })) }
                }
                val secs = (System.nanoTime() - tFirst) / 1e9
                val tps = if (secs > 0.2 && chunks > 3) (chunks - 1) / secs else null   // ≈ tokens/s (one chunk ≈ one token)
                AiEngine.touch()
                withContext(Dispatchers.Main) { listener.onDone(NumberGuard.verify(sb.toString().trim(), synchronized(known) { known.toList() }), tps) }
            } catch (c: CancellationException) {
                withContext(Dispatchers.Main + kotlinx.coroutines.NonCancellable) { listener.onDone(sb.toString().trim(), null) }
            } catch (t: Throwable) {
                if (!retried && sb.isEmpty() && AiEngine.onGpu) {
                    // GPU initialised but can't run here (e.g. no OpenCL): retry once on CPU, transparently.
                    conversation?.let { c -> runCatching { c.close() } }; conversation = null
                    AiEngine.fallBackToCpu()
                    job = null
                    withContext(Dispatchers.Main) { send(text, listener, retried = true) }
                    return@launch
                }
                withContext(Dispatchers.Main) { listener.onError(friendly(t)) }
            }
        }
    }

    fun stop() {
        runCatching { conversation?.cancelProcess() }
        job?.cancel()
    }

    /** Start a fresh thread (e.g. when the tapped stock changes or the user hits "New chat"). */
    fun reset() {
        stop()
        conversation?.let { c -> scope.launch(Dispatchers.IO) { runCatching { c.close() } } }
        conversation = null
    }

    private fun friendly(t: Throwable): String {
        val m = t.message.orEmpty()
        return when {
            "downloaded" in m -> "The AI model isn't downloaded yet."
            "memory" in m.lowercase() -> "Not enough memory right now. Close a few apps and try again."
            else -> "Something went wrong running the model on your phone. Try again."
        }
    }
}

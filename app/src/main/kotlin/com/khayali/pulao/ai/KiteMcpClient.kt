package com.khayali.pulao.ai

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * Optional, opt-in connection to Zerodha's official Kite MCP server (mcp.kite.trade).
 *
 * Auth is standard MCP OAuth 2.1: dynamic client registration → authorization code with
 * PKCE in the browser (Zerodha's own login page) → HTTPS callback page → app link back
 * into the app → bearer token. Read-only by construction: every tool call is checked against
 * [KiteData.READ_ONLY] before it is sent.
 */
class KiteMcpClient private constructor(ctx: Context) {

    sealed class State {
        data object Off : State()
        data object Connecting : State()
        data class WaitingForBrowser(val url: String) : State()
        data class Connected(val name: String?) : State()
        data class Failed(val message: String) : State()
    }

    private val prefs = ctx.applicationContext.getSharedPreferences("khayali_kite", Context.MODE_PRIVATE)
    private val ids = AtomicInteger(1)
    private val gson = Gson()
    private val _state = MutableStateFlow<State>(if (token != null) State.Connected(prefs.getString(KEY_NAME, null)) else State.Off)
    val state: StateFlow<State> = _state
    val connected get() = _state.value is State.Connected

    private var token: String?
        get() = prefs.getString(KEY_TOKEN, null)?.takeIf { prefs.getLong(KEY_EXPIRES, Long.MAX_VALUE) > System.currentTimeMillis() }
        set(v) { prefs.edit().putString(KEY_TOKEN, v).apply() }
    private var session: String? = null

    /** Tickers from the last holdings read — powers @mention autocomplete. */
    val knownSymbols: List<String> get() = prefs.getString(KEY_SYMBOLS, "")!!.split(',').filter { it.isNotBlank() }

    // =============================================================== OAuth

    /**
     * Step 1: register (once), create PKCE + state, open Zerodha's authorize page.
     * Zerodha redirects to our HTTPS callback page, which hands the code to [KiteCallbackActivity].
     */
    suspend fun connect(openBrowser: (String) -> Unit): State = withContext(Dispatchers.IO) {
        _state.value = State.Connecting
        runCatching {
            val clientId = register(KiteData.REDIRECT)
            val verifier = randomUrlSafe(64)
            val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            val stateParam = randomUrlSafe(24)
            prefs.edit().putString(KEY_PENDING, "$stateParam|$verifier|$clientId").apply()
            val url = KiteData.AUTH_BASE + "/authorize?" + form(
                "response_type" to "code", "client_id" to clientId, "redirect_uri" to KiteData.REDIRECT,
                "code_challenge" to challenge, "code_challenge_method" to "S256",
                "state" to stateParam, "scope" to "default", "resource" to KiteData.ENDPOINT,
            )
            withContext(Dispatchers.Main) { openBrowser(url) }
            State.WaitingForBrowser(url)
        }.getOrElse { State.Failed(it.message ?: "Couldn't reach Kite") }.also { _state.value = it }
    }

    /** Step 2: called with the redirect (khayalipulao://kite/callback?code&state). */
    suspend fun complete(code: String?, state: String?, error: String?): State = withContext(Dispatchers.IO) {
        _state.value = State.Connecting
        runCatching {
            if (error != null) error(error)
            val pending = prefs.getString(KEY_PENDING, null)?.split('|') ?: error("No login in progress — tap Connect Kite again")
            val (expectedState, verifier, clientId) = Triple(pending[0], pending[1], pending[2])
            if (state != expectedState) error("Security check failed — please try again")
            exchange(code ?: error("Login was cancelled"), clientId, KiteData.REDIRECT, verifier)
            prefs.edit().remove(KEY_PENDING).apply()
            session = null
            val name = runCatching {
                JsonParser.parseString(KiteData.summarize("profile", call("portfolio", mapOf("mode" to "profile")))).asJsonObject["name"]?.asString
            }.getOrNull()
            prefs.edit().putString(KEY_NAME, name).apply()
            runCatching { account("holdings") }      // warm the @mention list
            State.Connected(name)
        }.getOrElse { State.Failed(it.message ?: "Couldn't finish Kite login") }.also { _state.value = it }
    }

    private fun register(redirect: String): String {
        prefs.getString(KEY_CLIENT, null)?.let { saved ->
            val (id, uri) = saved.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (uri == redirect) return id
        }
        val body = JsonObject().apply {
            addProperty("client_name", "Khayali Pulao (Android)")
            add("redirect_uris", gson.toJsonTree(listOf(redirect)))
            add("grant_types", gson.toJsonTree(listOf("authorization_code")))
            add("response_types", gson.toJsonTree(listOf("code")))
            addProperty("token_endpoint_auth_method", "none")
        }
        val res = http("POST", KiteData.AUTH_BASE + "/register", "application/json", gson.toJson(body), auth = false)
        val id = JsonParser.parseString(res).asJsonObject["client_id"]?.asString ?: error("Kite didn't register the app")
        prefs.edit().putString(KEY_CLIENT, "$id|$redirect").apply()
        return id
    }

    private fun exchange(code: String, clientId: String, redirect: String, verifier: String) {
        val res = http("POST", KiteData.AUTH_BASE + "/token", "application/x-www-form-urlencoded", form(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirect,
            "client_id" to clientId, "code_verifier" to verifier, "resource" to KiteData.ENDPOINT,
        ), auth = false)
        val o = JsonParser.parseString(res).asJsonObject
        val t = o["access_token"]?.asString ?: error(o["error_description"]?.asString ?: "No token from Kite")
        val ttl = o["expires_in"]?.asLong ?: (12 * 3600L)
        prefs.edit().putString(KEY_TOKEN, t).putLong(KEY_EXPIRES, System.currentTimeMillis() + ttl * 1000).apply()
    }

    // ================================================================ MCP

    private class Unauthorized : Exception("Kite login expired — tap the Kite pill to reconnect")

    private fun http(method: String, url: String, type: String, body: String, auth: Boolean = true): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; doOutput = true; connectTimeout = 15_000; readTimeout = 45_000
            setRequestProperty("Content-Type", type)
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (auth) {
                token?.let { setRequestProperty("Authorization", "Bearer $it") } ?: throw Unauthorized()
                setRequestProperty("MCP-Protocol-Version", PROTOCOL)
                session?.let { setRequestProperty("Mcp-Session-Id", it) }
            }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (auth) conn.getHeaderField("Mcp-Session-Id")?.let { session = it }
            if (code == 401) { if (auth) { prefs.edit().remove(KEY_TOKEN).apply(); _state.value = State.Off }; throw Unauthorized() }
            if (code == 404 && auth && session != null) { session = null; throw SessionGone() }
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && code != 202) error("Kite returned HTTP $code ${text.take(120)}")
            return text
        } finally { conn.disconnect() }
    }

    private class SessionGone : Exception()

    private fun rpc(method: String, params: JsonObject = JsonObject()) = JsonObject().apply {
        addProperty("jsonrpc", "2.0"); addProperty("id", ids.getAndIncrement()); addProperty("method", method); add("params", params)
    }

    private fun handshake() {
        session = null
        val init = JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL); add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply { addProperty("name", "khayali-pulao"); addProperty("version", "0.5") })
        }
        http("POST", KiteData.ENDPOINT, "application/json", gson.toJson(rpc("initialize", init)))
        http("POST", KiteData.ENDPOINT, "application/json", """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
    }

    /** Calls one read-only (tool, mode). Refuses anything not on the allowlist. */
    private fun call(tool: String, args: Map<String, Any?>): String {
        require(KiteData.allowed(tool, args["mode"] as? String)) { "Blocked: '$tool/${args["mode"]}' is not a read-only Kite call" }
        if (session == null) handshake()
        val params = JsonObject().apply { addProperty("name", tool); add("arguments", gson.toJsonTree(args)) }
        val body = try { http("POST", KiteData.ENDPOINT, "application/json", gson.toJson(rpc("tools/call", params))) }
            catch (e: SessionGone) { handshake(); http("POST", KiteData.ENDPOINT, "application/json", gson.toJson(rpc("tools/call", params))) }
        return KiteData.resultText(KiteData.parseRpc(body) ?: error("Unreadable reply from Kite"))
    }

    // ============================================================ public API

    /** Compact JSON for the chat (or {"error":…}). Also refreshes the @mention list on holdings reads. */
    suspend fun account(section: String): String = withContext(Dispatchers.IO) {
        val (tool, mode) = KiteData.SECTIONS[section] ?: return@withContext """{"error":"Unknown section '$section'"}"""
        runCatching {
            val args = mutableMapOf<String, Any?>("mode" to mode)
            if (section == "holdings" || section == "positions") { args["type"] = "full"; args["limit"] = 500 }
            val out = KiteData.summarize(section, call(tool, args))
            if (section == "holdings") runCatching {
                val rows = JsonParser.parseString(out).asJsonObject.getAsJsonArray("holdings").map { it.asJsonObject }
                val e = prefs.edit().putString(KEY_SYMBOLS, rows.joinToString(",") { it["symbol"].asString })
                rows.forEach { r ->
                    r["token"]?.asLong?.takeIf { it > 0 }?.let { e.putLong("tok:" + r["symbol"].asString, it) }
                    r["exchange"]?.asString?.let { e.putString("ex:" + r["symbol"].asString, it) }
                }
                e.apply()
            }
            out
        }.getOrElse { """{"error": ${gson.toJson(it.message ?: "Kite unavailable")}}""" }
    }

    /** Live prices on each symbol's own exchange (from holdings), falling back NSE → BSE. */
    suspend fun ltp(symbols: List<String>): String = withContext(Dispatchers.IO) {
        fun inst(s: String, ex: String? = null) = if (":" in s) s else "${ex ?: prefs.getString("ex:$s", null) ?: "NSE"}:$s"
        runCatching {
            var out = KiteData.summarize("ltp", call("market", mapOf("mode" to "ltp", "instruments" to symbols.map { inst(it) })))
            if (out.contains("\"error\"") || !out.contains("\"quotes\":[{")) {
                out = runCatching { KiteData.summarize("ltp", call("market", mapOf("mode" to "ltp", "instruments" to symbols.map { inst(it, "BSE") }))) }.getOrDefault(out)
            }
            if (out.contains("\"error\"") || !out.contains("\"quotes\":[{")) {
                // Market closed / no live tick: use the last daily close so the maths can still go ahead.
                val quotes = symbols.mapNotNull { sym ->
                    runCatching { JsonParser.parseString(historyNow(sym.substringAfter(':'), "1m")).asJsonObject }.getOrNull()
                        ?.takeIf { !it.has("error") }?.let { mapOf("symbol" to sym.substringAfter(':'), "ltp" to it["last"].asDouble, "source" to "last close") }
                }
                if (quotes.isNotEmpty()) out = gson.toJson(mapOf("summary" to quotes.joinToString("; ") { "${it["symbol"]} ₹${com.khayali.pulao.Fmt.price(it["ltp"] as Double)} (last close)" } +
                    ". Live price unavailable — using the last close.", "quotes" to quotes, "market_closed" to true))
            }
            out
        }.getOrElse { """{"error": ${gson.toJson(it.message ?: "Kite unavailable")}}""" }
    }

    fun exchangeOf(symbol: String): String? = prefs.getString("ex:$symbol", null)

    /** Price history for a symbol (resolves the instrument token via holdings cache or search). */
    suspend fun history(symbol: String, period: String?): String = withContext(Dispatchers.IO) { historyNow(symbol, period) }

    private fun historyNow(symbol: String, period: String?): String {
        return runCatching {
            val sym = symbol.uppercase().removePrefix("NSE:").trim()
            val token = prefs.getLong("tok:$sym", 0L).takeIf { it > 0 }
                ?: KiteData.firstToken(call("market", mapOf("mode" to "search", "query" to sym, "limit" to 10)), sym)
                ?: error("Couldn't find $sym on Kite")
            prefs.edit().putLong("tok:$sym", token).apply()
            val (from, to, interval) = KiteData.period(period)
            val out = KiteData.summarize("history", call("market", mapOf("mode" to "historical", "instrument_token" to token,
                "interval" to interval, "from_date" to from, "to_date" to to)))
            JsonParser.parseString(out).asJsonObject.apply { addProperty("symbol", sym); addProperty("period", period ?: "6m") }.toString()
        }.getOrElse { """{"error": ${gson.toJson(it.message ?: "Kite unavailable")}}""" }
    }

    fun disconnect() {
        val client = prefs.getString(KEY_CLIENT, null)
        prefs.edit().clear().apply()
        client?.let { prefs.edit().putString(KEY_CLIENT, it).apply() }   // keep the app registration, drop the login
        session = null
        _state.value = State.Off
    }

    // ============================================================== helpers

    private fun form(vararg kv: Pair<String, String>) =
        kv.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun randomUrlSafe(n: Int) = b64url(ByteArray(n).also { SecureRandom().nextBytes(it) }).take(n)

    companion object {
        private const val PROTOCOL = "2025-03-26"
        private const val KEY_TOKEN = "token"
        private const val KEY_EXPIRES = "token_expires"
        private const val KEY_CLIENT = "client"
        private const val KEY_NAME = "name"
        private const val KEY_SYMBOLS = "symbols"
        private const val KEY_PENDING = "pending"
        @Volatile private var instance: KiteMcpClient? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: KiteMcpClient(ctx).also { instance = it } }
    }
}

package com.khayali.pulao.ai

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Opt-in web access (off until the user turns it on). Two destinations only, both by host
 * constant: DuckDuckGo's HTML results page for search, and a public quote feed for prices.
 *
 * Search runs in a hidden, locked-down browser view because DuckDuckGo refuses plain HTTP
 * clients: only the results page itself may load — every other request (images, scripts,
 * trackers, result sites) is refused, cookies are off, and the user agent is generic. Queries
 * are cleaned by [WebData.sanitize] first, so amounts and identifiers never go out.
 */
class WebClient private constructor(ctx: Context) {
    private val app = ctx.applicationContext
    private val gson = Gson()

    /** Search → (compact JSON for the model, full JSON with links for the card). */
    suspend fun search(query: String): Pair<String, String> {
        val q = WebData.sanitize(query)
        if (q.isBlank()) return err("Nothing to search for.").let { it to it }
        return runCatching {
            val results = results(q)
            WebData.searchJson(q, results, withUrls = false) to WebData.searchJson(q, results, withUrls = true)
        }.getOrElse { e -> err(Net.friendly(e, WebData.SEARCH_NAME)).let { it to it } }
    }

    private suspend fun results(q: String): List<WebData.Result> {
        val html = page(WebData.searchUrl(q), WebData.SEARCH_HOST, text = false, cookies = false)
        if (WebData.isChallenge(html)) throw IOException("${WebData.SEARCH_NAME} asked for a human check. Wait a minute and try again.")
        return WebData.parseSearch(html)
    }

    /** Latest price + history for a ticker, index or company name. */
    suspend fun quote(input: String, period: String?): String = withContext(Dispatchers.IO) {
        runCatching {
            val name = input.trim().removePrefix("@")
            val last = err("Couldn't find \"$name\". Try the NSE symbol, e.g. INFY.")
            val tried = mutableSetOf<String>()
            fun attempt(sym: String): String? =
                if (tried.add(sym)) WebData.parseChart(get(WebData.chartUrl(sym, period)), period).takeIf { ToolJson.ok(it) } else null
            for (sym in WebData.candidates(name)) attempt(sym)?.let { return@runCatching it }
            WebData.parseLookup(get(WebData.lookupUrl(name)))?.let { sym -> attempt(sym)?.let { return@runCatching it } }
            last
        }.getOrElse { err(Net.friendly(it, WebData.QUOTE_NAME)) }
    }

    /** Dividends and splits from the quote feed, plus dated results / AGM / board-meeting mentions from search. */
    suspend fun events(company: String): String {
        val q = quote(company, "1y")
        val o = runCatching { com.google.gson.JsonParser.parseString(q).asJsonObject }.getOrNull()
        val name = o?.get("name")?.asString ?: company
        val fromFeed = o?.getAsJsonArray("events")?.map { it.asJsonObject }?.mapNotNull { e ->
            runCatching { WebData.Event(java.time.LocalDate.parse(e["date"].asString), e["kind"].asString, e["title"].asString, 0) }.getOrNull()
        }.orEmpty()
        val results = runCatching { results(WebData.sanitize("$name quarterly results date AGM dividend record date board meeting")) }.getOrDefault(emptyList())
        if (fromFeed.isEmpty() && results.isEmpty()) return err(ToolJson.error(q) ?: "Couldn't find events for $company.")
        return WebData.eventsJson(o?.get("symbol")?.asString ?: company, (fromFeed + WebData.events(results)).sortedBy { it.date }, results)
    }

    private val IST = java.time.ZoneId.of("Asia/Kolkata")
    private fun today() = java.time.LocalDate.now(IST)

    /** One NSE feed as text. NSE refuses plain HTTP clients, so it is read through the locked-down browser view. */
    private suspend fun nse(url: String): String = runCatching { page(url, MarketData.NSE_HOST, text = true, cookies = true) }.getOrElse { "" }

    /**
     * The market calendar. [query] is a period ("week", "today", "month", a date) for the whole market,
     * or a symbol / company for one stock. [mine] (the user's own symbols) is used only on the phone, to
     * put their events first; it is never part of a request.
     */
    suspend fun calendar(query: String, mine: Set<String>): String = runCatching {
        val today = today()
        val window = MarketData.window(query, today)
        coroutineScope {
            if (window != null) {
                val (from, to) = window
                val events = async { MarketData.parseEvents(nse(MarketData.eventsUrl(from, to))) }
                val actions = async { MarketData.parseActions(nse(MarketData.actionsUrl(from, to))) }
                val ipos = async { MarketData.ipoItems(MarketData.parseIpos(nse(MarketData.ipoUpcomingUrl)) + MarketData.parseIpos(nse(MarketData.ipoCurrentUrl))) }
                val holidays = async { MarketData.parseHolidays(nse(MarketData.holidaysUrl)) }
                // One-day views also get Moneycontrol's results list, which names companies NSE's feed may not have yet.
                val results = async { if (from == to) runCatching { MarketData.parseResultsPage(get(MarketData.resultsUrl(from), html = true), from) }.getOrDefault(emptyList()) else emptyList() }
                val all = events.await() + actions.await() + ipos.await() + holidays.await() + results.await()
                if (all.isEmpty()) return@coroutineScope err("Couldn't read the market calendar from ${MarketData.NSE_NAME} right now. Try again in a moment.")
                MarketData.calendarJson("Market calendar", all, from, to, today, mine,
                    listOf(MarketData.NSE_NAME) + if (results.await().isNotEmpty()) listOf(MarketData.MC_NAME) else emptyList())
            } else {
                val symbol = MarketData.symbolOrNull(query)?.takeIf { !query.trim().contains(' ') }
                    ?: withContext(Dispatchers.IO) { runCatching { WebData.parseLookup(get(WebData.lookupUrl(query))) }.getOrNull() }?.substringBefore('.')
                    ?: return@coroutineScope err("Couldn't find \"$query\". Try the NSE symbol, e.g. INFY.")
                val events = async { MarketData.parseEvents(nse(MarketData.eventsUrl(symbol))) }
                val actions = async { MarketData.parseActions(nse(MarketData.actionsUrl(symbol))) }
                val all = events.await() + actions.await()
                if (all.isEmpty()) return@coroutineScope events(symbol)          // NSE unreachable or silent: fall back to the quote feed + search
                MarketData.calendarJson(symbol, all, today.minusDays(120), today.plusDays(180), today, mine)
            }
        }
    }.getOrElse { err(Net.friendly(it, MarketData.NSE_NAME)) }

    private fun gmpList(): List<GmpData.Gmp> = runCatching { GmpData.parseList(get(GmpData.LIST_URL, html = true), today()) }.getOrDefault(emptyList())

    /** Grey-market premium: every live IPO, or one issue with its day-by-day trend when [query] names it. */
    suspend fun gmp(query: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val list = GmpData.parseList(get(GmpData.LIST_URL, html = true), today())
            if (list.isEmpty()) return@runCatching err("Couldn't read grey-market premiums from ${GmpData.NAME} right now.")
            if (query.isBlank()) return@runCatching GmpData.listJson(list, today())
            val one = GmpData.find(list, query) ?: return@runCatching err("No IPO matching \"$query\" on ${GmpData.NAME}. Try /gmp to see the list.")
            val trend = if (one.path.isBlank()) emptyList() else runCatching { GmpData.parseTrend(get(GmpData.detailUrl(one.path), html = true), today()) }.getOrDefault(emptyList())
            GmpData.detailJson(one, trend, today())
        }.getOrElse { err(Net.friendly(it, GmpData.NAME)) }
    }

    /** Open and upcoming IPOs with their bid windows, plus recent equity listings. */
    suspend fun ipo(): String = runCatching {
        coroutineScope {
            val up = async { MarketData.parseIpos(nse(MarketData.ipoUpcomingUrl)) }
            val cur = async { MarketData.parseIpos(nse(MarketData.ipoCurrentUrl)) }
            val listed = async { MarketData.parseListings(nse(MarketData.listingsUrl)) }
            val gmp = async(Dispatchers.IO) { gmpList() }
            MarketData.ipoJson((cur.await() + up.await()).distinctBy { it.symbol.ifBlank { it.name } }, listed.await(), today(), gmp.await())
        }
    }.getOrElse { err(Net.friendly(it, MarketData.NSE_NAME)) }

    /** What terminal features see of the outside world. [mine] is used on the phone only. */
    fun fetch(mine: List<com.khayali.pulao.Stock.Holding>, seen: List<String> = emptyList()): com.khayali.pulao.ai.term.Fetch = object : com.khayali.pulao.ai.term.Fetch {
        override val today get() = today()
        override val holdings = mine
        override val watched = seen
        override val artifacts = com.khayali.pulao.ai.data.AndroidArtifactHub(app)
        override val knowledge get() = com.khayali.pulao.ai.data.KnowledgePack.bank(app)
        override val alerts get() = com.khayali.pulao.ai.data.Alerts.Store(app)
        override val webOn get() = com.khayali.pulao.Prefs(app).webOn
        override suspend fun names() = withContext(Dispatchers.IO) { com.khayali.pulao.ai.data.MarketPack.get(app).names() }
        override suspend fun sectors() = withContext(Dispatchers.IO) { com.khayali.pulao.ai.data.MarketPack.get(app).sectors() }
        override suspend fun market(days: Int) = withContext(Dispatchers.IO) { com.khayali.pulao.ai.data.MarketPack.get(app).panel(days) }
        override val profile get() = MemoryStore.get(app).let { m -> if (m.enabled) m.playbook.bullets.filter { it.section != Ace.Section.LESSON }.map { it.text } else emptyList() }
        override suspend fun spark(symbols: List<String>, range: String, interval: String) = withContext(Dispatchers.IO) {
            symbols.distinct().chunked(20).flatMap { batch ->
                runCatching { com.khayali.pulao.ai.term.Sparks.parse(this@WebClient.get(com.khayali.pulao.ai.term.Sparks.url(batch, range, interval))).entries }.getOrDefault(emptySet())
            }.associate { it.key to it.value }
        }
        override suspend fun nse(path: String) = this@WebClient.nse("https://${MarketData.NSE_HOST}/api/$path")
        override suspend fun chart(symbol: String, range: String, interval: String) = withContext(Dispatchers.IO) {
            runCatching { this@WebClient.get("https://${WebData.QUOTE_HOST}/v8/finance/chart/${WebData.encode(symbol)}?range=$range&interval=$interval&events=div%7Csplit") }.getOrDefault("")
        }
        override suspend fun get(url: String): String {
            require(com.khayali.pulao.ai.term.Hosts.allowed(url)) { "Blocked: ${com.khayali.pulao.ai.term.Hosts.host(url)} is not an allowed data source" }
            return withContext(Dispatchers.IO) { this@WebClient.get(url, html = !url.contains("/v1/") && !url.contains("/v8/") && !url.contains("api.")) }
        }
        override suspend fun search(query: String) = runCatching { results(WebData.sanitize(query)) }.getOrDefault(emptyList())
        override suspend fun symbol(query: String): String? {
            val q = query.trim().removePrefix("@")
            if (q.isBlank()) return null
            MarketData.symbolOrNull(q)?.takeIf { !q.contains(' ') }?.let { return it }
            return withContext(Dispatchers.IO) { runCatching { WebData.parseLookup(this@WebClient.get(WebData.lookupUrl(q))) }.getOrNull() }?.substringBefore('.')
        }
    }

    /** Runs one terminal feature; expected failures come back as {"error": …}. */
    suspend fun term(feature: com.khayali.pulao.ai.term.Feature, arg: String, mine: List<com.khayali.pulao.Stock.Holding>, seen: List<String> = emptyList()): String =
        runCatching { feature.run(arg, fetch(mine, seen)) }.getOrElse { err(Net.friendly(it, "the data source")) }

    private fun err(message: String) = gson.toJson(mapOf("error" to message))

    // ------------------------------------------------------------ quote feed

    private fun get(url: String, html: Boolean = false): String {
        var lastError: IOException? = null
        repeat(2) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("User-Agent", if (html) genericUserAgent("") else "Mozilla/5.0"); setRequestProperty("Accept", if (html) "text/html" else "application/json")
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 429 || code >= 500) throw IOException("The data source is busy (HTTP $code). Try again in a moment.")
                return body
            } catch (e: IOException) { lastError = e } finally { conn.disconnect() }
        }
        throw lastError ?: IOException("Couldn't reach the quote feed")
    }

    // ---------------------------------------------------------------- search

    /**
     * Loads one page from [host] off-screen and returns its HTML, or its visible text when [text] is set
     * (a JSON feed). Requests to any other host are refused. Cookies are accepted only where the site needs
     * them to answer at all ([cookies]) and are wiped as soon as the page has been read.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun page(url: String, host: String, text: Boolean, cookies: Boolean): String = withContext(Dispatchers.Main) {
        withTimeout(20_000) {
            suspendCancellableCoroutine { cont ->
                CookieManager.getInstance().setAcceptCookie(cookies)
                val wv = WebView(app)
                var finished = false
                fun finish(block: () -> Unit) {
                    if (finished) return
                    finished = true; block()
                    wv.post { wv.stopLoading(); wv.destroy(); if (cookies && --cookieUsers <= 0) { cookieUsers = 0; CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().setAcceptCookie(false) } }
                }
                if (cookies) cookieUsers++
                wv.settings.apply {
                    javaScriptEnabled = true              // only to read the page's own HTML back; scripts from other hosts can't load
                    blockNetworkImage = true; loadsImagesAutomatically = false
                    domStorageEnabled = false; databaseEnabled = false
                    allowFileAccess = false; allowContentAccess = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    setGeolocationEnabled(false)
                    userAgentString = genericUserAgent(WebSettings.getDefaultUserAgent(app))
                }
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                        if (req.url.scheme == "https" && req.url.host == host) null
                        else WebResourceResponse("text/plain", "utf-8", null)       // nothing but the results page
                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest) = req.url.host != host
                    override fun onPageFinished(view: WebView, u: String) {
                        view.evaluateJavascript(if (text) "document.body ? document.body.innerText : ''" else "document.documentElement.outerHTML") { v ->
                            val body = runCatching { gson.fromJson(v, String::class.java) }.getOrNull().orEmpty()
                            finish { if (text || body.length > 200) cont.resume(body) else cont.resumeWithException(IOException("Empty page")) }
                        }
                    }
                    override fun onReceivedError(view: WebView, req: WebResourceRequest, error: WebResourceError) {
                        if (req.isForMainFrame) finish { cont.resumeWithException(java.net.UnknownHostException(error.description?.toString())) }
                    }
                }
                cont.invokeOnCancellation { wv.post { runCatching { wv.stopLoading(); wv.destroy() } } }
                wv.loadUrl(url)
            }
        }
    }

    /** Pages currently loading with cookies allowed (main thread only). */
    private var cookieUsers = 0

    companion object {
        /** Chrome's reduced user agent: no device model, no Android build. */
        fun genericUserAgent(default: String): String {
            val chrome = Regex("""Chrome/(\d+)""").find(default)?.groupValues?.get(1) ?: "126"
            return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome.0.0.0 Mobile Safari/537.36"
        }

        @android.annotation.SuppressLint("StaticFieldLeak")       // holds the application context only
        @Volatile private var instance: WebClient? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: WebClient(ctx).also { instance = it } }
    }
}

/** Turns network exceptions into something a person can act on. */
object Net {
    fun friendly(t: Throwable, who: String): String = when (t) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> "No internet connection — couldn't reach $who."
        is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> "$who took too long to answer. Check your connection and try again."
        is javax.net.ssl.SSLException -> "Couldn't make a secure connection to $who. Try again."
        is IOException -> t.message?.takeIf { it.length in 8..140 && !it.contains("java.") && !it.contains("recvfrom") && !it.contains("ECONN") }
            ?: "The connection to $who dropped. Try again."
        else -> t.message?.takeIf { it.length in 8..160 } ?: "Couldn't reach $who."
    }
}

/**
 * Downloads one day of NSE's public bhavcopy straight from NSE's archive to this phone. This is the
 * only network code for market data, and it only ever contacts [com.khayali.pulao.ai.data.Bhavcopy.HOST]. Nothing is sent
 * except the request for the file.
 */
/** Lives here because WebClient.kt is one of only two files allowed to open network connections. */
object MarketFetcher {
    sealed class Day { data class Got(val bars: List<com.khayali.pulao.ai.data.Bhavcopy.Bar>) : Day(); data object NoTrading : Day() }

    private fun text(url: String, allowed: String): String? {
        require(URL(url).host == allowed) { "Blocked: only $allowed" }
        var last: java.io.IOException? = null
        repeat(3) { attempt ->
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("User-Agent", "Mozilla/5.0") }
            try {
                when (c.responseCode) { 404 -> return null; in 200..299 -> return c.inputStream.bufferedReader().readText(); else -> throw java.io.IOException("HTTP ${c.responseCode}") }
            } catch (e: java.io.IOException) { last = e; Thread.sleep(800L * (attempt + 1)) } finally { c.disconnect() }
        }
        throw last ?: java.io.IOException("Couldn't reach $allowed")
    }

    /** One day's official closes for every NSE index (empty when there was no file, e.g. a holiday). */
    fun indices(day: java.time.LocalDate): List<com.khayali.pulao.ai.data.Bhavcopy.IndexClose> =
        text(com.khayali.pulao.ai.data.Bhavcopy.indexUrl(day), com.khayali.pulao.ai.data.Bhavcopy.HOST)?.let { com.khayali.pulao.ai.data.Bhavcopy.parseIndices(it) }.orEmpty()

    /** Company names (symbol → name) for linking news to stocks. */
    fun names(): Map<String, String> =
        (text(com.khayali.pulao.ai.data.Sectors.equityListUrl(), com.khayali.pulao.ai.data.Bhavcopy.HOST)?.let { com.khayali.pulao.ai.data.Sectors.parseNames(it) }.orEmpty()).ifEmpty {
            text(com.khayali.pulao.ai.data.Sectors.namesUrl(), com.khayali.pulao.ai.data.Sectors.MEMBERS_HOST)?.let { com.khayali.pulao.ai.data.Sectors.parseNames(it) }.orEmpty() }

    /** A sector index's members, from NSE Indices' published list. */
    fun members(s: com.khayali.pulao.ai.data.Sectors.Sector): List<String> =
        text(com.khayali.pulao.ai.data.Sectors.membersUrl(s), com.khayali.pulao.ai.data.Sectors.MEMBERS_HOST)?.let { com.khayali.pulao.ai.data.Sectors.parseMembers(it) }.orEmpty()

    fun fetch(day: java.time.LocalDate): Day {
        val url = com.khayali.pulao.ai.data.Bhavcopy.url(day)
        require(URL(url).host == com.khayali.pulao.ai.data.Bhavcopy.HOST) { "Blocked: market data only comes from ${com.khayali.pulao.ai.data.Bhavcopy.HOST}" }
        var last: java.io.IOException? = null
        repeat(3) { attempt ->
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; setRequestProperty("User-Agent", "Mozilla/5.0") }
            try {
                when (c.responseCode) {
                    404 -> return Day.NoTrading
                    in 200..299 -> {
                        val csv = java.util.zip.ZipInputStream(c.inputStream.buffered()).use { z -> z.nextEntry ?: throw java.io.IOException("Empty archive"); z.readBytes().toString(Charsets.UTF_8) }
                        return Day.Got(com.khayali.pulao.ai.data.Bhavcopy.parse(csv))
                    }
                    else -> throw java.io.IOException("NSE archive answered HTTP ${c.responseCode}")
                }
            } catch (e: java.io.IOException) { last = e; Thread.sleep(800L * (attempt + 1)) } finally { c.disconnect() }
        }
        throw last ?: java.io.IOException("Couldn't reach NSE's archive")
    }
}

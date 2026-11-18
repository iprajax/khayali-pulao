package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.data.News
import java.time.Instant
import kotlinx.coroutines.async

/** The public news, regulator and filings feeds the phone reads (opt-in web). Each host is on [Hosts.ALLOWED]. */
object Feeds {
    data class Feed(val name: String, val url: String)
    const val ET = "economictimes.indiatimes.com"
    const val MINT = "www.livemint.com"
    const val BS = "www.business-standard.com"
    const val BL = "www.thehindubusinessline.com"
    const val CNBC = "www.cnbctv18.com"
    const val SEBI_HOST = "www.sebi.gov.in"
    val HOSTS = setOf(ET, MINT, BS, BL, CNBC, SEBI_HOST)

    /** Five market feeds, each checked live (all RSS 2.0, newest item under 1.5 h old when tested). */
    val NEWS = listOf(
        Feed("Economic Times", "https://$ET/markets/stocks/news/rssfeeds/2146843.cms"),
        Feed("Mint", "https://$MINT/rss/markets"),
        Feed("Business Standard", "https://$BS/rss/markets-106.rss"),
        Feed("BusinessLine", "https://$BL/markets/feeder/default.rss"),
        Feed("CNBC-TV18", "https://$CNBC/commonfeeds/v1/cne/rss/market.xml"),
    )
    /** SEBI's What's New list (its RSS is often empty; this page always has the latest 25). */
    val SEBI = Feed("SEBI", "https://$SEBI_HOST/sebiweb/home/HomeAction.do?doListingAll=yes")
    /** NSE's live announcements feed (no cookies needed), for when the announcements API is unavailable. */
    val NSE_FILINGS = Feed("NSE", "https://${com.khayali.pulao.ai.data.Bhavcopy.HOST}/content/RSS/Online_announcements.xml")
}

/** Shared news reading: fetch every feed the user's phone can reach, then fold, tag and link (all on the phone). */
internal object NewsRead {
    suspend fun stories(f: Fetch, max: Int = 40): List<News.Story> {
        val items = kotlinx.coroutines.coroutineScope { Feeds.NEWS.map { feed -> async { runCatching { News.parse(f.get(feed.url), feed.name) }.getOrDefault(emptyList()) } }.map { it.await() } }.flatten()
            .filterNot { com.khayali.pulao.ai.data.Filings.isCall(it.title) }          // the app never relays a buy/sell call
        val names = f.names()
        return News.stories(items, News.matcher(names), names.keys + f.watched.toSet(), max)
    }
    fun mover(s: News.Story, now: Instant, mine: Set<String>): Card.Row {
        val tone = when (s.tone) { 1 -> "▲ positive"; -1 -> "▼ negative"; else -> "neutral" }
        val when_ = News.ago(s.item.published, now)
        val who = (listOf(s.item.source) + s.also).joinToString(", ")
        val tick = s.tickers.joinToString(" ") { (if (it in mine) "★" else "") + it }
        return Card.Row(s.item.title, listOf(tone, when_, who, tick).filter { it.isNotBlank() }.joinToString(" · "), "", when (s.tone) { 1 -> "up"; -1 -> "down"; else -> "" },
            action = s.tickers.firstOrNull()?.let { "/news $it" } ?: "")
    }
}

/** /news [SYMBOL] — market news from several outlets, one story per event, tone-tagged and linked to the stocks it names; or one stock's stories. */
object NewsFeature : Feature {
    override val id = "news"; override val usage = "/news INFY"; override val group = Group.MARKET
    override val help = "Market news from several outlets: one story per event, tone-tagged, linked to the stocks it names"
    override val method = "Headlines come from public RSS feeds of Indian business news outlets, fetched by this phone (only the feed address is requested). " +
        "On the phone they're folded into one story per event (headlines sharing most words), tagged positive/negative/neutral with a finance word list, " +
        "and linked to stocks by company name, common short forms (RIL, SBI, L&T…) and exact NSE symbols. For one stock, the stories that name it are shown, " +
        "and the search engine fills in when the feeds have nothing."
    override val triggers = listOf(Regex("""(?i)^((latest |today'?s |market |business )?(news|headlines)( today| now)?|what('s| is) in the news( today)?|any (market )?news)\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val sym = arg.trim().removePrefix("@").uppercase().takeIf { it.isNotBlank() }?.let { f.symbol(it) ?: it }
        val all = NewsRead.stories(f)
        val now = Instant.now()
        val mine = f.holdings.map { it.name.uppercase() }.toSet()
        if (sym == null) {
            if (all.isEmpty()) return Card.error("Couldn't reach the news feeds just now.", "/news")
            val pos = all.count { it.tone > 0 }; val neg = all.count { it.tone < 0 }
            val named = all.flatMap { it.tickers }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
            val yours = all.filter { s -> s.tickers.any { it in mine } }
            val c = Card("Market news · ${all.size} stories · ${Feeds.NEWS.size} outlets", "blue")
                .headline(when { pos > neg * 1.5 -> "Mostly upbeat"; neg > pos * 1.5 -> "Mostly downbeat"; else -> "Mixed" } + " headlines", "$pos positive · $neg negative · ${all.size - pos - neg} neutral, from ${Feeds.NEWS.joinToString(", ") { it.name }}")
                .breadth("Tone of today's headlines", pos, neg, all.size - pos - neg)
            if (named.isNotEmpty()) c.bars("Most mentioned stocks", named.map { Card.Bar(it.key, it.value.toDouble(), "${it.value} stories") }, "")
            if (yours.isNotEmpty()) c.rows("About your holdings", yours.take(5).map { NewsRead.mover(it, now, mine) })
            c.rows("Latest", all.take(12).map { NewsRead.mover(it, now, mine) })
            return c.note("Tone is a word count, not a judgement — read the story. Tap a stock for its news, trend and events.")
                .sources(*Feeds.NEWS.map { it.name }.toTypedArray()).put("links", all.take(12).map { it.item.link })
                .pills(*(named.take(2).map { "/news ${it.key}" } + listOf("/pulse", "/sebi")).toTypedArray())
                .json("${all.size} stories: $pos positive, $neg negative. Top: " + all.take(3).joinToString("; ") { it.item.title } + ". Sources: " + Feeds.NEWS.joinToString(", ") { it.name } + ".")
        }
        val about = all.filter { sym in it.tickers }
        if (about.isEmpty()) {
            // nothing in the feeds: fall back to the search engine (topic words only)
            val res = runCatching { f.search("$sym share latest news") }.getOrDefault(emptyList())
            if (res.isEmpty()) return Card.error("No recent stories name $sym.", "/news", "/announcements $sym")
            return Card("$sym · news from the web", "blue").headline("${res.size} results", "No feed story names $sym today, so these come from a web search.")
                .rows(null, res.take(8).map { Card.Row(it.title, it.snippet.take(140)) })
                .sources("DuckDuckGo").put("links", res.take(8).map { it.url }).pills("/announcements $sym", "/events $sym", "/health $sym")
                .json("Web results for $sym: " + res.take(3).joinToString("; ") { it.title } + ".")
        }
        val tone = about.sumOf { it.tone }
        return Card("$sym · news · ${about.size} stories", if (tone >= 0) "green" else "saffron")
            .headline(when { tone > 0 -> "Upbeat coverage"; tone < 0 -> "Downbeat coverage"; else -> "Mixed coverage" }, "${about.count { it.tone > 0 }} positive · ${about.count { it.tone < 0 }} negative")
            .rows(null, about.take(10).map { NewsRead.mover(it, now, mine) })
            .note("Stories that name $sym in today's feeds. Tone is a word count, not a judgement.")
            .sources(*about.map { it.item.source }.distinct().toTypedArray()).put("links", about.take(10).map { it.item.link })
            .pills("/announcements $sym", "/events $sym", "/health $sym", "/chart $sym 1m")
            .json("$sym in the news: " + about.take(3).joinToString("; ") { it.item.title } + ".")
    }
}

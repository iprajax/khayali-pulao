package com.khayali.pulao.ai.term

/**
 * The strategy playbook: what each rule does, why it has worked, when it hasn't, and how to read it.
 * Written for learning. Price-based strategies run and backtest on the user's own data; the fundamental
 * families (quality, value, GARP, dividends, ownership…) carry the thresholds most used in public stock
 * screens and say what extra data they need.
 * Search is keyword-based (BM25) on the phone; the Mac research pipeline indexes the same text with
 * EmbeddingGemma 2 for search by meaning.
 */
object Playbook {
    /** [rules]: typical thresholds; [needs]: what data it needs beyond daily prices ("" = runs on the user's own NSE data). */
    data class Entry(val id: String, val name: String, val idea: String, val why: String, val struggles: String, val read: String, val tags: List<String>,
                     val rules: String = "", val needs: String = "")
    val Entry.runsHere get() = needs.isEmpty()

    val ENTRIES = listOf(
        Entry("momentum", "12-1 month momentum",
            "Buy what has risen most over the past year, skipping the latest month, and rebalance monthly.",
            "Trends persist: investors under-react to news, and institutions add to winners gradually. It is one of the most studied effects in markets worldwide, including India.",
            "Sharp reversals, when last year's losers suddenly rally (often after a crash bottoms). Turnover is high, so costs and short-term tax matter.",
            "A momentum list is a set of stocks that have already risen a lot. It tells you nothing about any single company's value.",
            listOf("trend", "winners", "relative strength", "rising", "breakout")),
        Entry("highs", "Near 52-week high",
            "Hold stocks trading close to their 52-week high, above their 200-day average.",
            "Investors anchor to the old high and hesitate to buy near it, so good news is priced in slowly. Stocks near highs have tended to keep outperforming.",
            "Choppy, range-bound markets, where breakouts fail and reverse.",
            "Being near a high is not the same as being expensive; it describes price, not valuation.",
            listOf("52-week", "breakout", "high", "strength", "new highs")),
        Entry("lowvol", "Low volatility",
            "Hold the steadiest stocks: the lowest daily swings over the last three months.",
            "Calmer stocks have historically delivered returns close to the market with much smaller falls, partly because many investors chase exciting stocks instead.",
            "Strong bull markets led by risky stocks; low-volatility portfolios lag when everything rallies.",
            "Low volatility means smaller day-to-day moves in the past, not a guarantee of safety in the future.",
            listOf("defensive", "safe", "stable", "low risk", "calm", "drawdown")),
        Entry("trend", "Trend following",
            "Hold stocks in confirmed uptrends — 50-day average above 200-day average and price above both — ranked by 3-month return.",
            "Following trends keeps you out of long declines and in long advances; the classic 'golden cross' idea.",
            "Whipsaws: sideways markets trigger repeated entries and exits at a loss.",
            "A stock leaving the list usually means its trend broke, not that the company changed.",
            listOf("golden cross", "moving average", "uptrend", "trend", "200 day", "50 day")),
        Entry("reversal", "Pullback in an uptrend",
            "Buy stocks in long-term uptrends (above their 200-day average) that fell the most over the last month.",
            "Short-term overreaction: sharp dips in otherwise healthy trends have tended to recover.",
            "Real bad news — a dip can be the start of a new downtrend. It is the riskiest of these rules.",
            "A big one-month fall is the entry condition, so the list is full of stocks with recent bad news. Check why each fell.",
            listOf("dip", "mean reversion", "oversold", "pullback", "contrarian", "buy the dip")),
        Entry("breakout", "Breakout on volume",
            "Hold stocks closing at a 50-day high on at least twice their normal volume.",
            "Heavy volume on a new high suggests large buyers are stepping in, and such moves have tended to carry on for a while.",
            "False breakouts in weak markets, when the price slips back under the old high within days.",
            "A breakout list changes a lot month to month; each name has just made a big, loud move.",
            listOf("breakout", "volume", "new high", "50 day high", "surge")),
        Entry("mom6", "6-month momentum",
            "Hold the stocks that rose most over the last six months, skipping the latest week.",
            "A faster version of momentum: it catches new trends sooner than the 12-month rule.",
            "Choppy markets, where six-month leaders change often and the rule buys high and sells low.",
            "It reacts faster than 12-1 momentum, so it also turns over more.",
            listOf("momentum", "6 month", "medium term", "winners")),
        Entry("sharpe", "Steady compounders",
            "Hold the stocks with the best one-year return for the volatility they took to get there.",
            "Smooth risers attract steady buying; rewarding return per unit of risk prefers them over erratic jumps.",
            "Sharp market rotations, when last year's steady leaders fall out of favour together.",
            "High return for low volatility in the past says nothing about the next year by itself.",
            listOf("quality", "steady", "compounder", "risk adjusted", "sharpe", "consistent")),
        Entry("lowbeta", "Low beta",
            "Hold the stocks that moved least with the market over the last six months.",
            "Low-beta stocks have historically earned close to the market with much smaller swings: the 'betting against beta' effect.",
            "Strong rallies: when the whole market jumps, low-beta stocks lag.",
            "Beta is measured against the app's equal-weight market of liquid stocks, not an official index.",
            listOf("beta", "market sensitivity", "less risky")),
        Entry("accumulation", "Accumulation",
            "Hold stocks that are up over three months and whose trading volume has been rising.",
            "Rising price on rising volume is a classic sign of investors building positions.",
            "Volume can rise on bad news too, and crowded names reverse hard.",
            "It's a price-and-volume pattern only; check the news behind each name.",
            listOf("accumulation", "institutional buying", "demand", "on balance volume")),
        Entry("rsi", "RSI pullback",
            "Hold stocks in long uptrends (above their 200-day average) whose 14-day RSI is lowest.",
            "Short dips inside strong trends have tended to recover; RSI measures how one-sided recent days were.",
            "Trend breaks: a low RSI can be the first sign of a new downtrend.",
            "RSI here is computed from daily closes on your own data.",
            listOf("rsi", "relative strength index", "short term weakness")),
        Entry("nolottery", "No lottery tickets",
            "Avoid stocks with huge single-day jumps; hold those whose biggest daily move last month was smallest.",
            "Stocks with lottery-like jumps tend to be overpriced by investors chasing big wins, and have historically done worse afterwards (the 'MAX' effect).",
            "Speculative rallies, when the jumpiest stocks lead.",
            "It favours calm stocks, so it often overlaps with low volatility.",
            listOf("lottery", "max effect", "speculative", "jumps")),
        Entry("largecap", "Largest traded",
            "Hold the 30 most traded stocks, equally weighted: roughly the biggest companies.",
            "A simple core holding: big, liquid companies, like an equal-weight index.",
            "Small-cap rallies, when smaller companies lead.",
            "Traded value is a proxy for size; it is not the official Nifty 50.",
            listOf("large cap", "index", "blue chip", "nifty", "core", "biggest companies")),
        Entry("weekrev", "Weekly reversal",
            "Hold the stocks that fell the most over the last week, expecting a short-term bounce.",
            "Very short-term moves often overshoot and partly reverse as liquidity providers step in.",
            "Falls caused by real news keep falling; monthly rebalancing also holds the picks longer than the effect usually lasts.",
            "Treat it as a study of short-term reversal, not as a trading system.",
            listOf("bounce", "losers", "weekly", "short term")),
        Entry("quality", "Quality compounders", "Companies that earn high returns on the money they use, carry little debt and keep growing profits.", "High returns on capital that persist mean a business can reinvest at good rates; low debt keeps it alive through bad years.", "Expensive markets: quality stocks often trade at high valuations and can de-rate sharply.", "A quality screen finds good businesses, not good prices; valuation is a separate question.",
            listOf("quality", "roce", "roe", "return on capital", "compounder", "moat"),
            rules = "ROCE above 20%, ROE above 15-17%, debt to equity below 1, 3-5 year profit growth above 15%", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("garp", "Growth at a reasonable price", "Growing companies whose price is still modest for that growth.", "Pays for growth without paying any price for it; the PEG ratio links the P/E to growth.", "When growth stalls, a 'reasonable' P/E turns out to be expensive.", "A low PEG relies on growth continuing; check whether past growth is repeatable.",
            listOf("garp", "peg", "growth", "valuation", "reasonable price"),
            rules = "PEG below 1, P/E below 20-25, 3-year profit growth above 15%", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("magicformula", "Magic formula", "Rank companies by return on capital and earnings yield together; hold the best combined ranks for a year.", "Buys good businesses (high return on capital) at cheap prices (high earnings yield) in one simple rule, popularised by Joel Greenblatt.", "Long stretches of underperformance that test patience; it usually excludes financials.", "It is mechanical by design; the edge comes from sticking with it for years.",
            listOf("magic formula", "greenblatt", "earnings yield", "return on capital", "value"),
            rules = "return on capital above 25%, earnings yield above 15%, positive book value", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("piotroski", "Piotroski F-score", "A 0-9 checklist of improving profitability, leverage and efficiency; favour high scores.", "Among cheap stocks, those with improving fundamentals have historically done much better than the rest.", "Expensive growth stocks rarely score high; it is built for value stocks.", "A high score is about the trend in the accounts, not the business's size or quality.",
            listOf("piotroski", "f score", "fundamentals", "checklist", "improving"),
            rules = "F-score of 7 or more (screens commonly use above 5-7)", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("coffeecan", "Coffee can investing", "Companies that grew sales and earned high returns on capital every year for a decade, bought and left alone.", "Consistency over ten years is rare; such businesses tend to keep compounding, and low turnover keeps costs down.", "Very few companies qualify, and they are often highly valued.", "The test is consistency every single year, not the average.",
            listOf("coffee can", "consistent", "long term", "buy and hold", "decade"),
            rules = "sales growth above 10% and ROCE above 15% in each of the last 10 years", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("debtfree", "Debt-free businesses", "Companies with no or almost no borrowing and comfortable interest cover.", "No debt means no forced sellers, no lender pressure and more room to invest in downturns.", "Capital-heavy sectors that use debt sensibly are excluded; zero debt can also mean idle cash.", "Check the cash too: debt-free with large cash is different from debt-free and cash-poor.",
            listOf("debt free", "no debt", "balance sheet", "leverage", "interest coverage"),
            rules = "debt to equity near 0 (screens use below 0.1-1), interest coverage above 3", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("dividend", "Dividend yield", "Companies paying a steady, meaningful dividend from real profits.", "Dividends are cash in hand and a sign of discipline; high-yield stocks have historically been less volatile.", "Yield traps: a high yield after a price fall can precede a dividend cut.", "Look at payout ratio and cash flow, not just yield.",
            listOf("dividend", "yield", "income", "payout", "cash returns"),
            rules = "dividend yield above 2-3%, payout below 70-80%, positive cash from operations", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("promoter", "Promoter conviction", "Companies where the founders own a large stake, have pledged none of it, and are adding more.", "Owners with skin in the game tend to run businesses for the long term; pledged shares can force sales in a crash.", "High promoter holding also means low free float, so prices can swing more.", "Changes matter: a rising stake is a stronger signal than a high one.",
            listOf("promoter", "pledge", "insider", "founder", "ownership", "skin in the game"),
            rules = "promoter holding above 50%, pledged percentage 0, promoter stake rising", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("fiibuying", "Institutional buying", "Companies where foreign and domestic institutions raised their stakes over the last quarter.", "Institutions research deeply and buy over months, so rising stakes often continue.", "Institutions also crowd into the same names and leave together.", "Holding data is quarterly, so the signal is always a few weeks old.",
            listOf("fii", "dii", "institutional", "foreign investors", "mutual funds buying"),
            rules = "change in FII holding above 1 percentage point over the quarter, or FII plus DII stake rising", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("graham", "Graham value", "Benjamin Graham's defensive-investor test: modest P/E and P/B together, strong balance sheet.", "Buying with a margin of safety limits losses when things go wrong.", "Long periods when cheap stocks stay cheap; it misses most high-quality growth companies.", "Cheap on these numbers can mean a business in decline; check why it is cheap.",
            listOf("graham", "value", "margin of safety", "price to book", "cheap", "defensive investor"),
            rules = "P/E times P/B below 22.5, P/E below 15, current ratio above 2", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
        Entry("turnaround", "Earnings turnaround", "Companies whose recent quarterly profits jumped against the same quarter last year after weak years.", "Markets are slow to believe a recovery, so prices can keep catching up for several quarters.", "One-off gains look like turnarounds; a single good quarter is weak evidence.", "Check whether the improvement comes from operations or from one-time items.",
            listOf("turnaround", "recovery", "quarterly results", "earnings surprise", "profit growth"),
            rules = "YoY quarterly profit growth above 15-25%, positive latest-quarter profit", needs = "company financials (results, balance sheet, shareholding), which NSE's daily price file doesn't include"),
    )

    fun get(id: String) = ENTRIES.firstOrNull { it.id == id.lowercase() }

    // ---- BM25 over the playbook text (pure; small enough to index on every call)
    private fun words(s: String) = Regex("""[a-z0-9]+""").findAll(s.lowercase()).map { it.value.removeSuffix("s") }.filter { it.length > 1 }.toList()
    private fun text(e: Entry) = listOf(e.name, e.name, e.idea, e.why, e.struggles, e.read, e.rules, e.tags.joinToString(" "), e.tags.joinToString(" ")).joinToString(" ")

    fun search(query: String, k: Int = 3): List<Pair<Entry, Double>> {
        val q = words(query).toSet(); if (q.isEmpty()) return emptyList()
        val docs = ENTRIES.map { it to words(text(it)) }
        val avg = docs.map { it.second.size }.average(); val n = docs.size.toDouble()
        val df = q.associateWith { t -> docs.count { t in it.second }.toDouble() }
        return docs.map { (e, w) ->
            val tf = w.groupingBy { it }.eachCount()
            e to q.sumOf { t -> val f = (tf[t] ?: 0).toDouble(); if (f == 0.0) 0.0 else Math.log(1 + (n - df[t]!! + .5) / (df[t]!! + .5)) * f * 2.2 / (f + 1.2 * (.25 + .75 * w.size / avg)) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(k)
    }
}

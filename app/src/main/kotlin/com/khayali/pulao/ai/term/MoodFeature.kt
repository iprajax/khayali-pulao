package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.data.Mood

/** /mood — the market mood index (fear ↔ greed) from the user's own NSE data: gauge, its six parts, and 60 days of history. */
object MoodFeature : Feature {
    override val id = "mood"; override val usage = "/mood"; override val group = Group.MARKET; override val needsWeb = false
    override val requires = "market"
    override val help = "Market mood index: fear to greed, 0–100, from breadth, trend, momentum, highs, calm and advancers"
    override val method = "Six parts, each scored 0–100 and averaged, all over liquid NSE stocks (median daily value ≥ ₹5 crore): breadth (share above their 50-day average); " +
        "trend (equal-weight market vs its 125-day average, ±8% maps to 0–100); momentum (equal-weight 1-month return, ±8%); highs vs lows (new 52-week highs ÷ highs + lows); " +
        "calm (100 minus where today's 20-day volatility sits in the past year); advancers (share of moving stocks that rose, 5-day average). Zones: under 25 extreme fear, 25–45 fear, " +
        "45–55 neutral, 55–75 greed, over 75 extreme greed. Computed on the phone from NSE's daily files."
    override val triggers = listOf(Regex("""(?i)^((what('s| is) the )?(market )?(mood|sentiment)( index| today| now)?|fear (and|&|or) greed( index)?|is the market (fearful|greedy|euphoric|scared))\??$"""))

    override suspend fun run(arg: String, f: Fetch): String {
        val p = f.market(1300) ?: return Card.error("The mood index uses your NSE history. Type /data 1y to download it.", "/data 1y", "/data 3y")
        val r = Mood.compute(p)
        if (r.isEmpty()) return Card.error("The mood index needs at least a year of history.", "/data 3y")
        val now = r.last(); val wk = r.getOrNull(r.size - 6); val mo = r.getOrNull(r.size - 22)
        fun d(x: Double?) = x?.let { "%+.0f".format(now.score - it) } ?: "—"
        return Card("Market mood · ${now.date} · your own NSE data", if (now.score >= 50) "green" else "saffron")
            .gauge("Fear ↔ greed", now.score, now.zone, "${d(wk?.score)} pts in a week · ${d(mo?.score)} in a month")
            .bars("What's driving it · each 0–100 (50 = neutral)", now.parts.map { Card.Bar(it.name, it.score - 50, "${it.score.toInt()} · ${it.detail}") }, "pts")
            .viz("multiline", 180, mapOf("t" to r.map { it.date.toString() }, "yLabel" to "Mood (0–100)", "xLabel" to "Date",
                "series" to listOf(mapOf("name" to "Mood", "color" to "violet", "v" to r.map { it.score }), mapOf("name" to "Neutral", "color" to "muted", "v" to r.map { 50.0 }))),
                "The mood over the last ${r.size} trading days.")
            .note("Extreme fear has often come near lows and extreme greed near highs, but the index describes the market's mood — it doesn't time it. Not a recommendation.")
            .sources("NSE (your copy)").put("asOf", now.date.toString()).put("span", "${p.dates.first()} to ${p.dates.last()} · ${p.days} trading days")
            .pills("/regime", "/sectorboard 1w", "/anomalies", "/brief")
            .json("Market mood ${now.score.toInt()} of 100 — ${now.zone.lowercase()} (${d(wk?.score)} points in a week). Strongest part: ${now.parts.maxBy { it.score }.name.lowercase()}; weakest: ${now.parts.minBy { it.score }.name.lowercase()}. Source: NSE daily data on this phone.")
    }
}

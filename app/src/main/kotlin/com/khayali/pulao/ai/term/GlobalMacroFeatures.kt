package com.khayali.pulao.ai.term

import com.khayali.pulao.ai.term.J.d
import com.khayali.pulao.ai.term.J.o
import com.khayali.pulao.ai.term.J.s

/** World Bank open data (no key): one indicator for India by year. */
object GlobalWorldBank {
    const val HOST = "api.worldbank.org"
    const val SOURCE = "World Bank"
    fun url(indicator: String) = "https://$HOST/v2/country/IN/indicator/$indicator?format=json&per_page=30"

    class Yearly(val name: String, val updated: String, val points: List<Pair<Int, Double>>)

    /** Reply is [ {paging…}, [ {date:"2025", value: 2.39, indicator:{value: name}}, … ] ]; years without a value are dropped. Oldest first. */
    fun parse(json: String): Yearly? {
        val root = J.parse(json)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (root.size() < 2 || !root[1].isJsonArray) return null
        val rows = root[1].asJsonArray.filter { it.isJsonObject }.map { it.asJsonObject }
        val pts = rows.mapNotNull { r -> val y = r.s("date").toIntOrNull() ?: return@mapNotNull null; val v = r.d("value") ?: return@mapNotNull null; y to v }.sortedBy { it.first }
        if (pts.isEmpty()) return null
        return Yearly(rows.firstNotNullOfOrNull { it.o("indicator")?.s("value")?.takeIf { n -> n.isNotBlank() } }.orEmpty(), root[0].takeIf { it.isJsonObject }?.asJsonObject?.s("lastupdated").orEmpty(), pts)
    }

    /** The shared shape of both yearly cards: bars for the last 15 years, the latest value and the average of the last 10. */
    internal fun card(json: String, kicker: String, yLabel: String, what: String, verb: (Double) -> String, note: String, code: String, pills: Array<String>): String? {
        val data = parse(json) ?: return null
        val pts = data.points; val (year, latest) = pts.last(); val prev = pts.getOrNull(pts.size - 2)
        val ten = pts.takeLast(10); val avg = ten.map { it.second }.average(); val span = "${ten.first().first}–${ten.last().first}"
        val avgName = "${ten.size}-year average"
        val shown = pts.takeLast(15); val hi = shown.maxBy { it.second }; val lo = shown.minBy { it.second }
        fun p(v: Double) = GlobalKit.level(v)
        return Card("$kicker · yearly · through $year", "blue")
            .headline("${p(latest)} in $year", "$avgName ${p(avg)} ($span)" + (prev?.let { " · ${it.first}: ${p(it.second)}" } ?: ""))
            .viz("yearbars", 230, mapOf("unit" to "%", "yLabel" to yLabel, "xLabel" to "Year", "avg" to avg, "avgLabel" to "$avgName ${p(avg)}",
                "items" to shown.map { mapOf("label" to it.first.toString(), "value" to it.second) }), "$yLabel by year, ${shown.first().first}–$year. Dashed line: $avgName. Tap a bar for its value.")
            .stats("Latest ($year)" to p(latest), avgName to p(avg), "Highest since ${shown.first().first}" to "${p(hi.second)} (${hi.first})", "Lowest since ${shown.first().first}" to "${p(lo.second)} (${lo.first})")
            .note(note + (if (data.updated.isNotBlank()) " World Bank data last updated ${data.updated}." else ""))
            .sources(SOURCE).pills(*pills)
            .put("year", year).put("latest_pct", Math.round(latest * 10) / 10.0)
            .json("$what ${verb(latest)} in $year" + (prev?.let { " (${p(it.second)} in ${it.first})" } ?: "") + "; the $avgName for $span is ${p(avg)}. Source: $SOURCE ($code).")
    }
}

/** /inflation — India's consumer-price inflation by year, the latest value and the 10-year average. */
object GlobalInflationFeature : Feature {
    override val id = "inflation"
    override val usage = "/inflation"
    override val help = "India CPI inflation by year, the latest value and the 10-year average"
    override val group = Group.MACRO
    override val triggers = listOf(Regex("""(?i)\b(cpi|inflation (rate|in india|by year|history)|india'?s? inflation)\b"""))

    const val INDICATOR = "FP.CPI.TOTL.ZG"

    override suspend fun run(arg: String, f: Fetch): String = build(runCatching { f.get(GlobalWorldBank.url(INDICATOR)) }.getOrDefault(""))

    /** Pure: World Bank indicator JSON → card. */
    fun build(json: String): String = GlobalWorldBank.card(json, "India · CPI inflation", "CPI inflation (% a year)", "India's CPI inflation", { "was " + GlobalKit.level(it) },
        "Annual average change in consumer prices as published by the World Bank. It arrives with a lag: the monthly figures from India's statistics ministry are more recent and are not shown here. Your own cost of living can differ from the national basket.",
        INDICATOR, arrayOf("/gdp", "/gold", "/fx", "/yields")) ?: Card.error("Couldn't read India's inflation series from the World Bank just now. Try again in a minute.")
}

/** /gdp — India's real GDP growth by year. */
object GlobalGdpFeature : Feature {
    override val id = "gdp"
    override val usage = "/gdp"
    override val help = "India real GDP growth by year, the latest value and the 10-year average"
    override val group = Group.MACRO
    override val triggers = listOf(Regex("""(?i)\bgdp\b"""), Regex("""(?i)\beconomic growth\b"""))

    const val INDICATOR = "NY.GDP.MKTP.KD.ZG"

    override suspend fun run(arg: String, f: Fetch): String = build(runCatching { f.get(GlobalWorldBank.url(INDICATOR)) }.getOrDefault(""))

    /** Pure: World Bank indicator JSON → card. */
    fun build(json: String): String = GlobalWorldBank.card(json, "India · real GDP growth", "Real GDP growth (% a year)", "India's real GDP",
        { if (it < 0) "shrank " + GlobalKit.level(-it) else "grew " + GlobalKit.level(it) },
        "Growth in GDP at constant prices as published by the World Bank, which reports India by fiscal year (April–March) under the year in which it starts. Figures are revised over time, and past growth does not tell you what the stock market or next year will do.",
        INDICATOR, arrayOf("/inflation", "/global", "/fx", "/yields")) ?: Card.error("Couldn't read India's GDP growth series from the World Bank just now. Try again in a minute.")
}

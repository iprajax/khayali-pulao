package com.khayali.pulao

import com.khayali.pulao.ai.data.Bhavcopy
import com.khayali.pulao.ai.data.Market
import com.khayali.pulao.ai.data.Panel
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Runs the whole engine on a real local cache of NSE bhavcopy (made by tools/market/fetch.py).
 * Skipped unless KP_BHAV_DIR is set:  KP_BHAV_DIR=~/.khayali/bhav ./gradlew :app:testDebugUnitTest --tests '*LiveMarketTest*' -i
 */
class LiveMarketTest {
    @Test fun engineOnRealData() {
        val dir = System.getenv("KP_BHAV_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val t0 = System.currentTimeMillis()
        val days = dir!!.listFiles { f -> f.name.endsWith(".csv.zip") }!!.sortedBy { it.name }.map { f ->
            val text = ZipInputStream(f.inputStream()).use { z -> z.nextEntry; z.readBytes().toString(Charsets.UTF_8) }
            LocalDate.parse(f.name.take(8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE) to Bhavcopy.parse(text)
        }
        val tParse = System.currentTimeMillis()
        val p = Panel.build(days)
        val tBuild = System.currentTimeMillis()
        println("LIVE ${p.days} days ${p.dates.first()}..${p.dates.last()}, ${p.symbols.size} symbols; parse ${tParse - t0} ms, build ${tBuild - tParse} ms")
        val r = Market.regime(p)
        println("LIVE regime ${r.label}: ${r.stocks} liquid, above50 ${"%.1f".format(r.above50)}%, above200 ${"%.1f".format(r.above200)}%, adv ${r.advances}/dec ${r.declines}, highs ${r.newHighs}/lows ${r.newLows}, " +
            "1d ${"%.2f".format(r.market1d * 100)}%, 1w ${"%.2f".format(r.market1w * 100)}%, 1m ${"%.2f".format(r.market1m * 100)}%, vol ${r.volNow?.let { "%.1f".format(it * 100) }}% (pct ${r.volPercentile?.let { "%.0f".format(it) }})")
        val ta = System.currentTimeMillis()
        Market.anomalies(p).forEach { println("LIVE anomaly ${it.symbol} · ${it.kind} · ${it.detail} · move ${"%.2f".format(it.move * 100)}%") }
        println("LIVE anomalies ${System.currentTimeMillis() - ta} ms")
        for (s in listOf("INFY", "TCS", "ITC", "HDFCBANK", "RELIANCE")) Market.health(p, s)?.let { h ->
            println("LIVE health $s 1m ${h.r1m?.let { "%.1f".format(it * 100) }}% 1y ${h.r1y?.let { "%.1f".format(it * 100) }}% vs200 ${h.vs200?.let { "%.1f".format(it * 100) }}% fromHigh ${h.fromHigh?.let { "%.1f".format(it * 100) }}% flags ${h.flags}") }
        for (st in Market.STRATEGIES) {
            val tb = System.currentTimeMillis()
            val b = Market.backtest(p, st, Market.commonWarmup(p) ?: st.need) ?: run { println("LIVE ${st.id}: not enough history"); null } ?: continue
            println("LIVE backtest ${st.id} ${b.from}..${b.to} ${b.months}m: CAGR ${"%.1f".format(b.cagr * 100)}% vs market ${"%.1f".format(b.benchCagr * 100)}%, vol ${"%.1f".format(b.vol * 100)}%, maxDD ${"%.1f".format(b.maxDrawdown * 100)}%, beat ${"%.0f".format(b.beatMonths)}% of months; now: ${b.current.take(6)} (${System.currentTimeMillis() - tb} ms)")
        }
        val p2 = Panel.build(days); val tAll = System.currentTimeMillis(); val all = Market.backtestAll(p2)
        println("LIVE backtestAll ${all.size} strategies in ${System.currentTimeMillis() - tAll} ms (fresh panel, parallel)")
        val rt = Runtime.getRuntime(); println("LIVE heap used ${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB")
    }
}

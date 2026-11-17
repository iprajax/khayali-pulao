package com.khayali.pulao

import com.khayali.pulao.ai.data.Artifacts
import com.khayali.pulao.ai.data.Artifacts.Ev
import com.khayali.pulao.ai.data.Artifacts.State
import com.khayali.pulao.ai.data.Bhavcopy
import com.khayali.pulao.ai.data.Market
import com.khayali.pulao.ai.data.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.abs

class MarketEngineTest {
    // ------------------------------------------------------------- bhavcopy

    @Test fun addressesForBothFormats() {
        assertEquals("https://nsearchives.nseindia.com/content/historical/EQUITIES/2016/JAN/cm04JAN2016bhav.csv.zip", Bhavcopy.url(LocalDate.of(2016, 1, 4)))
        assertEquals("https://nsearchives.nseindia.com/content/historical/EQUITIES/2024/JUL/cm05JUL2024bhav.csv.zip", Bhavcopy.url(LocalDate.of(2024, 7, 5)))
        assertEquals("https://nsearchives.nseindia.com/content/cm/BhavCopy_NSE_CM_0_0_0_20240708_F_0000.csv.zip", Bhavcopy.url(LocalDate.of(2024, 7, 8)))
        assertEquals("https://nsearchives.nseindia.com/content/indices/ind_close_all_16112026.csv", Bhavcopy.indexUrl(LocalDate.of(2026, 11, 16)))
        assertEquals(5, Bhavcopy.tradingDayCandidates(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 22)).size)      // Mon–Fri
    }

    @Test fun readsTheNewFormat() {
        val bars = Bhavcopy.parse(File("src/test/resources/data/udiff_sample.csv").readText())
        val infy = bars.first { it.symbol == "INFY" }
        assertEquals("EQ", infy.series); assertEquals(1145.10, infy.close, 1e-9); assertEquals(1141.50, infy.prev, 1e-9)
        assertEquals(4852092L, infy.volume); assertEquals(74241L, infy.trades); assertEquals("INE009A01021", infy.isin)
        assertTrue(bars.none { it.symbol == "SGBJUN28" })              // gold bonds are not equity
        assertTrue(bars.none { it.symbol == "GOLDBEES" })               // nor are ETFs
        assertTrue(Bhavcopy.isCompany("INFY", "INE009A01021")); assertFalse(Bhavcopy.isCompany("LIQUIDBEES", "INF732E01037"))
        assertFalse(Bhavcopy.isCompany("NIFTYBEES", "")); assertTrue(Bhavcopy.isCompany("RELIANCE", ""))
    }

    @Test fun readsTheOldFormat() {
        val bars = Bhavcopy.parse(File("src/test/resources/data/old_sample.csv").readText())
        assertTrue(bars.any { it.symbol == "INFY" && it.close > 0 && it.prev > 0 && it.value > 0 })
        assertTrue(bars.none { it.symbol == "SOMEBOND" })
        assertEquals(emptyList<Bhavcopy.Bar>(), Bhavcopy.parse("<html>not found</html>"))
    }

    @Test fun readsIndexCloses() {
        val n = Bhavcopy.parseIndices(File("src/test/resources/data/index_sample.csv").readText()).first()
        assertEquals("Nifty 50", n.name); assertEquals(25291.1, n.close, 1e-9); assertEquals(20.6, n.pe!!, 1e-9); assertEquals(3.1, n.pb!!, 1e-9)
    }

    // --------------------------------------------------------- artifact states

    @Test fun artifactLifeCycle() {
        var s: State = State.NotInstalled
        s = Artifacts.next(s, Ev.Install); assertEquals(State.Downloading(0, 0), s)
        s = Artifacts.next(s, Ev.Progress(5, 10)); assertEquals(State.Downloading(5, 10), s)
        s = Artifacts.next(s, Ev.Pause("No Wi-Fi")); assertTrue(s is State.Paused)
        s = Artifacts.next(s, Ev.Resume); assertEquals(State.Downloading(5, 10), s)
        s = Artifacts.next(s, Ev.Downloaded); assertEquals(State.Verifying, s)
        s = Artifacts.next(s, Ev.Verified); assertTrue(s is State.Building)
        s = Artifacts.next(s, Ev.Built("2026-11-16", 100, 1)); assertTrue(s is State.Ready)
        s = Artifacts.next(s, Ev.Downloaded); assertTrue("a late callback changes nothing", s is State.Ready)
        s = Artifacts.next(s, Ev.Outdated("1 day")); assertTrue(s is State.Stale)
        s = Artifacts.next(s, Ev.Install); assertTrue(s is State.Updating)
        s = Artifacts.next(s, Ev.Fail("offline")); assertEquals(State.Failed("offline"), s)
        s = Artifacts.next(s, Ev.Install); assertTrue(s is State.Downloading)
        assertEquals(State.NotInstalled, Artifacts.next(s, Ev.Delete))
        assertEquals("Downloading 50%", Artifacts.describe(State.Downloading(5, 10)))
        // progress moves an update forward too, and a late "Install" never knocks a running update back
        assertEquals(State.Updating(3, 9), Artifacts.next(State.Updating(0, 9), Ev.Progress(3, 9)))
        assertEquals(State.Updating(3, 9), Artifacts.next(State.Updating(3, 9), Ev.Install))
    }

    // ------------------------------------------------------------ the engine

    /** 320 weekdays of four fictional stocks with known behaviour. */
    private fun panel(): Panel {
        val days = Bhavcopy.tradingDayCandidates(LocalDate.of(2025, 8, 4), LocalDate.of(2026, 11, 13)).take(320)
        val price = mutableMapOf("RISER" to 100.0, "FADER" to 100.0, "STEADY" to 100.0, "SPLITCO" to 1000.0)
        return Panel.build(days.mapIndexed { i, d ->
            d to price.keys.map { sym ->
                val prev = price[sym]!!
                var close = when (sym) { "RISER" -> prev * 1.003; "FADER" -> prev * 0.998; "STEADY" -> prev * (1 + (if (i % 2 == 0) 0.0013 else -0.001)); else -> prev * 1.001 }
                var adjPrev = prev
                if (sym == "SPLITCO" && i == 200) { close /= 5; adjPrev = prev / 5 }           // 1:5 split: NSE's previous close is adjusted the same day
                val vol = if (sym == "STEADY" && i == days.size - 1) 5_000_000L else 1_000_000L      // a volume spike on the last day
                price[sym] = close
                Bhavcopy.Bar(sym, "EQ", adjPrev, maxOf(adjPrev, close), minOf(adjPrev, close), close, adjPrev, vol, close * vol, 1000, "")
            }
        })
    }

    @Test fun splitsDoNotLookLikeCrashes() {
        val p = panel(); val s = p.of("SPLITCO")!!
        assertEquals(0.001, p.ret[s][200].toDouble(), 1e-6)                        // the split day is a normal +0.1% day
        assertTrue(Market.change(p, s, p.days - 1, 252)!! > 0.2)                    // a year of +0.1% a day, not −80%
    }

    @Test fun snapshotRoundTripsAndKeepsOnlyLiquidStocks() {
        val p = panel()
        val f = File.createTempFile("panel", ".bin").apply { deleteOnExit() }
        com.khayali.pulao.ai.data.PanelFile.write(com.khayali.pulao.ai.data.PanelFile.slim(p), f)
        val q = com.khayali.pulao.ai.data.PanelFile.read(f)!!
        assertEquals(p.dates, q.dates); assertEquals(p.symbols, q.symbols)
        val s = p.of("RISER")!!; assertEquals(p.adj[s].toList(), q.adj[q.of("RISER")!!].toList())
        assertEquals(Market.regime(p), Market.regime(q))
        assertNull(com.khayali.pulao.ai.data.PanelFile.read(File.createTempFile("junk", ".bin").apply { writeText("nope"); deleteOnExit() }))
    }

    @Test fun newDaysExtendTheSnapshotExactly() {
        val full = panel()
        fun slice(p: Panel, from: Int, to: Int, syms: List<String> = p.symbols): Panel {
            val ix = syms.map { p.of(it)!! }
            fun cut(a: Array<FloatArray>) = Array(ix.size) { a[ix[it]].copyOfRange(from, to) }
            return Panel(p.dates.subList(from, to), syms, cut(p.adj), cut(p.ret), cut(p.value), cut(p.volume))
        }
        val old = slice(full, 0, 300, listOf("FADER", "RISER", "SPLITCO"))         // a stock appears only in the new days
        val tail = slice(full, 300, full.days)
        val merged = com.khayali.pulao.ai.data.PanelFile.append(old, tail, max = 1000)
        assertEquals(full.dates, merged.dates); assertEquals(full.symbols, merged.symbols)
        for (sym in listOf("FADER", "RISER", "SPLITCO")) assertEquals(full.adj[full.of(sym)!!].toList(), merged.adj[merged.of(sym)!!].toList())
        assertTrue(merged.adj[merged.of("STEADY")!!].take(300).all { it.isNaN() })        // only known from the new days
        assertEquals(full.ret[full.of("STEADY")!!].drop(300), merged.ret[merged.of("STEADY")!!].drop(300))
        val trimmed = com.khayali.pulao.ai.data.PanelFile.append(old, tail, max = 310)
        assertEquals(full.dates.takeLast(310), trimmed.dates)
        assertEquals(old, com.khayali.pulao.ai.data.PanelFile.append(old, slice(full, 0, 10), max = 1000))   // nothing newer: unchanged
    }

    @Test fun regimeAndAnomalies() {
        val p = panel(); val r = Market.regime(p)
        assertEquals(4, r.stocks)
        assertEquals(75.0, r.above200, 1e-9)                                        // all but the fader
        val a = Market.anomalies(p)
        assertEquals("STEADY", a.first { it.kind == "Volume spike" }.symbol)
        assertNull(a.firstOrNull { it.symbol == "FADER" && it.kind == "Volume spike" })
        assertTrue(a.any { it.symbol == "FADER" && it.kind == "Losing streak" })
    }

    @Test fun holdingHealth() {
        val h = Market.health(panel(), "fader")!!
        assertTrue(h.r1m!! < 0 && h.vs200!! < 0)
        assertTrue(h.flags.toString(), h.flags.contains("below its 200-day average"))
        assertNull(Market.health(panel(), "NOPE"))
    }

    @Test fun strategiesRankAndBacktestWithoutLookingAhead() {
        val p = panel()
        val mom = Market.strategy("momentum")!!
        assertEquals("RISER", p.symbols[mom.pick(p, p.days - 1, Market.liquid(p, p.days - 1)).first()])
        val bt = Market.backtest(p, mom, warmup = 0)!!
        assertTrue("momentum waits a year before its first pick", bt.months in 2..4 && bt.from >= p.dates[253])
        assertTrue(bt.maxDrawdown <= 0)
        assertEquals(1.0, bt.curve.first().second, 1e-9)
        // look-ahead check: picks on a day must not change when later days are removed
        val cut = 280
        val early = Panel.build((0..cut).map { d -> p.dates[d] to p.symbols.mapIndexedNotNull { s, sym ->
            val c = p.adj[s][d].toDouble(); if (c.isNaN()) null else Bhavcopy.Bar(sym, "EQ", c, c, c, c, c / (1 + p.ret[s][d]), p.volume[s][d].toLong(), p.value[s][d].toDouble(), 1, "") } })
        assertEquals(mom.pick(early, cut, Market.liquid(early, cut)).map { early.symbols[it] }, mom.pick(p, cut, Market.liquid(p, cut)).map { p.symbols[it] })
        for (st in Market.STRATEGIES) assertNotNull(st.id, Market.backtest(p, st, warmup = 0))
        assertFalse(Market.STRATEGIES.map { it.id }.toSet().size < Market.STRATEGIES.size)
    }
}

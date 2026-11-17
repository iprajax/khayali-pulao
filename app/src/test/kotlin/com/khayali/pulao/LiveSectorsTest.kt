package com.khayali.pulao

import com.khayali.pulao.ai.data.Bhavcopy
import com.khayali.pulao.ai.data.MarketPack
import com.khayali.pulao.ai.data.Sectors
import com.khayali.pulao.ai.term.Sec
import com.khayali.pulao.ai.term.SectorBoardFeature
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The sector board on a real local copy of NSE's daily index files. Skipped unless KP_IDX_DIR is set. */
class LiveSectorsTest {
    @Test fun boardOnRealIndexHistory() {
        val dir = System.getenv("KP_IDX_DIR")?.let(::File); assumeTrue(dir != null && dir.isDirectory)
        val pts = HashMap<String, MutableList<Pair<LocalDate, Double>>>(); val meta = HashMap<String, Triple<Double?, Double?, Double?>>()
        dir!!.listFiles()!!.sortedBy { it.name }.forEach { f ->
            val d = LocalDate.parse(f.name.take(8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
            Bhavcopy.parseIndices(f.readText()).forEach { r -> pts.getOrPut(r.name) { mutableListOf() } += d to r.close; meta[r.name] = Triple(r.pe, r.pb, r.dy) }
        }
        val book = MarketPack.SectorBook(pts.mapValues { Sectors.Series(it.value) }, meta, emptyMap())
        for (w in listOf("1d", "1w", "1y", "5y")) println("LIVE " + com.google.gson.JsonParser.parseString(SectorBoardFeature.build(book, Sec.window(w, Sectors.Period.D1))).asJsonObject["summary"].asString)
        Sectors.board(book.series, book.meta).sortedByDescending { it.cagr5 ?: -1.0 }.forEach { r ->
            println("LIVE %-18s 1D %6.2f%% 1W %6.2f%% 1M %6.2f%% 1Y %6.2f%% 3Y CAGR %6.2f%% 5Y CAGR %6.2f%% P/E %s".format(r.sector.short,
                (r.changes[Sectors.Period.D1] ?: 0.0) * 100, (r.changes[Sectors.Period.W1] ?: 0.0) * 100, (r.changes[Sectors.Period.M1] ?: 0.0) * 100,
                (r.changes[Sectors.Period.Y1] ?: 0.0) * 100, (r.cagr3 ?: 0.0) * 100, (r.cagr5 ?: 0.0) * 100, r.pe)) }
    }
}

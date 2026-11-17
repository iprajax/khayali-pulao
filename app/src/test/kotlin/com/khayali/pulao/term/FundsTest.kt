package com.khayali.pulao.term

import com.khayali.pulao.ai.ChatFlow
import com.khayali.pulao.ai.term.FundsCompareFeature
import com.khayali.pulao.ai.term.FundsData
import com.khayali.pulao.ai.term.FundsEmiFeature
import com.khayali.pulao.ai.term.FundsFundFeature
import com.khayali.pulao.ai.term.FundsLumpsumFeature
import com.khayali.pulao.ai.term.FundsMath
import com.khayali.pulao.ai.term.FundsRetireFeature
import com.khayali.pulao.ai.term.FundsSipFeature
import com.khayali.pulao.ai.term.FundsSipRealFeature
import com.khayali.pulao.ai.term.FundsStepUpFeature
import com.khayali.pulao.ai.term.FundsSwpFeature
import com.khayali.pulao.ai.term.Hosts
import com.khayali.pulao.ai.term.Terminal
import com.khayali.pulao.term.TermTesting.card
import com.khayali.pulao.term.TermTesting.error
import com.khayali.pulao.term.TermTesting.fixture
import com.khayali.pulao.term.TermTesting.viz
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Mutual-fund features (against trimmed real mfapi.in replies) and the planning calculators (against hand-computed values). */
class FundsTest {
    private val today = LocalDate.of(2026, 11, 17)
    private fun summary(json: String) = card(json)["summary"].asString
    private fun headline(json: String) = card(json).getAsJsonObject("card")["headline"].asString
    private fun sub(json: String) = card(json).getAsJsonObject("card")["sub"].asString
    private fun stat(json: String, label: String) = TermTesting.blocks(json).first { it["type"].asString == "stats" }.getAsJsonArray("items").map { it.asJsonObject }.first { it["label"].asString == label }["value"].asString
    private fun series(json: String, i: Int) = viz(json, "balance").getAsJsonArray("series")[i].asJsonObject
    private fun points(json: String, i: Int) = series(json, i).getAsJsonArray("points").map { it.asJsonArray }
    private fun ppfas() = FundsData.parse(fixture("funds_nav_ppfas.json"))!!
    private fun taurus() = FundsData.parse(fixture("funds_nav_taurus_nifty.json"))!!

    // ---------- the maths, against hand-computed values ----------

    @Test fun sipAndLumpSumMaths() {
        // 10,000 × ((1.01^180 − 1) / 0.01) × 1.01
        assertEquals(5045759.995, FundsMath.sipValue(10000.0, 12.0, 180), 0.01)
        assertEquals(120000.0, FundsMath.sipValue(10000.0, 0.0, 12), 1e-9)                    // no return: just the money put in
        assertEquals(10100.0, FundsMath.sipValue(10000.0, 12.0, 1), 1e-9)                     // one instalment, one month at 1%
        val s = FundsMath.sipSchedule(10000.0, 12.0, 180)
        assertEquals(16, s.size); assertEquals(FundsMath.Point(0.0, 0.0, 0.0), s.first())
        assertEquals(1800000.0, s.last().invested, 1e-6); assertEquals(5045759.995, s.last().value, 0.01)
        assertEquals(FundsMath.sipValue(10000.0, 12.0, 12), s[1].value, 1e-6)
        // step-up 10% a year: year 2 pays 11,000 a month
        val up = FundsMath.sipSchedule(10000.0, 12.0, 180, 10.0)
        assertEquals(120000.0 + 132000.0, up[2].invested, 1e-6)
        assertEquals(3812697.80, up.last().invested, 0.01); assertEquals(8683849.43, up.last().value, 0.01)
        assertEquals(37974.98, FundsMath.lastInstalment(10000.0, 10.0, 180), 0.01)            // 10,000 × 1.1^14
        assertEquals(10000.0, FundsMath.lastInstalment(10000.0, 10.0, 12), 1e-9)
        assertEquals(3960464.27, FundsMath.lumpValue(1.2e6, 12.0, 120), 0.01)                 // 12,00,000 × 1.01^120
        assertEquals(listOf(0.0, 1.0, 1.5), FundsMath.lumpSchedule(100.0, 12.0, 18).map { it.x })
    }

    @Test fun emiMaths() {
        // 50,00,000 at 8.5% for 240 months: P·i·(1+i)^n / ((1+i)^n − 1)
        assertEquals(43391.16, FundsMath.emi(5e6, 8.5, 240), 0.01)
        assertEquals(10000.0, FundsMath.emi(120000.0, 0.0, 12), 1e-9)
        val s = FundsMath.loanSchedule(5e6, 8.5, 240)
        assertEquals(20, s.size)
        assertEquals(99511.46, s[0].principal, 0.01); assertEquals(421182.48, s[0].interest, 0.01); assertEquals(4900488.54, s[0].balance, 0.01)
        assertEquals(5e6, s.sumOf { it.principal }, 0.01)                                      // every rupee borrowed is repaid
        assertEquals(5413878.80, s.sumOf { it.interest }, 0.05)
        assertEquals(0.0, s.last().balance, 1e-9)
        assertTrue(s.zipWithNext().all { (a, b) -> b.balance < a.balance && b.interest < a.interest })
        assertEquals(listOf(1, 2), FundsMath.loanSchedule(1e5, 10.0, 18).map { it.year })     // a part year still gets its row
    }

    @Test fun swpMaths() {
        // n = −ln(1 − C·i/w) / ln(1+i) = 165.34 → 165 full withdrawals
        val a = FundsMath.swp(1e7, 1e5, 8.0)
        assertEquals(165, a.lastsMonths); assertEquals(16534128.75, a.withdrawn, 0.01); assertEquals(a.withdrawn - 1e7, a.earned, 0.01)
        assertEquals(0.0, a.balance.last().second, 0.0); assertEquals(166 / 12.0, a.balance.last().first, 1e-9); assertEquals(0.0 to 1e7, a.balance.first())
        assertEquals(269, FundsMath.swp(1e7, 80000.0, 8.0).lastsMonths)
        // the first month's return (66,667) is more than 60,000: the balance grows
        val b = FundsMath.swp(1e7, 60000.0, 8.0)
        assertNull(b.lastsMonths); assertEquals(19935729.66, b.balance.first { it.first == 30.0 }.second, 0.01)
        assertEquals(100, FundsMath.swp(1e6, 10000.0, 0.0).lastsMonths)                        // no return: corpus ÷ withdrawal
    }

    @Test fun retirementMaths() {
        val r = FundsMath.retirement(50000.0, 25, 25, 6.0, 12.0, 8.0)
        assertEquals(214593.54, r.expenseAtRetirement, 0.01)                                   // 50,000 × 1.06^25
        assertEquals(51911227.65, r.corpus, 0.5)                                               // growing annuity due, q = 1.06/1.08
        assertEquals(27355.75, r.sip, 0.01)
        assertEquals(r.corpus, FundsMath.sipValue(r.sip, 12.0, 300), 0.01)                     // the SIP reaches exactly the corpus…
        assertEquals(r.corpus, r.path.first { it.first == 25.0 }.second, 0.01)
        assertEquals(50.0, r.path.last().first, 0.0); assertEquals(0.0, r.path.last().second, 1.0)   // …and the corpus is spent exactly at the end
        // inflation equal to the return: the corpus is simply years × first-year spending
        assertEquals(12 * 1000.0 * 10, FundsMath.retirement(1000.0, 0, 10, 5.0, 10.0, 5.0).corpus, 1e-6)
        assertEquals(0.0, FundsMath.retirement(1000.0, 0, 10, 5.0, 10.0, 5.0).sip, 0.0)
    }

    @Test fun xirrMaths() {
        val d = LocalDate.of(2024, 1, 1)
        assertEquals(10.0, FundsMath.xirr(listOf(d to -1000.0, d.plusDays(365) to 1100.0))!!, 1e-6)
        assertEquals(21.0, FundsMath.xirr(listOf(d to -1000.0, d.plusDays(730) to 1464.1))!!, 1e-6)      // 1.21² = 1.4641
        assertEquals(-50.0, FundsMath.xirr(listOf(d to -1000.0, d.plusDays(365) to 500.0))!!, 1e-6)
        // two instalments: 1000·(1+r) + 1000·(1+r)^0.5 = 2310 → (1+r)^0.5 = 1.1 exactly when the second date is half a year in
        assertEquals(21.0, FundsMath.xirr(listOf(d to -1000.0, d.plusDays(365) to -1000.0, d.plusDays(730) to 1000 * 1.4641 + 1000 * 1.21))!!, 1e-6)
        assertNull(FundsMath.xirr(listOf(d to -1000.0, d.plusDays(365) to -10.0)))             // nothing ever comes back
        assertNull(FundsMath.xirr(emptyList()))
    }

    @Test fun readsNumbersTheWayPeopleTypeThem() {
        FundsMath.Args("10000 12% 15y").let { assertEquals(10000.0, it.amount()); assertEquals(12.0, it.pct()); assertEquals(15.0, it.years()) }
        FundsMath.Args("15 years ₹10,000 at 12 percent").let { assertEquals(10000.0, it.amount()); assertEquals(12.0, it.pct()); assertEquals(15.0, it.years()) }
        FundsMath.Args("50 lakh 8.5 20").let { assertEquals(5e6, it.amount()); assertEquals(8.5, it.pct()); assertEquals(20.0, it.years()); assertNull(it.years()) }
        FundsMath.Args("1.5 crore 50k 7.5%").let { assertEquals(1.5e7, it.amount()); assertEquals(50000.0, it.amount()); assertEquals(7.5, it.pct()) }
        FundsMath.Args("10000 10 12 15").let { assertEquals(10000.0, it.amount()); assertEquals(10.0, it.pct()); assertEquals(12.0, it.pct()); assertEquals(15.0, it.years()) }
        assertTrue(FundsMath.Args("").isEmpty); assertTrue(FundsMath.Args("please").isEmpty)
    }

    // ---------- mfapi.in: search, ranking, parsing ----------

    @Test fun picksDirectGrowthAndSkipsLookalikes() {
        val parag = FundsData.rank(FundsData.hits(fixture("funds_search_parag.json")), "parag flexi")
        assertEquals(4, parag.size)
        assertEquals(FundsData.Hit(122639, "Parag Parikh Flexi Cap Fund - Direct Plan - Growth"), parag.first())
        assertEquals(122640, FundsData.rank(FundsData.hits(fixture("funds_search_parag.json")), "parag flexi regular").first().code)
        assertEquals(153964, FundsData.rank(FundsData.hits(fixture("funds_search_parag.json")), "parag flexi idcw").first().code)
        // "nifty 50 index" must not land on a Nifty NEXT 50 fund, an IDCW option or a regular plan
        val nifty = FundsData.rank(FundsData.hits(fixture("funds_search_nifty.json")), "nifty 50 index")
        assertEquals(15, nifty.size)
        assertEquals(listOf(118347, 118881, 154301), nifty.take(3).map { it.code })
        assertTrue(nifty.take(3).all { it.name.contains("Direct") && it.name.contains("Growth") && !it.name.contains("Next") })
        assertTrue(FundsData.hits("[]").isEmpty()); assertTrue(FundsData.hits("<html>").isEmpty()); assertTrue(FundsData.hits("""{"error":"x"}""").isEmpty())
        assertEquals("Parag Parikh Flexi Cap Fund", FundsData.short("Parag Parikh Flexi Cap Fund - Direct Plan - Growth"))
        assertEquals("IDBI NIFTY 50 Index Fund", FundsData.short("IDBI NIFTY 50  Index Fund Growth Direct"))
        assertEquals("Axis Growth Opportunities Fund", FundsData.short("Axis Growth Opportunities Fund - Direct Plan - Growth"))
    }

    @Test fun parsesNavHistory() {
        val s = ppfas()
        assertEquals(122639, s.code); assertEquals("PPFAS Mutual Fund", s.house); assertEquals("Equity Scheme - Flexi Cap Fund", s.category)
        assertEquals(269, s.navs.size)
        assertEquals(LocalDate.of(2021, 10, 28), s.dates.first()); assertEquals(53.0647, s.navs.first(), 1e-9)       // oldest first
        assertEquals(LocalDate.of(2026, 11, 13), s.lastDate); assertEquals(94.2594, s.last, 1e-9)
        assertEquals("Direct plan · Growth", s.plan)
        assertFalse(FundsData.stale(s, today)); assertTrue(FundsData.stale(FundsData.parse(fixture("funds_nav_idbi_stale.json"))!!, today))
        // what the feed sends for an unknown code, a bad code, and a broken reply
        assertNull(FundsData.parse("""{"meta":{"fund_house":"","scheme_type":"","scheme_category":"","scheme_code":0,"scheme_name":"","isin_growth":null,"isin_div_reinvestment":null},"data":[],"status":"SUCCESS"}"""))
        assertNull(FundsData.parse("""{"error":"invalid scheme_code"}""")); assertNull(FundsData.parse("")); assertNull(FundsData.parse("<html>"))
        assertNull(FundsData.parse("""{"meta":{"scheme_name":"X"},"data":[{"date":"13-11-2026","nav":"N.A."},{"date":"bad","nav":"10"},{"date":"12-11-2026","nav":"10.5"}]}"""))   // one usable row is not a history
        assertEquals(135, FundsData.sample(s).size); assertEquals(0, FundsData.sample(s).first()); assertEquals(s.dates.size - 1, FundsData.sample(s).last())
        assertEquals(listOf(101, 102, 104, 106, 108), FundsData.sample(s, 101, 100).take(5))           // the first day asked for is always kept
        assertTrue(Hosts.allowed(FundsData.searchUrl("parag flexi"))); assertTrue(FundsData.searchUrl("parag flexi").endsWith("/mf/search?q=parag+flexi"))
        assertTrue(FundsData.navUrl(122639, today.minusYears(1), today).endsWith("/mf/122639?startDate=2025-11-17&endDate=2026-11-17"))
    }

    // ---------- /fund ----------

    @Test fun fundCard() {
        val json = FundsFundFeature.build(ppfas(), today, "parag flexi")
        assertEquals("NAV ₹94.26", headline(json))
        assertEquals("Parag Parikh Flexi Cap Fund · 13 Nov 2026 · 1 year +0.2%", sub(json))
        // 89.5841 on 13 Oct 2026, 94.0765 on 10 Nov 2025, 64.4866 on 8 Nov 2023, 54.8171 on 8 Nov 2021 → 94.2594
        assertEquals("+5.22%", stat(json, "1 month")); assertEquals("+0.19%", stat(json, "1 year"))
        assertEquals("+13.49%", stat(json, "3 years (a year)")); assertEquals("+11.45%", stat(json, "5 years (a year)"))
        assertEquals("₹95.32", stat(json, "High in this period")); assertEquals("₹45.92", stat(json, "Low in this period"))
        assertEquals("Parag Parikh Flexi Cap Fund - Direct Plan - Growth: NAV ₹94.26 on 13 Nov 2026; 1 month +5.22%, 1 year +0.19%, 3 years +13.49% a year, 5 years +11.45% a year. " +
            "Past returns only. Source: AMFI NAVs via mfapi.in.", summary(json))
        val d = viz(json, "balance")
        assertEquals("date", d["x"].asString); assertEquals("nav", d["unit"].asString); assertEquals("NAV (₹)", d["yLabel"].asString); assertEquals("Date", d["xLabel"].asString)
        val pts = points(json, 0)
        assertEquals(135, pts.size); assertEquals("2021-10-28", pts.first()[0].asString); assertEquals("2026-11-13", pts.last()[0].asString); assertEquals(94.2594, pts.last()[1].asDouble, 1e-9)
        assertTrue(pts.zipWithNext().all { (a, b) -> a[0].asString < b[0].asString })
        assertEquals(listOf("/sipreal parag flexi 10000 5y", "/fundcompare parag flexi vs nifty 50 index", "/sip 10000 12% 15y"), card(json).getAsJsonArray("pills").map { it.asString })
        assertEquals("Mutual fund · PPFAS Mutual Fund · Direct plan · Growth", card(json).getAsJsonObject("card")["kicker"].asString)
    }

    @Test fun fundCardWithShortOrStaleHistory() {
        // a seven-month-old fund: only the 1-month return can be stated
        val young = FundsFundFeature.build(FundsData.parse(fixture("funds_nav_choice_new.json"))!!, today)
        assertNotNull(FundsFundFeature.change(FundsData.parse(fixture("funds_nav_choice_new.json"))!!, 1)); assertNull(FundsFundFeature.change(FundsData.parse(fixture("funds_nav_choice_new.json"))!!, 12))
        assertTrue(summary(young), summary(young).contains("NAV ₹10.95 on 16 Nov 2026; 1 month ") && !summary(young).contains("1 year"))
        // a merged scheme whose NAV stopped in 2023 is labelled, never shown as current
        val dead = FundsFundFeature.build(FundsData.parse(fixture("funds_nav_idbi_stale.json"))!!, today)
        assertTrue(summary(dead), summary(dead).contains("NAV ₹39.40 on 27 Jul 2023") && summary(dead).contains("too little for period returns"))
        assertTrue(TermTesting.blocks(dead).first { it["type"].asString == "note" }["text"].asString.startsWith("The last NAV on this feed is from 27 Jul 2023"))
    }

    @Test fun fundRunSearchesThenSkipsTheDeadScheme() {
        val fake = TermTesting.Fake(pages = mapOf("search?q=nifty" to fixture("funds_search_nifty.json"), "/mf/118347?" to fixture("funds_nav_idbi_stale.json"), "/mf/118881?" to fixture("funds_nav_taurus_nifty.json")))
        val json = runBlocking { FundsFundFeature.run("nifty 50 index", fake) }
        assertTrue(summary(json), summary(json).startsWith("Taurus Nifty 50 Index Fund - Direct Plan - Growth: NAV ₹51.37 on 16 Nov 2026"))
        assertEquals(3, fake.asked.size)
        assertTrue(fake.asked[0], fake.asked[0].endsWith("/mf/search?q=nifty+50+index"))
        assertTrue(fake.asked[1], fake.asked[1].endsWith("/mf/118347?startDate=2021-10-28&endDate=2026-11-17"))
        assertTrue(fake.asked.all { it.startsWith("get:") && Hosts.allowed(it.removePrefix("get:")) })
        // nothing found, nothing typed, feed down
        assertTrue(error(runBlocking { FundsFundFeature.run("zzzz", TermTesting.Fake(pages = mapOf("search" to "[]"))) })!!.contains("No mutual fund found for “zzzz”"))
        assertTrue(error(runBlocking { FundsFundFeature.run("  ", TermTesting.Fake()) })!!.contains("Which fund?"))
        assertTrue(error(runBlocking { FundsFundFeature.run("parag flexi", TermTesting.Fake(pages = mapOf("search" to fixture("funds_search_parag.json")))) })!!.contains("Couldn't read NAV history for Parag Parikh Flexi Cap Fund - Direct Plan - Growth"))
    }

    // ---------- /fundcompare ----------

    @Test fun fundCompareRebasesBothTo100() {
        assertEquals("parag flexi" to "nifty 50 index", FundsCompareFeature.split("parag flexi vs nifty 50 index"))
        assertEquals("a b" to "c", FundsCompareFeature.split("a b, c")); assertNull(FundsCompareFeature.split("parag flexi"))
        val json = FundsCompareFeature.build(ppfas(), taurus(), today)
        // common window 13 Nov 2023 → 13 Nov 2026: 66.0779 → 94.2594 and 39.7182 → 51.0755
        assertEquals("142.65 vs 128.59", headline(json))
        val a = points(json, 0); val b = points(json, 1)
        assertEquals(100.0, a.first()[1].asDouble, 1e-9); assertEquals(100.0, b.first()[1].asDouble, 1e-9)
        assertEquals("2023-11-16", a.first()[0].asString); assertEquals("2023-11-15", b.first()[0].asString)
        assertEquals(142.65, a.last()[1].asDouble, 1e-9); assertEquals(128.59, b.last()[1].asDouble, 1e-9); assertEquals("2026-11-13", b.last()[0].asString)
        assertEquals("Parag Parikh Flexi Cap Fund", series(json, 0)["name"].asString); assertEquals(100, viz(json, "balance").getAsJsonObject("marker")["value"].asInt)
        assertEquals("Over 3 years to 13 Nov 2026, ₹100 in Parag Parikh Flexi Cap Fund - Direct Plan - Growth became ₹142.65 (+42.65%, +12.60% a year) and ₹100 in Taurus Nifty 50 Index Fund - Direct Plan - Growth became ₹128.59 (+28.59%, +8.76% a year); " +
            "Parag Parikh Flexi Cap Fund ended 14.05 points ahead. Past returns only. Source: AMFI NAVs via mfapi.in.", summary(json))
        val rows = TermTesting.blocks(json).first { it["type"].asString == "rows" }.getAsJsonArray("rows").map { it.asJsonObject }
        assertEquals("+12.60% a year · deepest fall from a peak −10.6%", rows[0]["sub"].asString); assertEquals("+28.6%", rows[1]["value"].asString)
        // a young fund shortens the window, and says so; the same scheme twice is refused
        val young = FundsCompareFeature.build(ppfas(), FundsData.parse(fixture("funds_nav_choice_new.json"))!!, today)
        assertTrue(summary(young), summary(young).startsWith("Over 0.6 years to 13 Nov 2026") && !summary(young).contains("a year)"))
        assertTrue(error(FundsCompareFeature.build(ppfas(), ppfas(), today))!!.contains("same scheme"))
        assertTrue(error(FundsCompareFeature.build(ppfas(), FundsData.parse(fixture("funds_nav_idbi_stale.json"))!!, today))!!.contains("less than a month"))
        assertTrue(error(runBlocking { FundsCompareFeature.run("parag flexi", TermTesting.Fake()) })!!.contains("Name two funds"))
        val fake = TermTesting.Fake(pages = mapOf("search?q=parag" to fixture("funds_search_parag.json"), "search?q=nifty" to fixture("funds_search_nifty.json"),
            "/mf/122639?" to fixture("funds_nav_ppfas.json"), "/mf/118881?" to fixture("funds_nav_taurus_nifty.json")))
        assertEquals("142.65 vs 128.59", headline(runBlocking { FundsCompareFeature.run("parag flexi vs nifty 50 index", fake) }))
    }

    // ---------- /sipreal ----------

    @Test fun sipThroughActualNavs() {
        FundsSipRealFeature.ask("parag flexi 10000 5y").let { assertEquals("parag flexi", it.fund); assertEquals(10000.0, it.amount, 0.0); assertEquals(5, it.years); assertTrue(it.assumed.isEmpty()) }
        FundsSipRealFeature.ask("hdfc flexi cap 2.5 lakh 10y").let { assertEquals("hdfc flexi cap", it.fund); assertEquals(250000.0, it.amount, 0.0); assertEquals(10, it.years) }
        FundsSipRealFeature.ask("nifty 50 index 5k 3y").let { assertEquals("nifty 50 index", it.fund); assertEquals(5000.0, it.amount, 0.0); assertEquals(3, it.years) }
        FundsSipRealFeature.ask("nifty 50").let { assertEquals("nifty 50", it.fund); assertEquals(10000.0, it.amount, 0.0); assertEquals(listOf("₹10,000 a month", "5 years"), it.assumed) }
        val s = ppfas()
        // 60 instalments from 15 Nov 2021 buy 8,692.8273 units; × 94.2594 = 8,19,380.69
        val r = FundsMath.sipReal(s.dates, s.navs, 10000.0, 5)!!
        assertEquals(60, r.instalments); assertEquals(600000.0, r.invested, 1e-6); assertEquals(8692.8273, r.units, 1e-4); assertEquals(819380.69, r.value, 0.01)
        assertEquals(12.4354, r.xirr!!, 1e-3); assertEquals(LocalDate.of(2021, 11, 15), r.first); assertEquals(61, r.path.size)
        val json = FundsSipRealFeature.build(s, 10000.0, 5, today, "parag flexi")
        assertEquals("₹8,19,381", headline(json))
        assertEquals("from ₹6,00,000 invested · gain ₹2,19,381 (+36.6%) · XIRR +12.44% a year", sub(json))
        assertEquals("A SIP of ₹10,000 a month in Parag Parikh Flexi Cap Fund - Direct Plan - Growth from 15 Nov 2021 (60 instalments, ₹6,00,000 invested) was worth ₹8,19,381 on 13 Nov 2026, an XIRR of +12.44% a year. " +
            "Past returns only. Source: AMFI NAVs via mfapi.in.", summary(json))
        val value = points(json, 0); val invested = points(json, 1)
        assertEquals(61, value.size); assertEquals(819381L, value.last()[1].asLong); assertEquals(600000L, invested.last()[1].asLong); assertEquals(10000L, invested.first()[1].asLong)
        assertTrue(series(json, 1)["dashed"].asBoolean); assertEquals("inr", viz(json, "balance")["unit"].asString)
        // three years, a different amount
        val r3 = FundsMath.sipReal(s.dates, s.navs, 5000.0, 3)!!
        assertEquals(36, r3.instalments); assertEquals(198936.59, r3.value, 0.01); assertEquals(6.6195, r3.xirr!!, 1e-3)
        // a fund younger than the period: fewer instalments, and the card says why
        val young = FundsSipRealFeature.build(FundsData.parse(fixture("funds_nav_choice_new.json"))!!, 10000.0, 5, today)
        assertTrue(summary(young), summary(young).contains("from 9 Apr 2026 (8 instalments, ₹80,000 invested) was worth ₹85,241 on 16 Nov 2026"))
        assertTrue(TermTesting.blocks(young).first { it["type"].asString == "note" }["text"].asString.contains("so the SIP runs for 8 instalments instead of 60"))
        assertNull(FundsMath.sipReal(listOf(today), listOf(10.0), 1000.0, 1))
        assertTrue(error(runBlocking { FundsSipRealFeature.run("", TermTesting.Fake()) })!!.contains("Which fund?"))
    }

    // ---------- calculators ----------

    @Test fun sipCard() {
        val json = FundsSipFeature.build("10000 12% 15y")
        assertEquals("₹50,45,760", headline(json))
        assertEquals("Invested ₹18,00,000 · growth ₹32,45,760 · 2.80× the money put in", sub(json))
        assertEquals("A SIP of ₹10,000 a month for 15 years at an assumed 12% a year grows to ₹50,45,760: ₹18,00,000 invested and ₹32,45,760 growth. Illustrative, not a forecast. Source: calculation on the inputs given.", summary(json))
        val d = viz(json, "stackarea"); val pts = d.getAsJsonArray("points").map { it.asJsonObject }
        assertEquals(listOf("Invested", "Growth"), d.getAsJsonArray("series").map { it.asString })
        assertEquals(16, pts.size); assertEquals(0L, pts.first()["a"].asLong); assertEquals(15.0, pts.last()["x"].asDouble, 0.0)
        assertEquals(1800000L, pts.last()["a"].asLong); assertEquals(3245760L, pts.last()["b"].asLong)
        assertEquals(summary(json), summary(FundsSipFeature.build("₹10,000 for 15 years at 12 percent")))
        assertEquals(summary(json), summary(FundsSipFeature.build("")))                         // no figures → the usage example, labelled as one
        assertTrue(TermTesting.blocks(FundsSipFeature.build("")).first { it["type"].asString == "note" }["text"].asString.startsWith("No figures were given"))
        assertTrue(TermTesting.blocks(FundsSipFeature.build("5000")).first { it["type"].asString == "note" }["text"].asString.startsWith("Assumed a 12% yearly return and 10 years"))
        assertTrue(error(FundsSipFeature.build("10000 80% 15y"))!!.contains("/sip 10000 12% 15y")); assertTrue(error(FundsSipFeature.build("0 12% 15y")) != null); assertTrue(error(FundsSipFeature.build("10000 12% 99y")) != null)
        assertFalse(FundsSipFeature.needsWeb)
        assertEquals("₹1,20,000", headline(FundsSipFeature.build("10000 0% 1y")))
    }

    @Test fun stepUpCard() {
        val json = FundsStepUpFeature.build("10000 10% 12% 15y")
        assertEquals("₹86,83,849", headline(json))
        assertEquals("against ₹50,45,760 with a flat SIP · ₹36,38,089 more, from ₹20,12,698 more invested", sub(json))
        assertEquals("₹37,975 a month", stat(json, "SIP in the last year"))
        assertEquals(listOf("Step-up SIP", "Flat SIP"), listOf(series(json, 0), series(json, 1)).map { it["name"].asString })
        assertEquals(8683849L, points(json, 0).last()[1].asLong); assertEquals(5045760L, points(json, 1).last()[1].asLong); assertEquals(16, points(json, 0).size)
        assertEquals(3812698L, viz(json, "stackarea").getAsJsonArray("points").last().asJsonObject["a"].asLong)
        assertTrue(summary(json), summary(json).startsWith("A SIP of ₹10,000 a month raised 10% every year for 15 years at an assumed 12% a year grows to ₹86,83,849 on ₹38,12,698 invested; kept flat it grows to ₹50,45,760 on ₹18,00,000 invested."))
        assertEquals(headline(FundsSipFeature.build("10000 12% 15y")), headline(FundsStepUpFeature.build("10000 0% 12% 15y")))       // no step-up = a flat SIP
        assertTrue(error(FundsStepUpFeature.build("10000 500% 12% 15y")) != null)
    }

    @Test fun swpCard() {
        val json = FundsSwpFeature.build("1 crore 100000 8%")
        assertEquals("Lasts 13 years 9 months", headline(json))
        assertEquals("Withdrawing ₹1,00,000 a month from ₹1,00,00,000 at an assumed 8% a year lasts 13 years 9 months (165 full withdrawals, ₹1,65,34,129 paid out in all). Illustrative, not a forecast. Source: calculation on the inputs given.", summary(json))
        val d = viz(json, "balance")
        assertEquals(0, d.getAsJsonObject("marker")["value"].asInt); assertEquals("Runs out: 13 years 9 months", d.getAsJsonObject("marker")["label"].asString)
        assertEquals(10000000L, points(json, 0).first()[1].asLong); assertEquals(0L, points(json, 0).last()[1].asLong); assertEquals(15, points(json, 0).size)
        assertEquals("12% of corpus", stat(json, "Withdrawn a year")); assertEquals("₹66,667", stat(json, "Return in month 1"))
        val forever = FundsSwpFeature.build("1 crore 60k 8%")
        assertEquals("Does not run out", headline(forever)); assertEquals("₹1,99,35,730", stat(forever, "Balance after 30 years"))
        assertEquals(30.0, points(forever, 0).last()[0].asDouble, 0.0); assertEquals(10000000L, viz(forever, "balance").getAsJsonObject("marker")["value"].asLong)
        assertTrue(summary(forever), summary(forever).contains("does not run the money out: the first month's return is ₹66,667 and the balance after 30 years is ₹1,99,35,730"))
        assertTrue(error(FundsSwpFeature.build("1 crore"))!!.contains("How much is withdrawn")); assertTrue(error(FundsSwpFeature.build("1 lakh 2 lakh 8%"))!!.contains("larger than the corpus"))
        assertEquals("Lasts 22 years 5 months", headline(FundsSwpFeature.build("")))            // the usage example: 1 crore, 80,000, 8%
    }

    @Test fun emiCard() {
        val json = FundsEmiFeature.build("50 lakh 8.5% 20y")
        assertEquals("₹43,391 a month", headline(json))
        assertEquals("Total interest ₹54,13,879 · total repaid ₹1,04,13,879 · interest is 108.28% of the loan", sub(json))
        assertEquals("A loan of ₹50,00,000 at 8.5% a year for 20 years has an EMI of ₹43,391; total interest is ₹54,13,879 and total repaid ₹1,04,13,879. Fixed-rate arithmetic. Source: calculation on the inputs given.", summary(json))
        val d = viz(json, "stackarea"); val pts = d.getAsJsonArray("points").map { it.asJsonObject }
        assertTrue(d["step"].asBoolean); assertEquals(listOf("Principal", "Interest"), d.getAsJsonArray("series").map { it.asString })
        assertEquals(20, pts.size); assertEquals(99511L, pts[0]["a"].asLong); assertEquals(421182L, pts[0]["b"].asLong)
        assertTrue(pts.all { Math.abs(it["a"].asLong + it["b"].asLong - 520694L) <= 1 })       // every year pays 12 EMIs
        assertEquals(5000000L, points(json, 0).first()[1].asLong); assertEquals(0L, points(json, 0).last()[1].asLong); assertEquals(21, points(json, 0).size)
        assertEquals("year 13", stat(json, "Principal overtakes interest")); assertEquals("by end of year 14", stat(json, "Half the loan repaid")); assertEquals("₹4,21,182", stat(json, "Interest in year 1"))
        assertEquals(summary(json), summary(FundsEmiFeature.build("")))
        assertEquals("₹10,000 a month", headline(FundsEmiFeature.build("1.2 lakh 0% 1y")))
        assertTrue(error(FundsEmiFeature.build("50 lakh 8.5% 90y")) != null)
    }

    @Test fun retireCard() {
        val json = FundsRetireFeature.build("50000 25y 25y 6% 12% 8%")
        assertEquals("₹5.19 crore", headline(json))
        assertEquals("needed in 25 years · SIP of ₹27,356 a month from now gets there at 12% a year", sub(json))
        assertEquals("Spending of ₹50,000 a month today becomes ₹2,14,594 a month in 25 years at 6% inflation; funding 25 years of it needs a corpus of ₹5,19,11,228 at an assumed 8% return in retirement, " +
            "which a SIP of ₹27,356 a month reaches at an assumed 12% a year. Illustrative, not a forecast. Source: calculation on the inputs given.", summary(json))
        val d = viz(json, "balance"); val pts = points(json, 0)
        assertEquals(51911228L, d.getAsJsonObject("marker")["value"].asLong); assertEquals(25, d.getAsJsonArray("marks")[0].asJsonObject["x"].asInt)
        assertEquals(51, pts.size); assertEquals(0L, pts.first()[1].asLong); assertEquals(51911228L, pts[25][1].asLong); assertEquals(0L, pts.last()[1].asLong); assertEquals(50.0, pts.last()[0].asDouble, 0.0)
        assertEquals(summary(json), summary(FundsRetireFeature.build("")))
        assertTrue(TermTesting.blocks(FundsRetireFeature.build("50000 25y")).first { it["type"].asString == "note" }["text"].asString.startsWith("Assumed 25 years in retirement and 6% inflation and a 12% return before retirement and an 8% return after it"))
        assertTrue(error(FundsRetireFeature.build("50000 80y 25y")) != null)
        assertTrue(summary(FundsRetireFeature.build("50000 0y 20y 6% 12% 6%")).contains("needs a corpus of ₹1,20,00,000 at"))            // inflation = return: 20 × 6,00,000
    }

    @Test fun lumpsumCard() {
        val json = FundsLumpsumFeature.build("12 lakh 12% 10y")
        assertEquals("₹39.6 lakh vs ₹23.23 lakh", headline(json))
        assertEquals("₹12,00,000 invested at once for 10 years at an assumed 12% a year grows to ₹39,60,464; the same total as a SIP of ₹10,000 a month grows to ₹23,23,391. Illustrative, not a forecast. Source: calculation on the inputs given.", summary(json))
        assertEquals(listOf("Lump sum", "Monthly SIP"), listOf(series(json, 0), series(json, 1)).map { it["name"].asString })
        assertEquals(1200000L, points(json, 0).first()[1].asLong); assertEquals(3960464L, points(json, 0).last()[1].asLong); assertEquals(2323391L, points(json, 1).last()[1].asLong)
        assertEquals(1200000L, viz(json, "balance").getAsJsonObject("marker")["value"].asLong)
        assertEquals("3.30×", stat(json, "Lump sum multiple")); assertEquals("1.94×", stat(json, "SIP multiple"))
        assertEquals(summary(json), summary(FundsLumpsumFeature.build("")))
    }

    // ---------- every card follows the house rules ----------

    @Test fun everyFundsCardIsComplete() {
        val cards = mapOf(
            "fund" to FundsFundFeature.build(ppfas(), today, "parag flexi"), "fundcompare" to FundsCompareFeature.build(ppfas(), taurus(), today), "sipreal" to FundsSipRealFeature.build(ppfas(), 10000.0, 5, today),
            "sip" to FundsSipFeature.build("10000 12% 15y"), "stepup" to FundsStepUpFeature.build("10000 10% 12% 15y"), "swp" to FundsSwpFeature.build("1 crore 80000 8%"),
            "emi" to FundsEmiFeature.build("50 lakh 8.5% 20y"), "retire" to FundsRetireFeature.build("50000 25y 25y"), "lumpsum" to FundsLumpsumFeature.build("12 lakh 12% 10y"))
        val ids = Terminal.FEATURES.filter { it.javaClass.simpleName.startsWith("Funds") }.map { it.id }.toSet()
        assertEquals(cards.keys, ids)
        for ((id, json) in cards) {
            val c = card(json); val inner = c.getAsJsonObject("card"); val blocks = TermTesting.blocks(json)
            assertNull("$id returned an error", c["error"])
            assertTrue("$id kicker", inner["kicker"].asString.isNotBlank()); assertTrue("$id headline", inner["headline"].asString.isNotBlank()); assertTrue("$id sub", inner["sub"].asString.isNotBlank())
            val charts = blocks.filter { it["type"].asString == "viz" }
            assertTrue("$id needs 1–2 charts", charts.size in 1..2)
            charts.forEach { v ->
                assertTrue("$id chart type", v["viz"].asString in setOf("balance", "stackarea")); assertTrue("$id caption", v["caption"].asString.length > 20)
                assertTrue("$id names both axes", v.getAsJsonObject("data")["xLabel"].asString.isNotBlank() && v.getAsJsonObject("data")["yLabel"].asString.isNotBlank())
            }
            assertTrue("$id rows ≤ 10", blocks.filter { it["type"].asString == "rows" }.all { it.getAsJsonArray("rows").size() <= 10 })
            assertTrue("$id pills", c.getAsJsonArray("pills").size() in 2..4 && c.getAsJsonArray("pills").all { it.asString.startsWith("/") })
            assertEquals("$id sources", 1, c.getAsJsonArray("sources").size())
            val s = c["summary"].asString
            assertTrue("$id summary: $s", s.contains("Source: ") && s.endsWith("."))
            val all = json.lowercase()
            listOf("should buy", "should sell", "recommend", "target price", "guaranteed", "will grow", "best fund").forEach { assertFalse("$id says “$it”", all.contains(it)) }
            assertTrue("$id is too big for a chat card", json.length < 20000)
        }
        Terminal.FEATURES.filter { it.id in setOf("sip", "stepup", "swp", "emi", "retire", "lumpsum") }.forEach { assertFalse(it.id, it.needsWeb) }
        Terminal.FEATURES.filter { it.id in setOf("fund", "fundcompare", "sipreal") }.forEach { assertTrue(it.id, it.needsWeb) }
        assertTrue(Hosts.ALLOWED.contains(FundsData.HOST))
    }

    @Test fun plainQuestionsReachTheRightCalculator() {
        val on = ChatFlow.Env(modelReady = true, kite = ChatFlow.Kite.OFF, webOn = false)
        assertEquals(ChatFlow.Action.Term("sip", ""), ChatFlow.route("sip calculator", on))                     // calculators work with the web off
        assertEquals(ChatFlow.Action.Term("stepup", ""), ChatFlow.route("step-up sip calculator", on))
        assertEquals(ChatFlow.Action.Term("stepup", ""), ChatFlow.route("step up sip", on))
        assertEquals(ChatFlow.Action.Term("swp", ""), ChatFlow.route("show me an swp plan", on))
        assertEquals(ChatFlow.Action.Term("emi", ""), ChatFlow.route("home loan emi calculator", on))
        assertEquals(ChatFlow.Action.Term("retire", ""), ChatFlow.route("retirement calculator", on))
        assertEquals(ChatFlow.Action.Term("lumpsum", ""), ChatFlow.route("lumpsum vs sip", on))
        assertEquals(ChatFlow.Action.Term("fund", "parag flexi"), ChatFlow.route("nav of parag flexi", on.copy(webOn = true)))
        assertEquals(ChatFlow.Action.Term("sip", "10000 12% 15y"), ChatFlow.route("/sip 10000 12% 15y", on))
        assertEquals(ChatFlow.Action.EnableWeb("/fund parag flexi"), ChatFlow.route("/fund parag flexi", on))  // fund data still needs the web switched on
        listOf("my sip is due tomorrow", "what is navigation", "emi") .forEach { assertNull(it, Terminal.match(it)) }
    }
}

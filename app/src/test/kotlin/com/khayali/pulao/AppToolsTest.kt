package com.khayali.pulao

import com.google.gson.JsonParser
import com.khayali.pulao.ai.AppTools
import com.khayali.pulao.ai.DownloadText
import com.khayali.pulao.ai.Money
import com.khayali.pulao.ai.NumberGuard
import com.khayali.pulao.ai.SpeedMeter
import com.khayali.pulao.ai.ToolEvent
import com.khayali.pulao.ai.ToolHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat model's tools: exact maths, validated writes, and no way to trade. Fictional data only. */
class AppToolsTest {

    private class FakeHost(override var stock: Stock?) : ToolHost {
        val settings = mutableMapOf<String, Any?>("fd_rate" to 7.0, "inflation_rate" to 5.0, "watch_amount" to 10_000.0)
        val opened = mutableListOf<String>()
        override fun setting(name: String) = settings[name]
        override fun updateSetting(name: String, value: String): String? {
            val d = value.toDoubleOrNull() ?: return "not a number"
            if (d > 50) return "too high"
            settings[name] = d; return null
        }
        override fun open(target: String) = (target in AppTools.TARGETS).also { if (it) opened += target }
    }

    private val infra = Stock.Holding("INFRA", 250.0, 1200.0, 300000.0, 1450.0)
    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test fun everyToolHasValidSchema() {
        val t = AppTools(FakeHost(infra))
        assertTrue(t.defs.size in 8..12)   // small models do best with a short tool list
        for (d in t.defs) {
            val s = obj(t.schemaJson(d))
            assertEquals(d.name, s["name"].asString)
            assertTrue(s["description"].asString.length > 20)
            assertEquals("object", s["parameters"].asJsonObject["type"].asString)
            for (r in d.required) assertTrue(s["parameters"].asJsonObject["properties"].asJsonObject.has(r))
        }
        assertFalse("no trading tools, ever", t.defs.any { Regex("buy|sell|order|trade", RegexOption.IGNORE_CASE).containsMatchIn(it.name) })
    }

    @Test fun projectMoveMatchesAppMaths() {
        val r = obj(AppTools(FakeHost(infra)).execute("project_move", """{"percent": 10, "from": "avg"}"""))
        assertEquals(1320.0, r["price_after"].asDouble, 0.0)
        assertEquals(330000.0, r["value_after"].asDouble, 0.0)
        assertEquals(30000.0, r["profit_vs_cost"].asDouble, 0.0)
    }

    @Test fun toolsWorkWithoutAStockWhenGivenNumbers() {
        val r = obj(AppTools(FakeHost(null)).execute("project_move", """{"percent": "15", "ltp": "1,450", "qty": 250, "avg": 1200}"""))
        assertEquals(1667.5, r["price_after"].asDouble, 1e-9)
        val g = obj(AppTools(FakeHost(null)).execute("grow_for_years", """{"yearly_return_percent": 12, "years": 10, "amount_rupees": 100000}"""))
        assertEquals(310585.0, g["future_value"].asDouble, 1.0)
    }

    @Test fun missingContextGivesAQuestionNotACrash() {
        val r = obj(AppTools(FakeHost(null)).execute("project_move", """{"percent": 10}"""))
        assertTrue(r.has("error"))
        val bad = obj(AppTools(FakeHost(infra)).execute("project_move", "not json"))
        assertTrue(bad.has("error"))
    }

    @Test fun fdComparisonUsesUserRate() {
        val r = obj(AppTools(FakeHost(infra)).execute("compare_with_fd", """{"years_held": 2}"""))
        assertEquals(9.9, r["your_yearly_return_percent"].asDouble, 0.05)   // 3,00,000 → 3,62,500 over 2 yrs
        assertTrue(r["beat_fd"].asBoolean)                                  // 3,62,500 vs 3,00,000 × 1.07²
        val fd = r["comparison"].asJsonArray.map { it.asJsonObject }.first { it["option"].asString == "Bank FD" }
        assertEquals(7.0, fd["rate"].asDouble, 0.0)
        assertEquals(Math.round(300000 * 1.07 * 1.07).toDouble(), fd["would_be"].asDouble, 1.0)
    }

    @Test fun taxAndGoal() {
        val t = AppTools(FakeHost(infra))
        val tax = obj(t.execute("in_hand_after_tax", """{"held_over_one_year": false}"""))
        assertEquals("STCG", tax["tax_type"].asString)
        assertTrue(tax["in_hand"].asDouble < 62500)
        val goal = obj(t.execute("goal_plan", """{"target_rupees": 725000}"""))
        assertEquals(100.0, goal["growth_needed_percent"].asDouble, 0.0)
        assertEquals(6.1, goal["years_at"].asJsonObject["12%"].asDouble, 0.05)
    }

    @Test fun settingWritesAreValidatedAndReported() {
        val host = FakeHost(infra)
        val events = mutableListOf<ToolEvent>()
        val t = AppTools(host) { events += it }
        assertTrue(obj(t.execute("update_setting", """{"name":"fd_rate","value":"7.25"}"""))["ok"].asBoolean)
        assertEquals(7.25, host.settings["fd_rate"])
        assertTrue(obj(t.execute("update_setting", """{"name":"fd_rate","value":"900"}""")).has("error"))
        assertTrue(obj(t.execute("open_screen", """{"target":"goal"}"""))["ok"].asBoolean)
        assertTrue(obj(t.execute("open_screen", """{"target":"buy_page"}""")).has("error"))
        assertEquals(listOf("goal"), host.opened)
        assertEquals(listOf(true, false, true, false), events.map { it.ok })
    }

    @Test fun downloadEtaAndLabels() {
        val m = SpeedMeter(alpha = 1.0)
        assertNull(m.etaSeconds(0, 100))
        m.sample(0, 0); m.sample(10_000_000, 1000)                     // 10 MB/s
        assertEquals(10_000_000.0, m.bytesPerSec!!, 1.0)
        assertEquals(259L, m.etaSeconds(0, 2_588_147_712L / 1))         // ~2.6 GB at 10 MB/s
        assertEquals("2.59 GB", DownloadText.bytes(2_588_147_712L))
        assertEquals("4 min 19s left", DownloadText.eta(259))
        assertEquals("1 h 2 min left", DownloadText.eta(3720))
        assertEquals(50, DownloadText.percent(50, 100))
    }

    @Test fun summariesCarryExactIndianAmounts() {
        val g = obj(AppTools(FakeHost(null)).execute("grow_for_years", """{"yearly_return_percent": 12, "years": 10, "amount_rupees": 100000}"""))
        val s = g["summary"].asString
        assertTrue(s, s.contains("₹1,00,000") && s.contains("₹3,10,585"))
        for (tool in listOf("project_move" to """{"percent":10}""", "compare_with_fd" to """{"years_held":2}""",
                "goal_plan" to """{"target_rupees":725000}""", "in_hand_after_tax" to "{}", "regret" to """{"past_price":960}""")) {
            assertTrue(tool.first, obj(AppTools(FakeHost(infra)).execute(tool.first, tool.second)).has("summary"))
        }
    }

    @Test fun numberGuardRegroupsMangledRupees() {
        assertEquals("about ₹3,10,05,850 (approx)", NumberGuard.fix("about ₹3,10,0,5850 (approx)"))   // fixes grouping, not digits
        assertEquals("₹19,067 and ₹3,10,585.50", NumberGuard.fix("₹1,90,67 and ₹310585.50"))
        assertEquals("no money here 12%", NumberGuard.fix("no money here 12%"))
    }

    @Test fun unverifiedRupeesAreRemoved() {
        val known = listOf(100000.0, 310585.0, 190670.0)
        assertEquals("It becomes ₹3,10,585, or the amount shown above today.",
            NumberGuard.verify("It becomes ₹310585, or ₹19,067 today.", known))
        assertEquals("₹1,00,000 grows nicely", NumberGuard.verify("₹1,00,000 grows nicely", known))
        assertEquals("fee ₹20", NumberGuard.verify("fee ₹20", known))   // tiny amounts are left alone
    }

    @Test fun indianMoneyWords() {
        assertEquals(1_000_000.0, Money.parse("10 lakh")!!, 0.0)
        assertEquals(15_000_000.0, Money.parse("1.5 crore")!!, 0.0)
        assertEquals(50_000.0, Money.parse("50k")!!, 0.0)
        assertEquals(250_000.0, Money.parse("₹2,50,000")!!, 0.0)
        assertEquals(1e7, Money.parse("1 Cr")!!, 0.0)
        assertNull(Money.parse("lots"))
        val g = obj(AppTools(FakeHost(infra)).execute("goal_plan", """{"target_rupees": "10 lakh"}"""))
        assertEquals(1_000_000.0, g["goal"].asDouble, 0.0)
    }
}

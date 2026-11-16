package com.khayali.pulao

import com.khayali.pulao.ai.ContextPills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pills come from context, not a fixed list. Fictional holdings. */
class ContextPillsTest {
    private val holdingsJson = """{"summary":"x","largest_weight_percent":70.0,"holdings":[
        {"symbol":"INFRA","pnl_percent":20.8},{"symbol":"CHAICO","pnl_percent":-6.0}]}"""

    @Test fun afterHoldingsPillsNameRealStocks() {
        val p = ContextPills.build(ContextPills.Ctx(true, listOf("INFRA", "CHAICO"), "kite_account", holdingsJson, "/holdings"))
        assertTrue(p.toString(), p.contains("/chart INFRA 1y"))
        assertTrue(p.contains("Why is @CHAICO lagging?"))
        assertTrue(p.contains("How risky is having one big holding?"))
    }

    @Test fun afterChartOffersOtherPeriods() {
        val p = ContextPills.build(ContextPills.Ctx(true, listOf("INFRA"), "kite_chart", """{"symbol":"INFRA","period":"6m"}""", "/chart INFRA"))
        assertTrue(p.containsAll(listOf("/chart INFRA 1m", "/chart INFRA 1y", "/chart INFRA 5y")))
        assertTrue(p.none { it == "/chart INFRA 6m" })
    }

    @Test fun errorsOfferRecovery() {
        assertEquals(listOf("Connect Kite to chat about your real portfolio"),
            ContextPills.build(ContextPills.Ctx(false, emptyList(), "kite_account", """{"error":"expired"}""", "/holdings")))
        assertEquals(listOf("Try again"), ContextPills.build(ContextPills.Ctx(true, emptyList(), "kite_chart", """{"error":"x"}""", "/chart X")))
    }

    @Test fun parsesModelSuggestions() {
        val p = ContextPills.parseModel("1. What if INFRA doubles?\n- Compare with FD\n• Here are some suggestions:\nShow my tax")
        assertEquals(listOf("What if INFRA doubles?", "Compare with FD", "Show my tax"), p)
    }
}

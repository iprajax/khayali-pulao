package com.khayali.pulao

import com.khayali.pulao.ai.AppTools
import com.khayali.pulao.ai.ContextPills
import com.khayali.pulao.ai.Persona
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartersTest {
    private val h = listOf(ContextPills.H("INFRA", 362500.0, 20.8), ContextPills.H("CHAICO", 6582.0, -6.0))

    @Test fun startersUseRealHoldings() {
        val a = ContextPills.starters(h, Persona.ADVISOR, true)
        assertTrue(a.toString(), a.contains("How risky is 98% in @INFRA?"))
        assertTrue(a.contains("Why is @CHAICO lagging?"))
        assertTrue(ContextPills.starters(h, Persona.ANALYST, true).contains("What if @INFRA moves +15%?"))
        assertTrue(ContextPills.starters(emptyList(), Persona.ANALYST, false).first().startsWith("Connect Kite"))
    }

    @Test fun userPercentsAreSigned() {
        assertEquals(listOf(-20.0), AppTools.percents("What if my biggest holding falls 20%?"))
        assertEquals(listOf(15.0), AppTools.percents("what if it moves +15%"))
        assertEquals(listOf(-5.0), AppTools.percents("and at -5%?"))
        assertEquals(emptyList<Double>(), AppTools.percents("1 lakh at 12% a year for 10 years"))
    }
}

package com.khayali.pulao

import com.khayali.pulao.ai.Persona
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaTest {
    @Test fun personasNeverPromptTradeCalls() {
        for (p in Persona.entries) {
            val f = p.focus.lowercase()
            assertFalse(p.name, Regex("""\b(you should (buy|sell)|recommend buying|strong buy)\b""").containsMatchIn(f))
        }
        assertTrue(Persona.ADVISOR.focus.contains("Never say buy, sell, hold"))
        assertTrue(Persona.DISCLAIMER.contains("not a SEBI-registered investment adviser"))
    }
}

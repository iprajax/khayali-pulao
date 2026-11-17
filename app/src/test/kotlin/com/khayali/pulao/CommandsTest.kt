package com.khayali.pulao

import com.khayali.pulao.ai.Commands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsTest {
    @Test fun parsesCommands() {
        assertEquals(Commands.Parsed.Kite("holdings"), Commands.parse("/holdings"))
        assertEquals(Commands.Parsed.Kite("holdings"), Commands.parse("/pnl"))
        assertEquals(Commands.Parsed.Clear, Commands.parse("/clear"))
        assertEquals(Commands.Parsed.Tool("goal_plan", """{"target_rupees":"50 lakh"}"""), Commands.parse("/goal 50 lakh"))
        assertEquals(Commands.Parsed.Tool("compare_with_fd", """{"years_held":3.0}"""), Commands.parse("/fd 3y"))
        assertEquals(Commands.Parsed.Tool("project_move", """{"percent":-12.5}"""), Commands.parse("/move −12.5%"))
        assertEquals(Commands.Parsed.Tool("grow_for_years", """{"amount_rupees":"2.5 crore","yearly_return_percent":15.0,"years":7.0}"""), Commands.parse("/grow 2.5 crore at 15% for 7 years"))
        assertTrue(Commands.parse("/grow something vague") is Commands.Parsed.Prompt)      // unreadable → ask the model
        assertEquals(Commands.Parsed.Unknown("buy"), Commands.parse("/buy INFY"))   // no trading commands
        assertEquals(Commands.Parsed.Chart("INFRA", "1y"), Commands.parse("/chart @infra 1Y"))
        assertEquals(Commands.Parsed.Chart("TCS", null), Commands.parse("/chart tcs"))
        assertEquals(Commands.Parsed.Help, Commands.parse("/chart"))
        assertTrue(Commands.suggest("/g").map { it.name }.containsAll(listOf("gmp", "goal", "grow")))
        assertTrue(Commands.suggest("/g").all { it.name.startsWith("g") })
    }

    @Test fun mentions() {
        assertEquals("IN", Commands.typingMention("what if @IN"))
        assertNull(Commands.typingMention("what if @INFY goes up"))
        assertEquals("what if @INFRA ", Commands.completeMention("what if @IN", "INFRA"))
        assertEquals(listOf("INFRA", "TCS"), Commands.mentions("compare @infra and @TCS and @INFRA"))
        assertEquals(listOf("INFRA", "INDIGO", "BINFRA"), Commands.matching("in", listOf("TCS", "INFRA", "BINFRA", "INDIGO")))
    }
}

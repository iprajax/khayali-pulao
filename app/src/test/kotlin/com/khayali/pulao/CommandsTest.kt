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
        assertTrue((Commands.parse("/goal 50 lakh") as Commands.Parsed.Prompt).text.contains("50 lakh"))
        assertTrue((Commands.parse("/fd 3y") as Commands.Parsed.Prompt).text.contains("3 years"))
        assertEquals(Commands.Parsed.Unknown("buy"), Commands.parse("/buy INFY"))   // no trading commands
        assertEquals(Commands.Parsed.Chart("INFRA", "1y"), Commands.parse("/chart @infra 1Y"))
        assertEquals(Commands.Parsed.Chart("TCS", null), Commands.parse("/chart tcs"))
        assertEquals(Commands.Parsed.Help, Commands.parse("/chart"))
        assertEquals(listOf("goal", "grow"), Commands.suggest("/g").map { it.name })
    }

    @Test fun mentions() {
        assertEquals("IN", Commands.typingMention("what if @IN"))
        assertNull(Commands.typingMention("what if @INFY goes up"))
        assertEquals("what if @INFRA ", Commands.completeMention("what if @IN", "INFRA"))
        assertEquals(listOf("INFRA", "TCS"), Commands.mentions("compare @infra and @TCS and @INFRA"))
        assertEquals(listOf("INFRA", "INDIGO", "BINFRA"), Commands.matching("in", listOf("TCS", "INFRA", "BINFRA", "INDIGO")))
    }
}

package com.khayali.pulao

import com.khayali.pulao.ai.Md
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun parsesTablesAsTablesNotPipes() {
        val b = Md.parse("Intro line.\n\n| Item | Value |\n| :--- | ---: |\n| Current | ₹3,62,500 |\n| P&L | +20.8% |\n\nAfter.")
        assertEquals(3, b.size)
        val t = b[1] as Md.Block.Table
        assertEquals(listOf("Item", "Value"), t.header)
        assertEquals(listOf(listOf("Current", "₹3,62,500"), listOf("P&L", "+20.8%")), t.rows)
    }

    @Test fun listsHeadingsParagraphs() {
        val b = Md.parse("## Summary\nLine one\nline two\n\n- a\n- **b**\n1. x\n2) y")
        assertEquals(Md.Block.Heading("Summary", 2), b[0])
        assertEquals(Md.Block.Para("Line one line two"), b[1])
        assertTrue(b[2] is Md.Block.Bullets)
        assertEquals(listOf("a", "**b**", "x", "y"), (b[2] as Md.Block.Bullets).items)
    }

    @Test fun inlineHidesMarkers() {
        assertEquals("bold and italic and code", Md.plain("**bold** and *italic* and `code`"))
        assertEquals("half", Md.plain("**half"))   // unfinished while streaming: no raw stars
    }
}

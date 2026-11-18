package com.khayali.pulao

import com.khayali.pulao.ai.Pronounce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The natural voice skips any word it doesn't know, so everything it's given must be words from its list. */
class PronounceTest {
    // a small stand-in for Kokoro's 178,401-word list (every word below is in the real one)
    private val known = setOf("the", "nifty", "rose", "today", "and", "led", "banks", "info", "sis", "sen", "sex", "rue", "peas", "rupee",
        "is", "up", "in", "a", "year", "your", "biggest", "holding", "worth", "crore", "lakh", "stock", "market", "mood", "of", "on", "trend", "check",
        "ball", "ia", "reliance", "say", "bee", "point", "percent", "minus", "plus", "november", "results", "q", "f", "y") +
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred", "thousand",
            "first", "second", "third", "fifth", "eighth", "ninth", "twelfth", "twentieth", "twenty-first", "oh") + ('a'..'z').map { it.toString() }
    private fun say(s: String) = Pronounce.prepare(s) { it in known }
    private fun allKnown(s: String) = s.split(Regex("""[\s,.;:!?()]+""")).filter { it.isNotEmpty() }.all { it.lowercase() in known }

    @Test fun numbersBecomeIndianWords() {
        assertEquals("one crore forty two lakh sixty four thousand eight hundred four", Pronounce.number("1,42,64,804"))
        assertEquals("seventeen point eight", Pronounce.number("17", ".8"))
        assertEquals("twenty twenty six", Pronounce.number("2026"))
        assertEquals("one thousand two hundred thirty four", Pronounce.number("1,234"))
        assertEquals("zero point three eight", Pronounce.number("0", ".38"))
    }

    @Test fun anAnswerIsAllSayable() {
        val out = say("The Nifty 50 rose 0.38% today; PSU banks led. Your biggest holding is worth ₹1,42,64,804 (+17.8%).")
        assertTrue(out, allKnown(out))
        assertTrue(out, out.contains("fifty") && out.contains("zero point three eight percent") && out.contains("eight hundred four rue peas") && out.contains("plus seventeen point eight percent"))
        assertEquals("two point five crore rue peas", say("Rs 2.5 crore"))
        assertTrue(out, out.contains("p s u"))
    }

    @Test fun marketWordsDatesAndAcronyms() {
        assertEquals("info sis", say("Infosys"))
        assertEquals("sen sex", say("Sensex"))
        assertEquals("say bee", say("SEBI"))
        assertEquals("seventeen November twenty twenty six", say("17 Nov 2026"))
        assertEquals("q two f y twenty six results", say("Q2 FY26 results").lowercase())
        assertEquals("minus two point three percent", say("-2.3%"))
        assertEquals("twenty first", say("21st"))
        assertEquals("check i n f y trend", say("Check INFY's trend").lowercase())
        assertEquals("ball ia", say("ballia"))
        assertFalse(say("RELIANCE up").contains("r e l"))
    }
}

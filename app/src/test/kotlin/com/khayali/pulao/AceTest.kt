package com.khayali.pulao

import com.khayali.pulao.ai.Ace
import com.khayali.pulao.ai.Ace.Delta
import com.khayali.pulao.ai.Ace.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memory playbook: generate (select) → reflect (rules, model, lessons) → curate (merge, refuse, evict). */
class AceTest {
    private fun pb(vararg d: Delta) = Ace.curate(Ace.Playbook(), d.toList(), now = 1)

    @Test fun newNotesAreAppendedAsSmallBullets() {
        val p = pb(Delta(Section.FACT, "Goal: ₹1 crore by 2035."), Delta(Section.PREF, "Likes short answers."))
        assertEquals(2, p.bullets.size)
        assertEquals(listOf(1, 2), p.bullets.map { it.id })
        assertEquals(3, p.nextId)
    }

    @Test fun restatingANoteStrengthensItInsteadOfDuplicating() {
        var p = pb(Delta(Section.FACT, "Invests for a 10 year horizon."))
        p = Ace.curate(p, listOf(Delta(Section.FACT, "Invests for a ten to 10 year horizon")), now = 5)
        assertEquals(1, p.bullets.size)
        assertEquals(2, p.bullets[0].helpful)
        assertEquals(5, p.bullets[0].used)
        // same words in another section is a different note
        p = Ace.curate(p, listOf(Delta(Section.LESSON, "Invests for a 10 year horizon.")), now = 6)
        assertEquals(2, p.bullets.size)
    }

    @Test fun secretsIdentifiersAndAdviceAreNeverStored() {
        for (bad in listOf("My Kite password is hunter2 okay", "PAN number is ABCDE1234F", "Phone 9876543210 for alerts", "Reach me at someone@example.com",
            "User should buy INFRA at dips", "Target price for INFRA is high", "OTP for login is needed", "short"))
            assertFalse(bad, Ace.acceptable(bad))
        assertTrue(Ace.acceptable("Wants to retire by 50 and travel."))
        assertEquals(0, pb(Delta(Section.FACT, "My account number is 123456789012")).bullets.size)
    }

    @Test fun overTheCapTheWeakestOldestGo() {
        var p = Ace.Playbook()
        // distinct notes (no shared words) so none merge
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel")
        words.forEachIndexed { i, w -> p = Ace.curate(p, listOf(Delta(Section.FACT, "Note about $w $w$w topic.")), now = i.toLong(), cap = 5) }
        assertEquals(5, p.bullets.size)
        assertTrue(p.bullets.none { it.text.contains("alpha") })           // the oldest of the equally-weak ones went first
        // a proven note survives even when old
        var q = Ace.curate(Ace.Playbook(), listOf(Delta(Section.FACT, "Note about alpha alphaalpha topic.")), 0, cap = 3)
        q = Ace.feedback(q, listOf(1), good = true, now = 0)
        listOf("bravo", "charlie", "delta", "echo").forEachIndexed { i, w -> q = Ace.curate(q, listOf(Delta(Section.FACT, "Note about $w $w$w topic.")), now = 10L + i, cap = 3) }
        assertTrue(q.bullets.any { it.text.contains("alpha") })
    }

    @Test fun feedbackMovesCountsAndRetiresBadNotes() {
        var p = pb(Delta(Section.PREF, "Likes detailed answers."))
        p = Ace.feedback(p, listOf(1), good = true, now = 2)
        assertEquals(2, p.bullets[0].helpful)
        repeat(4) { p = Ace.feedback(p, listOf(1), good = false, now = 3) }
        assertEquals(1, p.bullets.size); assertEquals(4, p.bullets[0].harmful)
        p = Ace.feedback(p, listOf(1), good = false, now = 4)             // harmful ≥ helpful + 3 → retired
        assertEquals(0, p.bullets.size)
    }

    @Test fun selectionPrefersProvenAndRelevantNotes() {
        var p = pb(Delta(Section.FACT, "Goal: retire by 50."), Delta(Section.LESSON, "Pass the percentage exactly as typed."),
            Delta(Section.PREF, "Likes short answers."), Delta(Section.FACT, "Saves through a monthly SIP."))
        repeat(2) { p = Ace.feedback(p, listOf(2), good = true, now = 2) }
        val pick = Ace.select(p, "how is my SIP doing", max = 2)
        assertEquals(listOf(4, 2), pick.map { it.id })                     // the SIP fact (relevant), then the proven lesson
        // a note that keeps leading to redos is no longer shown
        val sour = Ace.feedback(Ace.feedback(p, listOf(3), false, 3), listOf(3), false, 3)
        assertTrue(Ace.select(sour, "anything", max = 10).none { it.id == 3 })
        val text = Ace.prompt(Ace.select(p, "sip", 6))
        assertTrue(text, text.contains("What you remember about this user") && text.contains("Lessons from earlier answers") && text.contains("- Saves through a monthly SIP."))
        assertEquals("", Ace.prompt(emptyList()))
    }

    @Test fun reflectionByRules() {
        assertEquals(listOf(Delta(Section.FACT, "Goal: 1 crore by 2035.")), Ace.reflectRules("My goal is 1 crore by 2035. What will it take?"))
        assertEquals(listOf(Delta(Section.FACT, "Is 34 years old.")), Ace.reflectRules("I'm 34 years old, how much should I have saved?"))
        assertEquals(listOf(Delta(Section.PREF, "Prefers index funds over single stocks.")), Ace.reflectRules("i prefer index funds over single stocks"))
        assertEquals(listOf(Delta(Section.FACT, "Their horizon is 15 years.")), Ace.reflectRules("Remember that my horizon is 15 years"))
        assertEquals(emptyList<Delta>(), Ace.reflectRules("What if INFRA falls 20%?"))
        assertTrue(Ace.worthReflecting("I want to retire at 50 with two crore"))
        assertFalse(Ace.worthReflecting("What if INFRA falls 20%?"))
        assertFalse(Ace.worthReflecting("/remember I want to retire at 50"))      // commands are handled directly
    }

    @Test fun reflectionByTheModelIsParsedStrictly() {
        val d = Ace.parseReflection("FACT: Wants to retire at 50\n- PREF: likes tables\nHere is some chatter\nLESSON - be brief\nFACT: four\nFACT: five")
        assertEquals(listOf(Delta(Section.FACT, "Wants to retire at 50."), Delta(Section.PREF, "likes tables."), Delta(Section.LESSON, "be brief.")), d)
        assertEquals(emptyList<Delta>(), Ace.parseReflection("NONE"))
        assertEquals(0, pb(*Ace.parseReflection("FACT: NONE").toTypedArray()).bullets.size)
    }

    @Test fun lessonsComeFromWhatActuallyWentWrong() {
        assertEquals(emptyList<Delta>(), Ace.lessons(Ace.Signals()))
        val l = Ace.lessons(Ace.Signals(percentCorrected = true, toolErrors = listOf("No stock selected — ask the user"), ungroundedWebNumbers = true))
        assertEquals(3, l.size)
        assertTrue(l.all { it.section == Section.LESSON && Ace.acceptable(it.text) })
        // the same mistake twice raises one bullet's count rather than adding a second
        val p = Ace.curate(Ace.curate(Ace.Playbook(), l, 1), l, 2)
        assertEquals(3, p.bullets.size); assertTrue(p.bullets.all { it.helpful == 2 })
    }

    @Test fun survivesSaveAndLoad() {
        val p = Ace.feedback(pb(Delta(Section.FACT, "Goal: ₹1 crore by 2035."), Delta(Section.LESSON, "Cite web figures like [1].")), listOf(2), false, 9)
        assertEquals(p, Ace.fromJson(Ace.toJson(p)))
        assertEquals(Ace.Playbook(), Ace.fromJson("not json")); assertEquals(Ace.Playbook(), Ace.fromJson(null))
    }
}

package com.khayali.pulao

import com.khayali.pulao.ai.VoiceFlow
import com.khayali.pulao.ai.VoiceFlow.Ev
import com.khayali.pulao.ai.VoiceFlow.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFlowTest {
    @Test fun aConversationLoops() {
        var p = Phase.IDLE
        p = VoiceFlow.next(p, Ev.Start); assertEquals(Phase.LISTENING, p)
        assertEquals("silence keeps listening", Phase.LISTENING, VoiceFlow.next(p, Ev.Heard("  ")))
        p = VoiceFlow.next(p, Ev.Heard("what should I do today")); assertEquals(Phase.THINKING, p)
        p = VoiceFlow.next(p, Ev.Answer); assertEquals(Phase.SPEAKING, p)
        p = VoiceFlow.next(p, Ev.Spoken); assertEquals(Phase.LISTENING, p)
        assertEquals(Phase.LISTENING, VoiceFlow.next(Phase.SPEAKING, Ev.Interrupt))
        assertEquals(Phase.ERROR, VoiceFlow.next(Phase.LISTENING, Ev.Failed("x")))
        assertEquals(Phase.LISTENING, VoiceFlow.next(Phase.ERROR, Ev.Start))
        p = VoiceFlow.next(p, Ev.End); assertEquals(Phase.ENDED, p)
        assertEquals("nothing restarts an ended session", Phase.ENDED, VoiceFlow.next(p, Ev.Start))
        assertEquals("a late answer doesn't speak", Phase.LISTENING, VoiceFlow.next(Phase.LISTENING, Ev.Answer))
    }

    @Test fun answersAreSpokenCleanly() {
        val s = VoiceFlow.speakable("""**Your plan for today** — 4 of 4 steps done.
- **Market:** recovering — 58% of stocks above their 50-day average [1].
- [🩺 Check BALLIA's trend](cmd:/health BALLIA)
| a | b |
PSU Bank −2.3% at ₹1,168""")
        assertFalse(s, s.contains("**") || s.contains("cmd:") || s.contains("[1]") || s.contains("|") || s.contains("🩺"))
        assertTrue(s, s.contains("Check BALLIA's trend") && s.contains("58 percent") && s.contains("minus 2.3 percent") && s.contains("rupees 1,168"))
        val long = VoiceFlow.speakable("This is a sentence. ".repeat(100), 200)
        assertTrue(long.length < 260 && long.endsWith("The rest is in the chat."))
    }

    @Test fun handsFreeKeepsGoingAndPausesOnlyWhenAsked() {
        assertEquals(Phase.IDLE, VoiceFlow.next(Phase.LISTENING, Ev.Pause))
        assertEquals(Phase.IDLE, VoiceFlow.next(Phase.SPEAKING, Ev.Pause))
        assertEquals(Phase.ENDED, VoiceFlow.next(Phase.ENDED, Ev.Pause))
        assertEquals("a paused session picks up again", Phase.LISTENING, VoiceFlow.next(Phase.IDLE, Ev.Start))
        assertEquals("Talk or tap to interrupt", VoiceFlow.hint(Phase.SPEAKING))
        assertTrue(VoiceFlow.QUIET_PAUSE_MS >= 60_000)
    }

    @Test fun talkingOverTheAnswerIsNoticedButItsOwnVoiceIsNot() {
        val echo = listOf(0.03, 0.05, 0.06, 0.04, 0.07, 0.05, 0.02, 0.06, 0.05, 0.04)      // the phone hearing itself
        val t = VoiceFlow.TalkOver()
        repeat(2) { echo.forEach { assertFalse("calibrating", t.feed(it)) } }
        assertEquals("90th percentile of what it heard × 2.8", 0.07 * 2.8, t.threshold, 1e-9)
        repeat(30) { echo.forEach { assertFalse("its own voice, a little louder, never interrupts", t.feed(it * 1.5)) } }
        listOf(0.3, 0.3, 0.3).forEach { assertFalse("a cough isn't talking", t.feed(it)) }
        repeat(10) { assertFalse(t.feed(0.04)) }
        var heard = false
        repeat(10) { heard = t.feed(0.25) || heard }
        assertTrue("you talking over it is noticed within 0.3 s", heard)
        val quiet = VoiceFlow.TalkOver(); repeat(20) { quiet.feed(0.001) }
        assertEquals("headphones / a silent echo: the floor applies", 0.012, quiet.threshold, 1e-12)
    }

    @Test fun theCaptionShowsTheSentenceBeingRead() {
        val text = "First sentence here. The second sentence is the one being read now. Third."
        assertEquals("The second sentence is the one being read now.", text.substring(VoiceFlow.window(text, text.indexOf("being"))))
        assertEquals("First sentence here.", text.substring(VoiceFlow.window(text, 2)))
        val long = (1..60).joinToString(" ") { "word$it" } + "."
        val at = long.indexOf("word45")
        val w = VoiceFlow.window(long, at)
        assertTrue(w.first <= at && w.last >= at + 5 && w.count() <= 175)
        assertTrue(VoiceFlow.window("", 0).isEmpty())
    }
}

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
}

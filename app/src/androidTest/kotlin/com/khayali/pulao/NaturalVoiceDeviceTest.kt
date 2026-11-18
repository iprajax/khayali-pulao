package com.khayali.pulao

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.khayali.pulao.ai.NeuralVoice
import com.khayali.pulao.ai.data.VoicePack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** On the phone, with the voice files in place: the engine loads, every voice speaks, and it's faster than real time. No sound is played. */
@RunWith(AndroidJUnit4::class)
class NaturalVoiceDeviceTest {
    @Test fun threadsVsSpeed() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(VoicePack.ready(ctx))
        val line = "The Nifty 50 rose 0.38% today, and PSU banks led; your biggest holding is up 17.8% this year."
        for ((n, p) in listOf(6 to "cpu", 8 to "cpu", 4 to "xnnpack", 4 to "nnapi")) {
            NeuralVoice.reloadForCheck(n, p)
            if (NeuralVoice.synthesizeForCheck(ctx, "Ready.") == null) { Log.i("KPVoiceCheck", "$p: not available"); continue }
            val best = (1..2).minOf { val t = System.nanoTime(); val (pcm, rate) = NeuralVoice.synthesizeForCheck(ctx, line)!!; ((System.nanoTime() - t) / 1e9) / (pcm.size.toDouble() / rate) }
            Log.i("KPVoiceCheck", "$p, threads $n: real-time factor ${"%.2f".format(best)}")
        }
    }

    @Test fun kokoroSpeaksOnThePhone() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("voice files not on the phone", VoicePack.ready(ctx))
        val t0 = System.nanoTime()
        val warm = NeuralVoice.synthesizeForCheck(ctx, "Ready.")
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("engine loads", warm != null && warm.first.isNotEmpty())
        assertEquals("54 Kokoro v1.0 voices (ids below assume name order)", 54, NeuralVoice.speakersForCheck())
        val line = "The Nifty 50 rose 0.38% today, and PSU banks led; your biggest holding is up 17.8% this year."
        for (v in NeuralVoice.VOICES) {
            val t1 = System.nanoTime()
            val (pcm, rate) = NeuralVoice.synthesizeForCheck(ctx, line, v.sid)!!
            val ms = (System.nanoTime() - t1) / 1_000_000
            val secs = pcm.size.toDouble() / rate
            val peak = pcm.maxOf { kotlin.math.abs(it) }
            Log.i("KPVoiceCheck", "${v.name} (sid ${v.sid}): ${"%.1f".format(secs)} s of audio in $ms ms — real-time factor ${"%.2f".format(ms / 1000.0 / secs)}, peak ${"%.2f".format(peak)}")
            assertTrue("${v.name}: a full sentence of audio", secs in 4.0..14.0)
            assertTrue("${v.name}: not silent", peak > 0.05)
            assertTrue("${v.name}: faster than real time", ms / 1000.0 < secs)
        }
        Log.i("KPVoiceCheck", "loaded in $loadMs ms")
    }
}

package com.khayali.pulao.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/**
 * Reads an answer aloud (🔊 under each reply) with the best voice on the phone — never a network voice.
 * One answer at a time: starting another stops the first.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var owner: Any? = null
    private var onEnd: (() -> Unit)? = null

    fun speakingFor(o: Any) = owner === o

    fun speak(ctx: Context, text: String, owner: Any, onEnd: () -> Unit) {
        stop()
        this.owner = owner; this.onEnd = onEnd
        // the natural voice when it's downloaded and chosen; otherwise the phone's own offline voice
        if (NeuralVoice.speak(ctx, "kp-read", text, object : NeuralVoice.Listener {
                override fun onStart(id: String) = Unit
                override fun onRange(id: String, start: Int, end: Int) = Unit
                override fun onDone(id: String) = finish()
            })) return
        val t = tts
        if (t != null && ready) { say(t, text); return }
        tts = TextToSpeech(ctx.applicationContext, { s ->
            val e = tts
            ready = s == TextToSpeech.SUCCESS && e != null && pickOffline(e)
            if (ready && e != null) say(e, text) else finish()
        }, "com.google.android.tts")
    }

    fun stop() { NeuralVoice.stop(); tts?.stop(); finish() }

    private fun say(t: TextToSpeech, text: String) {
        if (t.voice?.isNetworkConnectionRequired != false) { finish(); return }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) = finish()
            @Deprecated("Deprecated in Java") override fun onError(id: String?) = finish()
            override fun onStop(id: String?, interrupted: Boolean) = Unit
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kp-read")
    }

    private fun finish() { val e = onEnd; onEnd = null; owner = null; e?.invoke() }

    /** An offline voice only (no network synthesis): Indian English first, then the highest quality English voice. */
    fun pickOffline(t: TextToSpeech): Boolean {
        val voices = runCatching { t.voices }.getOrNull().orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == "en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val v = voices.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "IN" }.thenByDescending { it.quality }).firstOrNull() ?: return false
        t.voice = v; t.setSpeechRate(1.05f)
        return true
    }
}

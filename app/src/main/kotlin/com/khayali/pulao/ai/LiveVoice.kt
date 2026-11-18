package com.khayali.pulao.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import java.util.Locale
import kotlin.math.sin

/**
 * Live voice, Gemini-Live style, entirely on the phone: speak, watch your words appear as live captions,
 * hear the answer read back with each word lit as it's spoken, tap the orb to interrupt, ✕ to end.
 *
 * Listening uses Android's on-device recognizer only (SpeechRecognizer.createOnDeviceSpeechRecognizer —
 * there is no cloud fallback: if on-device recognition isn't available, voice says so and stays off).
 * Speaking uses an offline text-to-speech voice. The words go into the same chat as typing, so commands,
 * plans and cards all work by voice.
 */
@SuppressLint("ViewConstructor")
class LiveVoice(
    ctx: Context, private val k: Kit,
    /** Sends what was heard to the chat; the chat calls [answer] with the reply. */
    private val ask: (String) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(ctx) {

    private var phase = VoiceFlow.Phase.IDLE
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var spoken = ""
    private val white = 0xFFFFFFFF.toInt()

    private val orb = Orb(ctx)
    private val you = k.text("", 22f, Pal.alpha(white, 0.95f), 400).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) }
    private val reply = k.text("", 19f, Pal.alpha(white, 0.55f), 400).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.35f) }
    private val hint = k.text("", 13f, Pal.alpha(white, 0.55f), 500).apply { gravity = Gravity.CENTER; letterSpacing = 0.03f }
    private val privacy = k.text("🔒 On-device · nothing leaves your phone", 11.5f, Pal.alpha(white, 0.45f)).apply { gravity = Gravity.CENTER }

    init {
        background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFC0B1017.toInt(), 0xFD101826.toInt(), 0xFF0B1017.toInt()))
        isClickable = true
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(k.dp(28), k.dp(56), k.dp(28), k.dp(28)) }
        val top = k.row().apply {
            addView(k.text("Live", 15f, Pal.alpha(white, 0.9f), 600), k.lp(0, WRAP_CONTENT, 1f))
            addView(k.text("✕", 20f, Pal.alpha(white, 0.85f), 400).apply {
                gravity = Gravity.CENTER; background = k.rounded(Pal.alpha(white, 0.08f), 999f); contentDescription = "End live voice"
                setOnClickListener { end() }
            }, k.lp(k.dp(44), k.dp(44)))
        }
        col.addView(top, k.lp(MATCH_PARENT))
        col.addView(View(ctx), k.lp(MATCH_PARENT, 0, 0.6f))
        col.addView(you, k.lp(MATCH_PARENT))
        col.addView(k.space(14))
        col.addView(reply, k.lp(MATCH_PARENT))
        col.addView(View(ctx), k.lp(MATCH_PARENT, 0, 0.4f))
        col.addView(orb.apply { setOnClickListener { tapOrb() }; contentDescription = "Talk" }, LinearLayout.LayoutParams(k.dp(170), k.dp(170)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        col.addView(k.space(10))
        col.addView(hint, k.lp(MATCH_PARENT))
        col.addView(k.space(14))
        col.addView(privacy, k.lp(MATCH_PARENT))
        addView(col, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        alpha = 0f
    }

    // ------------------------------------------------------------------ life cycle

    fun open() {
        animate().alpha(1f).setDuration(220).start()
        if (!available(context)) {
            to(VoiceFlow.Phase.ERROR)
            you.text = "Voice needs Android's on-device speech recognition"
            reply.text = if (Build.VERSION.SDK_INT < 31) "It needs Android 12 or newer." else "It isn't available on this phone. Voice stays off rather than sending your audio anywhere."
            return
        }
        tts = TextToSpeech(context.applicationContext, { status -> if (status == TextToSpeech.SUCCESS) { ttsReady = pickOfflineVoice() } }, "com.google.android.tts")
        if (Build.VERSION.SDK_INT >= 31) recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        pickLanguage { listen() }
    }

    fun end() {
        to(VoiceFlow.Phase.ENDED)
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
        animate().alpha(0f).setDuration(180).withEndAction { onClose() }.start()
    }

    private fun to(p: VoiceFlow.Phase) { phase = p; hint.text = VoiceFlow.hint(p); orb.mode = p }

    private fun tapOrb() {
        when (phase) {
            VoiceFlow.Phase.SPEAKING, VoiceFlow.Phase.THINKING -> { tts?.stop(); resetSpeech(); phase = VoiceFlow.next(phase, VoiceFlow.Ev.Interrupt); listen() }
            VoiceFlow.Phase.ERROR, VoiceFlow.Phase.IDLE -> if (available(context)) listen()
            VoiceFlow.Phase.LISTENING -> recognizer?.stopListening()          // "I'm done talking"
            VoiceFlow.Phase.ENDED -> Unit
        }
    }

    // ------------------------------------------------------------------ listening

    private fun listen() {
        if (phase == VoiceFlow.Phase.ENDED) return
        to(VoiceFlow.next(phase, VoiceFlow.Ev.Start))
        you.text = ""; you.setTextColor(Pal.alpha(white, 0.95f))
        if (Build.VERSION.SDK_INT < 31) return
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { orb.level = 0f }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { orb.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { orb.level = 0f }
            override fun onPartialResults(b: Bundle?) { first(b)?.let { you.text = it } }
            override fun onResults(b: Bundle?) {
                val text = first(b).orEmpty().trim()
                if (text.isEmpty()) { listen(); return }                     // silence: keep listening
                backoff = 250L
                performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                you.text = text
                phase = VoiceFlow.next(phase, VoiceFlow.Ev.Heard(text)); to(phase)
                reply.text = ""; resetSpeech()
                ask(text)
            }
            override fun onError(error: Int) {
                if (phase == VoiceFlow.Phase.ENDED) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (phase == VoiceFlow.Phase.LISTENING) { to(VoiceFlow.Phase.IDLE); hint.text = "Tap the orb to talk" }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Microphone permission is off")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        fail("The on-device English speech pack isn't installed. Android can add it in Settings → System → Languages → Speech.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> { recognizer?.cancel(); backoff = (backoff * 2).coerceIn(250L, 2000L); postDelayed({ listen() }, backoff) }
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> if (!rebuilt) { rebuilt = true; recognizer?.destroy(); recognizer = null; postDelayed({ listen() }, 300) } else fail("The speech service stopped. Tap the orb to try again.")
                    SpeechRecognizer.ERROR_AUDIO -> fail("The microphone is blocked. Turn on mic access in quick settings.")
                    // network errors would mean a cloud recognizer — never retried another way
                    else -> fail("Couldn't hear that (code $error)")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            if (Build.VERSION.SDK_INT >= 33) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_LATENCY)
                putExtra(RecognizerIntent.EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION, true)
                // market words and the user's own stocks are recognised more reliably
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(BIAS + bias().take(40)))
            }
        })
    }

    private var backoff = 250L
    private var rebuilt = false
    private var language = "en-IN"
    /** The user's stocks and words to help the recognizer (set by the chat). */
    var bias: () -> List<String> = { emptyList() }
    private val BIAS = listOf("Nifty", "Sensex", "Bank Nifty", "Khayali Pulao", "SEBI", "FII", "DII", "IPO", "GMP", "brief", "strategies", "sector", "pulse", "mood", "filings", "holdings")

    /** The installed on-device English pack: Indian English first, then British, then US (API 33+). */
    private fun pickLanguage(then: () -> Unit) {
        val r = recognizer
        if (Build.VERSION.SDK_INT < 33 || r == null) { then(); return }
        runCatching {
            r.checkRecognitionSupport(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), context.mainExecutor, object : android.speech.RecognitionSupportCallback {
                override fun onSupportResult(s: android.speech.RecognitionSupport) {
                    val have = s.installedOnDeviceLanguages
                    language = listOf("en-IN", "en-GB", "en-US").firstOrNull { it in have } ?: have.firstOrNull { it.startsWith("en") } ?: "en-IN"
                    if (have.none { it.startsWith("en") }) r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN"))
                    post(then)
                }
                override fun onError(error: Int) { post(then) }
            })
        }.onFailure { then() }
    }

    private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    private fun fail(msg: String) { to(VoiceFlow.next(phase, VoiceFlow.Ev.Failed(msg))); reply.text = msg; reply.setTextColor(Pal.alpha(white, 0.75f)) }

    // ------------------------------------------------------------------ speaking

    // what has been queued for speaking: raw answer text consumed so far, the spoken caption, and utterances in flight
    private var consumed = 0
    private var finalSeen = false
    private var inFlight = 0
    private var halted = false

    private fun resetSpeech() { consumed = 0; finalSeen = false; inFlight = 0; halted = false; spoken = "" }

    /** Speaks [text] after what's already queued; captions grow with it and light up word by word. */
    private fun enqueue(text: String): Boolean {
        val t = tts ?: return false
        if (!ttsReady || t.voice?.isNetworkConnectionRequired != false) return false            // offline voice only, re-checked every time
        if (phase != VoiceFlow.Phase.SPEAKING) { phase = VoiceFlow.next(phase, VoiceFlow.Ev.Answer); to(phase); orb.speaking(); you.setTextColor(Pal.alpha(white, 0.5f)); reply.setTextColor(Pal.alpha(white, 0.55f)) }
        val offset = if (spoken.isEmpty()) 0 else spoken.length + 1
        spoken = if (spoken.isEmpty()) text else "$spoken $text"
        reply.text = SpannableString(spoken).also { lit(it, 0, offset) }
        inFlight++
        t.setOnUtteranceProgressListener(listener)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "kp-$offset")
        return true
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) = Unit
        override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { val o = id?.removePrefix("kp-")?.toIntOrNull() ?: 0; post { highlight(o + start, o + end) } }
        // a short pause before listening again, so the phone doesn't hear the end of its own voice
        override fun onDone(id: String?) { post { inFlight = (inFlight - 1).coerceAtLeast(0); if (inFlight == 0 && finalSeen) finishSpeaking() } }
        @Deprecated("Deprecated in Java") override fun onError(id: String?) { post { inFlight = (inFlight - 1).coerceAtLeast(0); if (inFlight == 0 && finalSeen) finishSpeaking() } }
        override fun onError(id: String?, code: Int) { post { inFlight = (inFlight - 1).coerceAtLeast(0); if (inFlight == 0 && finalSeen) finishSpeaking() } }
    }

    private fun finishSpeaking() {
        if (phase != VoiceFlow.Phase.SPEAKING) return
        reply.text = SpannableString(spoken).also { lit(it, 0, spoken.length) }
        phase = VoiceFlow.next(phase, VoiceFlow.Ev.Spoken); postDelayed({ listen() }, 300)
    }

    /**
     * The model's answer as it streams: whole sentences are spoken as soon as they're complete, so the first words
     * come long before the answer is finished. A sentence with a figure waits — figures are spoken only from the
     * final, checked answer.
     */
    fun stream(md: String) {
        if (phase == VoiceFlow.Phase.ENDED || finalSeen || halted || md.length <= consumed) return
        val rest = md.substring(consumed)
        val ends = Regex("""(?<=[.!?])\s+|\n+""").findAll(rest).map { it.range.last + 1 }.toList()
        var at = 0
        for (e in ends) {
            val sentence = rest.substring(at, e).trim()
            if (sentence.any { it.isDigit() }) { halted = true; break }
            val say = VoiceFlow.speakable(sentence, 400)
            if (say.isNotBlank() && !enqueue(say)) { halted = true; break }
            at = e
        }
        consumed += at
    }

    /** The chat's final reply (markdown or a card's summary): speak whatever wasn't spoken yet, then listen again. */
    fun answer(md: String) {
        if (phase == VoiceFlow.Phase.ENDED) return
        finalSeen = true
        val already = consumed > 0 && md.length >= consumed
        val rest = if (already) md.substring(consumed) else md
        if (consumed > 0 && !already) { tts?.stop(); spoken = ""; inFlight = 0 }           // the checked answer differs from what streamed: say it whole
        val text = VoiceFlow.speakable(rest)
        if (text.isNotBlank() && enqueue(text)) return
        if (inFlight == 0) {
            if (spoken.isEmpty()) { reply.text = VoiceFlow.speakable(md); phase = VoiceFlow.Phase.LISTENING; postDelayed({ listen() }, 1200) } else finishSpeaking()
        }
    }

    /** Words already spoken are bright; the current word glows; the rest wait, dim. */
    private fun highlight(start: Int, end: Int) {
        if (phase != VoiceFlow.Phase.SPEAKING || spoken.isEmpty()) return
        val s = SpannableString(spoken)
        lit(s, 0, end.coerceAtMost(spoken.length))
        s.setSpan(ForegroundColorSpan(0xFF9FC1FF.toInt()), start.coerceIn(0, spoken.length), end.coerceIn(0, spoken.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        reply.text = s
    }
    private fun lit(s: SpannableString, a: Int, b: Int) { if (b > a) s.setSpan(ForegroundColorSpan(Pal.alpha(white, 0.95f)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    /** An offline voice only (no network synthesis), Indian English first. */
    private fun pickOfflineVoice(): Boolean {
        val t = tts ?: return false
        val voices = runCatching { t.voices }.getOrNull().orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == "en" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
        val v = voices.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale.country == "IN" }.thenByDescending { it.quality }).firstOrNull() ?: return false
        t.voice = v; t.setSpeechRate(1.05f)
        return true
    }

    companion object {
        /** On-device recognition only. */
        fun available(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    }

    // ------------------------------------------------------------------ the orb

    /** A soft glowing orb: breathes while idle, swells with your voice, ripples while thinking, pulses while speaking. */
    private inner class Orb(ctx: Context) : View(ctx) {
        var level = 0f
        var mode = VoiceFlow.Phase.IDLE
        private var shown = 0f
        private var speakPulse = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val t0 = System.nanoTime()
        fun speaking() { speakPulse = 1f }

        override fun onDraw(c: Canvas) {
            val t = (System.nanoTime() - t0) / 1e9f
            shown += (level - shown) * 0.25f
            if (mode == VoiceFlow.Phase.SPEAKING) speakPulse = 0.5f + 0.5f * sin(t * 7f) * sin(t * 2.3f).let { kotlin.math.abs(it) }
            val cx = width / 2f; val cy = height / 2f; val base = width * 0.27f
            val swell = when (mode) {
                VoiceFlow.Phase.LISTENING -> shown * 0.35f + 0.03f * sin(t * 2f)
                VoiceFlow.Phase.SPEAKING -> speakPulse * 0.14f
                VoiceFlow.Phase.THINKING -> 0.04f * sin(t * 3f)
                else -> 0.03f * sin(t * 1.4f)
            }
            val r = base * (1f + swell)
            val (a, b) = when (mode) {
                VoiceFlow.Phase.LISTENING -> 0xFF5282EB.toInt() to 0xFF8E7CF0.toInt()
                VoiceFlow.Phase.THINKING -> 0xFF8E7CF0.toInt() to 0xFFE8708A.toInt()
                VoiceFlow.Phase.SPEAKING -> 0xFF67AD5B.toInt() to 0xFF5282EB.toInt()
                VoiceFlow.Phase.ERROR -> 0xFFCF5B52.toInt() to 0xFF8E7CF0.toInt()
                else -> 0xFF5282EB.toInt() to 0xFF324050.toInt()
            }
            // outer glow
            p.shader = RadialGradient(cx, cy, r * 1.9f, intArrayOf(Pal.alpha(a, 0.35f), Pal.alpha(b, 0.12f), 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 1.9f, p)
            // body, with a slowly turning highlight
            val hx = cx + r * 0.35f * sin(t * 0.8f); val hy = cy - r * 0.35f * kotlin.math.cos(t * 0.8f)
            p.shader = RadialGradient(hx, hy, r * 1.6f, intArrayOf(Pal.alpha(0xFFFFFFFF.toInt(), 0.9f), a, b), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            // thinking: two travelling ripples
            if (mode == VoiceFlow.Phase.THINKING) for (i in 0..1) {
                val ph = ((t * 0.7f + i * 0.5f) % 1f)
                ring.strokeWidth = k.dpf(1.5f); ring.color = Pal.alpha(0xFFFFFFFF.toInt(), 0.35f * (1 - ph))
                c.drawCircle(cx, cy, r * (1.05f + ph * 0.7f), ring)
            }
            if (mode != VoiceFlow.Phase.ENDED) postInvalidateOnAnimation()
        }
    }
}

package com.khayali.pulao.ai

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.khayali.pulao.Kit
import com.khayali.pulao.Pal
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hands-free voice, inside the chat — the way ChatGPT's voice mode works now. A compact dock takes the input
 * bar's place; the conversation stays on screen. What you say goes into the chat as your message; the answer
 * appears there as usual (cards and all) while it's read aloud, sentence by sentence, with the spoken word lit
 * in the dock's caption. Then it listens again by itself. Talk over it (or tap the orb) to interrupt; mute or
 * End any time. Leaving the app pauses it; three quiet minutes pause it too.
 *
 * Everything stays on the phone: listening uses Android's on-device recognizer only
 * (SpeechRecognizer.createOnDeviceSpeechRecognizer — no cloud fallback); speaking uses an offline voice.
 * While an answer plays, the phone measures only how loud the microphone is (after echo cancelling) to notice
 * you talking over it — no words are recognised and no sound is kept.
 */
@SuppressLint("ViewConstructor")
class LiveVoice(
    ctx: Context, private val k: Kit,
    /** Sends what was heard to the chat; the chat calls [stream] and [answer] with the reply. */
    private val ask: (String) -> Unit,
    /** You interrupted: the rest of the current reply shouldn't be spoken (it stays in the chat). */
    private val onInterrupt: () -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(ctx) {

    private var phase = VoiceFlow.Phase.IDLE
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var spoken = ""
    private var muted = false
    private var heldByApp = false
    private var lastHeardAt = SystemClock.elapsedRealtime()
    private val white = 0xFFFFFFFF.toInt()
    private val ear = Ear()

    private val orb = Orb(ctx)
    private val status = k.text("", 12.5f, Pal.alpha(white, 0.6f), 500).apply { letterSpacing = 0.02f; maxLines = 1 }
    private val cap = k.text("", 17f, Pal.alpha(white, 0.95f), 400).apply { setLineSpacing(0f, 1.25f); maxLines = 3; minHeight = k.dp(4) }
    private val slash = object : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8708A.toInt(); strokeWidth = k.dpf(2f); strokeCap = Paint.Cap.ROUND }
        override fun onDraw(c: Canvas) { c.drawLine(width * 0.28f, height * 0.26f, width * 0.72f, height * 0.74f, p) }
    }
    private val muteBtn = FrameLayout(ctx)

    init {
        orientation = VERTICAL
        keepScreenOn = true                                                  // hands-free: the screen stays on while voice is open
        isClickable = true
        val card = LinearLayout(ctx).apply {
            orientation = VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2172030.toInt(), 0xF2101722.toInt())).apply {
                cornerRadius = k.dpf(26f); setStroke(k.dp(1), Pal.alpha(white, 0.08f)) }
            setPadding(k.dp(18), k.dp(12), k.dp(14), k.dp(10))
        }
        card.addView(k.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(status, k.lp(0, WRAP_CONTENT, 1f))
            addView(k.text("🔒 on this phone", 11f, Pal.alpha(white, 0.4f)), k.lp(WRAP_CONTENT))
        }, k.lp(MATCH_PARENT))
        card.addView(cap, k.lp(MATCH_PARENT).apply { topMargin = k.dp(4) })
        // controls: mute · orb · End — the orb sits dead centre
        val controls = k.row().apply { gravity = Gravity.CENTER_VERTICAL }
        muteBtn.apply {
            background = k.press(k.rounded(Pal.alpha(white, 0.07f), 999f), 999f); contentDescription = "Mute the microphone"
            addView(TickerWheel.LineIcon(ctx, "mic", k), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(slash.apply { visibility = GONE }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            setOnClickListener { toggleMute() }
        }
        controls.addView(FrameLayout(ctx).apply { addView(muteBtn, FrameLayout.LayoutParams(k.dp(44), k.dp(44), Gravity.START or Gravity.CENTER_VERTICAL)) }, k.lp(0, k.dp(76), 1f))
        controls.addView(orb.apply { setOnClickListener { tapOrb() }; contentDescription = "Talk, or interrupt" }, k.lp(k.dp(76), k.dp(76)))
        val end = k.text("End", 14.5f, Pal.alpha(white, 0.92f), 600).apply {
            gravity = Gravity.CENTER; setPadding(k.dp(18), 0, k.dp(18), 0)
            background = k.press(k.rounded(Pal.alpha(0xFFE8708A.toInt(), 0.16f), 999f), 999f); contentDescription = "End voice"
            setOnClickListener { end() }
        }
        controls.addView(FrameLayout(ctx).apply { addView(end, FrameLayout.LayoutParams(WRAP_CONTENT, k.dp(40), Gravity.END or Gravity.CENTER_VERTICAL)) }, k.lp(0, k.dp(76), 1f))
        card.addView(controls, k.lp(MATCH_PARENT).apply { topMargin = k.dp(2) })
        setPadding(k.dp(12), k.dp(4), k.dp(12), k.dp(12))
        addView(card, k.lp(MATCH_PARENT))
        alpha = 0f; translationY = k.dpf(36f)
    }

    // ------------------------------------------------------------------ life cycle

    fun open() {
        animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        if (!available(context)) {
            to(VoiceFlow.Phase.ERROR)
            caption(if (Build.VERSION.SDK_INT < 31) "Voice needs Android 12 or newer." else "This phone has no on-device speech recognition, so voice stays off rather than sending your audio anywhere.")
            return
        }
        tts = TextToSpeech(context.applicationContext, { s -> if (s == TextToSpeech.SUCCESS) ttsReady = pickOfflineVoice() }, "com.google.android.tts")
        NeuralVoice.warm(context)                                          // the natural voice, if downloaded: load it now so the first answer starts fast
        if (Build.VERSION.SDK_INT >= 31) recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        pickLanguage { listen() }
    }

    fun end() {
        if (phase == VoiceFlow.Phase.ENDED) return
        to(VoiceFlow.Phase.ENDED)
        ear.stop()
        recognizer?.destroy(); recognizer = null
        NeuralVoice.stop(); tts?.stop(); tts?.shutdown(); tts = null
        animate().alpha(0f).translationY(k.dpf(36f)).setDuration(180).withEndAction { onClose() }.start()
    }

    /** The app went to the background: the microphone and voice stop until you're back. */
    fun hold() {
        if (phase == VoiceFlow.Phase.ENDED || phase == VoiceFlow.Phase.IDLE) return
        heldByApp = true; pause("Paused while you were away")
    }

    /** Back in the app: carry on listening if it was only paused for that. */
    fun resume() {
        if (heldByApp && phase == VoiceFlow.Phase.IDLE && !muted) { heldByApp = false; lastHeardAt = SystemClock.elapsedRealtime(); listen() }
    }

    /** A question tapped (a pill, a link) while voice is open: stop listening, and the answer will be read out too. */
    fun thinking(text: String) {
        if (phase == VoiceFlow.Phase.ENDED || phase == VoiceFlow.Phase.THINKING) return
        Log.i(TAG, "asked by tap in ${phase.name}")
        recognizer?.cancel(); ear.stop(); stopVoice(); resetSpeech()
        phase = VoiceFlow.next(VoiceFlow.next(phase, VoiceFlow.Ev.Interrupt), VoiceFlow.Ev.Heard(text)); to(phase)
        caption(text, 0.6f)
    }

    /** The chat is answering the same question again (it folded a long thread first): drop what was half-spoken. */
    fun redo() {
        if (!answering) return
        ear.stop(); stopVoice(); resetSpeech()
        if (phase == VoiceFlow.Phase.SPEAKING) { phase = VoiceFlow.next(VoiceFlow.next(phase, VoiceFlow.Ev.Interrupt), VoiceFlow.Ev.Heard("…")); to(phase) }
    }

    private fun to(p: VoiceFlow.Phase) {
        if (p != orb.mode || status.text.isEmpty()) Log.i(TAG, "phase ${p.name}")
        phase = p; orb.mode = p
        status.text = if (muted && p == VoiceFlow.Phase.IDLE) "Muted · answers are still read out" else VoiceFlow.hint(p)
    }

    private fun caption(text: CharSequence, alpha: Float = 0.95f) { cap.text = text; cap.setTextColor(Pal.alpha(white, alpha)) }

    private fun tapOrb() {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        when (phase) {
            VoiceFlow.Phase.SPEAKING, VoiceFlow.Phase.THINKING -> interrupt()
            VoiceFlow.Phase.ERROR, VoiceFlow.Phase.IDLE -> if (available(context)) { if (muted) toggleMute(); lastHeardAt = SystemClock.elapsedRealtime(); listen() }
            VoiceFlow.Phase.LISTENING -> recognizer?.stopListening()            // "I'm done talking"
            VoiceFlow.Phase.ENDED -> Unit
        }
    }

    /** Stop talking and listen — from a tap, or from you talking over the answer. */
    private fun interrupt() {
        Log.i(TAG, "interrupted in ${phase.name}")
        ear.stop(); stopVoice(); resetSpeech(); onInterrupt()
        phase = VoiceFlow.next(phase, VoiceFlow.Ev.Interrupt)
        lastHeardAt = SystemClock.elapsedRealtime()
        listen(); status.text = "Go ahead — listening…"
    }

    private fun toggleMute() {
        muted = !muted
        slash.visibility = if (muted) VISIBLE else GONE
        muteBtn.contentDescription = if (muted) "Unmute the microphone" else "Mute the microphone"
        when {
            muted && phase == VoiceFlow.Phase.LISTENING -> pause("")
            muted && phase == VoiceFlow.Phase.SPEAKING -> ear.stop()
            !muted && (phase == VoiceFlow.Phase.IDLE || phase == VoiceFlow.Phase.ERROR) -> { lastHeardAt = SystemClock.elapsedRealtime(); listen() }
        }
        if (muted) to(phase)
    }

    private fun pause(why: String) {
        recognizer?.cancel(); ear.stop(); stopVoice(); resetSpeech()
        to(VoiceFlow.next(phase, VoiceFlow.Ev.Pause)); caption(why, 0.6f)
    }

    // ------------------------------------------------------------------ listening

    private fun listen() {
        if (phase == VoiceFlow.Phase.ENDED) return
        if (muted) { pause(""); return }
        ear.stop()
        to(VoiceFlow.next(phase, VoiceFlow.Ev.Start))
        caption("")
        if (Build.VERSION.SDK_INT < 31) return
        val r = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { orb.level = 0f; rebuilt = false }
            override fun onBeginningOfSpeech() { lastHeardAt = SystemClock.elapsedRealtime() }
            override fun onRmsChanged(rmsdB: Float) { orb.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { orb.level = 0f }
            override fun onPartialResults(b: Bundle?) { first(b)?.takeIf { it.isNotBlank() }?.let { caption(if (it.length > 140) "…" + it.takeLast(140) else it) } }
            override fun onResults(b: Bundle?) {
                if (phase != VoiceFlow.Phase.LISTENING) return
                val text = first(b).orEmpty().trim()
                if (text.isEmpty()) { again(); return }                          // nothing said: keep listening, hands-free
                backoff = 250L; lastHeardAt = SystemClock.elapsedRealtime()
                Log.i(TAG, "heard ${text.length} chars")
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                phase = VoiceFlow.next(phase, VoiceFlow.Ev.Heard(text)); to(phase)
                caption(text, 0.6f); resetSpeech()
                ask(text)
            }
            override fun onError(error: Int) {
                if (phase != VoiceFlow.Phase.LISTENING) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> again()
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Microphone permission is off")
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        fail("The on-device English speech pack isn't installed. Android can add it in Settings → System → Languages → Speech.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> { recognizer?.cancel(); backoff = (backoff * 2).coerceIn(250L, 2000L); postDelayed({ if (phase == VoiceFlow.Phase.LISTENING) listen() }, backoff) }
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> if (!rebuilt) { rebuilt = true; recognizer?.destroy(); recognizer = null; postDelayed({ if (phase == VoiceFlow.Phase.LISTENING) listen() }, 300) } else fail("The speech service stopped. Tap the orb to try again.")
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

    /** Silence: listen again straight away — unless it's been quiet for minutes, then pause (the mic shouldn't stay open for nobody). */
    private fun again() {
        if (SystemClock.elapsedRealtime() - lastHeardAt > VoiceFlow.QUIET_PAUSE_MS) { pause("Paused after a few quiet minutes"); return }
        postDelayed({ if (phase == VoiceFlow.Phase.LISTENING) listen() }, 120)
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
    private fun fail(msg: String) { ear.stop(); to(VoiceFlow.next(phase, VoiceFlow.Ev.Failed(msg))); caption(msg, 0.8f) }

    // ------------------------------------------------------------------ speaking

    // what has been queued for speaking: raw answer text consumed so far, utterances in flight, and a generation
    // number so callbacks from an interrupted answer are ignored
    private var consumed = 0
    private var finalSeen = false
    private var inFlight = 0
    private var halted = false
    private var gen = 0

    private fun resetSpeech() { consumed = 0; finalSeen = false; inFlight = 0; halted = false; spoken = ""; gen++ }
    private val answering get() = phase == VoiceFlow.Phase.THINKING || phase == VoiceFlow.Phase.SPEAKING

    private fun stopVoice() { NeuralVoice.stop(); tts?.stop() }

    /** Speaks [text] after what's already queued — with the natural voice when it's downloaded, else the phone's offline voice. */
    private fun enqueue(text: String): Boolean {
        val natural = NeuralVoice.enabled(context)
        val t = tts
        if (!natural && (t == null || !ttsReady || t.voice?.isNetworkConnectionRequired != false)) return false            // offline voice only, re-checked every time
        val offset = if (spoken.isEmpty()) 0 else spoken.length + 1
        spoken = if (spoken.isEmpty()) text else "$spoken $text"
        if (phase != VoiceFlow.Phase.SPEAKING) {
            phase = VoiceFlow.next(phase, VoiceFlow.Ev.Answer); to(phase)
            caption(SpannableString(spoken.substring(VoiceFlow.window(spoken, 0))), 0.5f)
        }
        inFlight++
        val id = "kp-$gen-$offset"
        if (natural && NeuralVoice.speak(context, id, text, natural())) return true
        if (t == null) { inFlight--; return false }
        t.setOnUtteranceProgressListener(listener)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        return true
    }

    private fun mine(id: String?): Int? = id?.split('-')?.takeIf { it.size == 3 && it[1].toIntOrNull() == gen }?.get(2)?.toIntOrNull()

    // the answer is playing: start noticing if you talk over it
    private fun started(id: String?) { if (mine(id) != null && phase == VoiceFlow.Phase.SPEAKING && !muted) { if (!ear.running) Log.i(TAG, "speaking; listening for talk-over"); ear.start() } }

    /** The natural voice reports the same three moments as the phone's voice. */
    private fun natural() = object : NeuralVoice.Listener {
        override fun onStart(id: String) = started(id)
        override fun onRange(id: String, start: Int, end: Int) { mine(id)?.let { o -> highlight(o + start, o + end) } }
        override fun onDone(id: String) = settled(id)
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) { post { started(id) } }
        override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) { post { mine(id)?.let { o -> highlight(o + start, o + end) } } }
        override fun onDone(id: String?) = settled(id)
        @Deprecated("Deprecated in Java") override fun onError(id: String?) = settled(id)
        override fun onError(id: String?, code: Int) = settled(id)
    }

    private fun settled(id: String?) { post { if (mine(id) == null) return@post; inFlight = (inFlight - 1).coerceAtLeast(0); if (inFlight == 0 && finalSeen) finishSpeaking() } }

    private fun finishSpeaking() {
        if (phase != VoiceFlow.Phase.SPEAKING) return
        ear.stop()
        phase = VoiceFlow.next(phase, VoiceFlow.Ev.Spoken)
        lastHeardAt = SystemClock.elapsedRealtime()
        // a short pause before listening again, so the phone doesn't hear the end of its own voice
        postDelayed({ if (phase == VoiceFlow.Phase.LISTENING) listen() }, 300)
    }

    /** You talked over the answer. */
    private fun heardOver() {
        if (phase != VoiceFlow.Phase.SPEAKING) return
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        interrupt()
    }

    /**
     * The model's answer as it streams: whole sentences are spoken as soon as they're complete, so the first words
     * come long before the answer is finished. A sentence with a figure waits — figures are spoken only from the
     * final, checked answer.
     */
    fun stream(md: String) {
        if (!answering || finalSeen || halted || md.length <= consumed) return
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
        Log.i(TAG, "answer ${md.length} chars in ${phase.name}")
        if (!answering) return
        finalSeen = true
        val already = consumed > 0 && md.length >= consumed
        val rest = if (already) md.substring(consumed) else md
        if (consumed > 0 && !already) { stopVoice(); spoken = ""; inFlight = 0; gen++ }           // the checked answer differs from what streamed: say it whole
        val text = VoiceFlow.speakable(rest)
        if (text.isNotBlank() && enqueue(text)) return
        if (inFlight == 0) {
            if (spoken.isEmpty()) {
                // nothing could be spoken (no offline voice installed): the answer is in the chat; carry on listening
                if (!ttsReady && !NeuralVoice.enabled(context)) status.text = "No offline voice installed — answers are in the chat"
                phase = VoiceFlow.next(phase, VoiceFlow.Ev.Interrupt); postDelayed({ if (phase == VoiceFlow.Phase.LISTENING) listen() }, 600)
            } else finishSpeaking()
        }
    }

    /** Words already spoken are bright, the current word glows, the rest wait, dim — showing the sentence being read. */
    private fun highlight(start: Int, end: Int) {
        if (phase != VoiceFlow.Phase.SPEAKING || spoken.isEmpty()) return
        val w = VoiceFlow.window(spoken, start)
        if (w.isEmpty()) return
        val s = SpannableString(spoken.substring(w))
        val a = (start - w.first).coerceIn(0, s.length); val b = (end - w.first).coerceIn(0, s.length)
        if (a > 0) s.setSpan(ForegroundColorSpan(Pal.alpha(white, 0.95f)), 0, a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (b > a) s.setSpan(ForegroundColorSpan(0xFF9FC1FF.toInt()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        cap.text = s; cap.setTextColor(Pal.alpha(white, 0.5f))
    }

    private fun pickOfflineVoice(): Boolean = tts?.let { Speaker.pickOffline(it) } ?: false

    companion object {
        private const val TAG = "KPVoice"
        /** On-device recognition only. */
        fun available(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
    }

    // ------------------------------------------------------------------ talking over the answer

    /**
     * While an answer plays, reads the microphone's loudness (after Android's echo cancelling and noise suppression)
     * in 30 ms frames and hands each frame's RMS to [VoiceFlow.TalkOver]. Nothing is recognised, stored or sent;
     * the recorder is closed before the speech recognizer opens the microphone again.
     */
    private inner class Ear {
        @Volatile private var on = false
        private var thread: Thread? = null
        val running get() = on

        fun start() {
            if (on || Build.VERSION.SDK_INT < 31) return
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            on = true
            thread = Thread({ run() }, "kp-talk-over").apply { isDaemon = true; start() }
        }

        fun stop() {
            on = false
            thread?.let { if (it !== Thread.currentThread()) runCatching { it.join(300) } }
            thread = null
        }

        @SuppressLint("MissingPermission")
        private fun run() {
            val rate = 16_000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) { on = false; return }
            val r = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400)) }.getOrNull()
            if (r == null || r.state != AudioRecord.STATE_INITIALIZED) { r?.release(); on = false; return }
            val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
            val det = VoiceFlow.TalkOver()
            val buf = ShortArray(480)
            var peak = 0.0; var frames = 0
            try {
                r.startRecording()
                while (on) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                    val rms = sqrt(sum / n)
                    // debug builds only: loudness numbers once a second, to calibrate talk-over (never audio, never words)
                    if (com.khayali.pulao.BuildConfig.DEBUG) { peak = maxOf(peak, rms); if (++frames % 33 == 0) { Log.d(TAG, "talk-over level peak=${"%.3f".format(peak)} threshold=${"%.3f".format(det.threshold)}"); peak = 0.0 } }
                    if (det.feed(rms)) {
                        Log.i(TAG, "talk-over (echo cancel ${aec != null}, threshold ${"%.3f".format(det.threshold)})")
                        on = false; post { heardOver() }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                runCatching { r.stop() }; r.release(); aec?.release(); ns?.release(); on = false
            }
        }
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

        override fun onDraw(c: Canvas) {
            val t = (System.nanoTime() - t0) / 1e9f
            shown += (level - shown) * 0.25f
            if (mode == VoiceFlow.Phase.SPEAKING) speakPulse = 0.5f + 0.5f * sin(t * 7f) * sin(t * 2.3f).let { kotlin.math.abs(it) }
            val cx = width / 2f; val cy = height / 2f; val base = width * 0.27f
            val swell = when (mode) {
                VoiceFlow.Phase.LISTENING -> shown * 0.35f + 0.03f * sin(t * 2f)
                VoiceFlow.Phase.SPEAKING -> speakPulse * 0.14f
                VoiceFlow.Phase.THINKING -> 0.04f * sin(t * 3f)
                else -> 0.02f * sin(t * 1.4f)
            }
            val r = base * (1f + swell)
            val (a, b) = when (mode) {
                VoiceFlow.Phase.LISTENING -> 0xFF5282EB.toInt() to 0xFF8E7CF0.toInt()
                VoiceFlow.Phase.THINKING -> 0xFF8E7CF0.toInt() to 0xFFE8708A.toInt()
                VoiceFlow.Phase.SPEAKING -> 0xFF67AD5B.toInt() to 0xFF5282EB.toInt()
                VoiceFlow.Phase.ERROR -> 0xFFCF5B52.toInt() to 0xFF8E7CF0.toInt()
                else -> 0xFF5D6B7E.toInt() to 0xFF324050.toInt()
            }
            p.shader = RadialGradient(cx, cy, r * 1.8f, intArrayOf(Pal.alpha(a, 0.35f), Pal.alpha(b, 0.12f), 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r * 1.8f, p)
            val hx = cx + r * 0.35f * sin(t * 0.8f); val hy = cy - r * 0.35f * kotlin.math.cos(t * 0.8f)
            p.shader = RadialGradient(hx, hy, r * 1.6f, intArrayOf(Pal.alpha(0xFFFFFFFF.toInt(), 0.9f), a, b), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
            if (mode == VoiceFlow.Phase.THINKING) for (i in 0..1) {
                val ph = ((t * 0.7f + i * 0.5f) % 1f)
                ring.strokeWidth = k.dpf(1.5f); ring.color = Pal.alpha(0xFFFFFFFF.toInt(), 0.35f * (1 - ph))
                c.drawCircle(cx, cy, r * (1.05f + ph * 0.6f), ring)
            }
            if (mode != VoiceFlow.Phase.ENDED && isAttachedToWindow) postInvalidateOnAnimation()
        }
    }
}

/**
 * Dictation for the input box: one utterance through Android's on-device recognizer (never the cloud one); the words
 * appear as you speak and stay in the box for you to check and send.
 */
class Dictation(private val ctx: Context, private val onText: (String) -> Unit, private val onLevel: (Float) -> Unit, private val onEnd: (String?) -> Unit) {
    private var r: SpeechRecognizer? = null

    fun start() {
        if (!LiveVoice.available(ctx) || Build.VERSION.SDK_INT < 31) { onEnd("This phone has no on-device speech recognition"); return }
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { r = it }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(db: Float) = onLevel(((db + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(b: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText) }
            override fun onResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText); finish(null) }
            override fun onError(e: Int) = finish(when (e) {
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Add the on-device English speech pack in Settings → Languages → Speech"
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off"
                else -> "Couldn't hear that"
            })
            override fun onEvent(t: Int, p: Bundle?) = Unit
        })
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (Build.VERSION.SDK_INT >= 33) putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
        })
    }

    /** "I'm done" — the recognizer finishes what it heard. */
    fun stop() { r?.stopListening() }

    private fun finish(problem: String?) { r?.destroy(); r = null; onEnd(problem) }
}


package com.khayali.pulao.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.khayali.pulao.ai.data.VoicePack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * The natural voice: Kokoro-82M (int8) run on the phone by sherpa-onnx, played through an AudioTrack. Nothing is
 * sent anywhere — the model files sit on the phone after a one-time download ([VoicePack]).
 *
 * Two threads: one writes the next sentence's audio while the other plays the current one, so a streamed answer is
 * spoken without gaps. Each utterance reports when it starts, the word being spoken (from playback position) and when
 * it's done, so captions light word by word. [stop] drops everything queued at once.
 */
object NeuralVoice {
    interface Listener { fun onStart(id: String); fun onRange(id: String, start: Int, end: Int); fun onDone(id: String) }

    data class Voice(val key: String, val name: String, val desc: String, val sid: Int)
    /** Kokoro v1.0 speaker ids in sherpa-onnx's voices.bin: all 54 voices in name order (af_alloy 0 … zm_yunyang 53). */
    val VOICES = listOf(
        Voice("heart", "Heart", "Warm · American English", 3),
        Voice("emma", "Emma", "Clear · British English", 21),
        Voice("george", "George", "Calm · British English, male", 26),
        Voice("alpha", "Alpha", "Indian voice · reads English", 32),
        Voice("omega", "Omega", "Indian voice, male · reads English", 34),
    )
    private const val TAG = "KPVoice"
    private val main = Handler(Looper.getMainLooper())

    private class Item(val id: String, val text: String, val sid: Int, val gen: Int, val l: Listener)
    private class Clip(val item: Item, val pcm: FloatArray)

    @Volatile private var tts: OfflineTts? = null
    /** CPU threads for synthesis (measured on the phone; see NaturalVoiceDeviceTest). */
    @Volatile var cpuThreads = 4
    @Volatile var provider = "cpu"
    @Volatile private var broken = false
    @Volatile private var lexicon: Set<String> = emptySet()
    private val gen = AtomicInteger()
    private val synthQ = LinkedBlockingQueue<Item>()
    private val playQ = LinkedBlockingQueue<Clip>()
    @Volatile private var track: AudioTrack? = null
    private var threads: Pair<Thread, Thread>? = null
    private var app: Context? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("khayali", Context.MODE_PRIVATE)
    /** The chosen voice ("system" = the phone's own offline voice). */
    fun choice(ctx: Context) = prefs(ctx).getString("voice", "heart") ?: "heart"
    fun choose(ctx: Context, key: String) = prefs(ctx).edit().putString("voice", key).apply()
    fun voice(ctx: Context) = VOICES.firstOrNull { it.key == choice(ctx) }

    /** True when the natural voice is downloaded, chosen and working. */
    fun enabled(ctx: Context) = !broken && voice(ctx) != null && VoicePack.ready(ctx)

    /** Loads the model in the background so the first answer starts quickly. */
    fun warm(ctx: Context) { if (enabled(ctx)) start(ctx) }

    /** Queues [text] (shown as captions) to be spoken; false if the natural voice isn't available. */
    fun speak(ctx: Context, id: String, text: String, l: Listener): Boolean {
        val v = voice(ctx) ?: return false
        if (!enabled(ctx)) return false
        start(ctx)
        synthQ.put(Item(id, text, v.sid, gen.get(), l))
        return true
    }

    /** Stops speaking now and drops everything queued. */
    fun stop() {
        gen.incrementAndGet(); synthQ.clear(); playQ.clear()
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    @Synchronized private fun start(ctx: Context) {
        if (threads != null) return
        app = ctx.applicationContext
        val s = Thread({ synthLoop() }, "kp-voice-synth").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1 }
        val p = Thread({ playLoop() }, "kp-voice-play").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
        threads = s to p; s.start(); p.start()
    }

    private fun load(): OfflineTts? {
        tts?.let { return it }
        val ctx = app ?: return null
        return runCatching {
            val t0 = System.nanoTime()
            val lex = VoicePack.file(ctx, "kokoro_lexicon")
            val t = OfflineTts(null, OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = VoicePack.file(ctx, "kokoro_model").path, voices = VoicePack.file(ctx, "kokoro_voices").path,
                        tokens = VoicePack.file(ctx, "kokoro_tokens").path, lexicon = lex.path),
                    numThreads = cpuThreads, provider = provider),
                maxNumSentences = 1))
            lexicon = lex.useLines { lines -> lines.map { it.substringBefore(' ') }.filter { it.isNotEmpty() }.toHashSet() }
            Log.i(TAG, "natural voice loaded in ${(System.nanoTime() - t0) / 1_000_000} ms (${t.numSpeakers()} voices, ${t.sampleRate()} Hz)")
            t.also { tts = it }
        }.onFailure { Log.w(TAG, "natural voice failed to load", it); broken = true }.getOrNull()
    }

    private fun synthLoop() {
        while (true) {
            val item = synthQ.take()
            if (item.gen != gen.get()) continue
            val t = load()
            if (t == null) { main.post { item.l.onDone(item.id) }; continue }
            val sid = item.sid.coerceIn(0, maxOf(0, t.numSpeakers() - 1))
            val said = Pronounce.prepare(item.text) { it in lexicon }
            val t0 = System.nanoTime()
            val pcm = runCatching { t.generate(said, sid, 1.0f).samples }.getOrElse { Log.w(TAG, "synthesis failed", it); FloatArray(0) }
            if (com.khayali.pulao.BuildConfig.DEBUG) Log.d(TAG, "synth ${said.length} chars → ${pcm.size / maxOf(1, t.sampleRate())} s audio in ${(System.nanoTime() - t0) / 1_000_000} ms")
            if (item.gen != gen.get()) continue
            playQ.put(Clip(item, pcm))
        }
    }

    private fun playLoop() {
        var written = 0L                                       // frames written since the track last started
        while (true) {
            val clip = playQ.take()
            val item = clip.item
            if (item.gen != gen.get()) continue
            val rate = tts?.sampleRate() ?: 24_000
            val tr = track ?: AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), rate / 5 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM).build().also { track = it }
            if (tr.playState != AudioTrack.PLAYSTATE_PLAYING) { written = 0; tr.play() }
            val start = written
            main.post { if (item.gen == gen.get()) item.l.onStart(item.id) }
            val bounds = wordBounds(item.text)
            var lastWord = -1
            var i = 0
            val step = rate / 20                                   // 50 ms at a time
            while (i < clip.pcm.size && item.gen == gen.get()) {
                val n = minOf(step, clip.pcm.size - i)
                val w = tr.write(clip.pcm, i, n, AudioTrack.WRITE_BLOCKING)
                if (w <= 0) break
                i += w; written += w
                // the word being heard: playback position through this clip, mapped onto the caption's words
                val heard = (tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - start
                val frac = (heard.toDouble() / clip.pcm.size).coerceIn(0.0, 1.0)
                val k = bounds.indexOfLast { it.first <= frac * item.text.length }.coerceAtLeast(0)
                if (k != lastWord && bounds.isNotEmpty()) { lastWord = k; val b = bounds[k]; main.post { if (item.gen == gen.get()) item.l.onRange(item.id, b.first, b.last + 1) } }
            }
            // let the last of it play out before saying it's done
            while (item.gen == gen.get() && (tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < written) {
                val heard = (tr.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - start
                val frac = (heard.toDouble() / maxOf(1, clip.pcm.size)).coerceIn(0.0, 1.0)
                val k = bounds.indexOfLast { it.first <= frac * item.text.length }.coerceAtLeast(0)
                if (k != lastWord && bounds.isNotEmpty()) { lastWord = k; val b = bounds[k]; main.post { if (item.gen == gen.get()) item.l.onRange(item.id, b.first, b.last + 1) } }
                Thread.sleep(25)
            }
            if (item.gen == gen.get()) main.post { item.l.onDone(item.id) }
            if (playQ.isEmpty() && synthQ.isEmpty()) { runCatching { tr.stop() } }
        }
    }

    /** For the on-device check: load (if needed) and synthesise [text] without playing it. Returns (samples, rate). */
    fun synthesizeForCheck(ctx: Context, text: String, sid: Int = 3): Pair<FloatArray, Int>? {
        app = ctx.applicationContext
        val t = load() ?: return null
        return t.generate(Pronounce.prepare(text) { it in lexicon }, sid, 1.0f).samples to t.sampleRate()
    }
    fun speakersForCheck() = tts?.numSpeakers()
    fun reloadForCheck(n: Int, p: String = "cpu") { tts?.release(); tts = null; broken = false; cpuThreads = n; provider = p }

    private fun wordBounds(s: String): List<IntRange> = Regex("""\S+""").findAll(s).map { it.range }.toList()
}

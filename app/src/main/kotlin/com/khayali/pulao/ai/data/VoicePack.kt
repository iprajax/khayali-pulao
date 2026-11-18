package com.khayali.pulao.ai.data

import android.content.Context

/**
 * The natural voice's files (Kokoro-82M v1.0, int8, Apache-2.0), downloaded once — only if you choose the natural
 * voice — from a public Hugging Face repo (no login), each pinned to its exact size and sha256 like the AI model.
 */
object VoicePack {
    private const val REPO = "csukuangfj/kokoro-int8-multi-lang-v1_0"
    private fun url(f: String) = "https://${com.khayali.pulao.ai.ModelSpec.HOST}/$REPO/resolve/main/$f"

    val FILES = listOf(
        FilePack.Spec("kokoro_model", "Natural voice", url("model.int8.onnx"), "kokoro-model.int8.onnx", "75635ea3b85a32d00094dcdd61a258aba87c160f7c264c304a7b3ae9d734195e", 114_202_993),
        FilePack.Spec("kokoro_voices", "Natural voice · voices", url("voices.bin"), "kokoro-voices.bin", "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29", 28_200_960),
        FilePack.Spec("kokoro_tokens", "Natural voice · tokens", url("tokens.txt"), "kokoro-tokens.txt", "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425", 687),
        FilePack.Spec("kokoro_lexicon", "Natural voice · pronunciations", url("lexicon-us-en.txt"), "kokoro-lexicon-us-en.txt", "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14", 5_956_885),
    )
    val BYTES = FILES.sumOf { it.bytes }

    @Volatile private var packs: List<FilePack>? = null
    fun packs(ctx: Context) = packs ?: synchronized(this) { packs ?: FILES.map { FilePack(ctx.applicationContext, it) }.also { packs = it } }

    fun ready(ctx: Context) = packs(ctx).all { it.ready() }
    fun install(ctx: Context) = packs(ctx).forEach { it.install() }
    fun delete(ctx: Context) = packs(ctx).forEach { it.delete() }
    fun file(ctx: Context, id: String) = packs(ctx).first { it.spec.id == id }.file

    /** (bytes so far, total) while downloading; null when nothing is in flight. */
    fun progress(ctx: Context): Pair<Long, Long>? {
        val ps = packs(ctx).onEach { it.refresh() }
        if (ps.none { it.state.value is Artifacts.State.Downloading }) return null
        val done = ps.sumOf { p -> when (val s = p.state.value) { is Artifacts.State.Downloading -> s.done; else -> if (p.ready()) p.spec.bytes else 0L } }
        return done to BYTES
    }

    fun failed(ctx: Context): String? = packs(ctx).firstNotNullOfOrNull { (it.state.value as? Artifacts.State.Failed)?.message }
}

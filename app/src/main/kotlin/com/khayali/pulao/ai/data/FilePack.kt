package com.khayali.pulao.ai.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * A downloadable file, fetched the same way as the AI model: Android's system download manager
 * (resumable, survives the app closing, shows a notification), then accepted only if its sha256
 * matches. Drives one [Artifacts] state machine.
 */
class FilePack(private val ctx: Context, val spec: Spec) {
    data class Spec(val id: String, val title: String, val url: String, val file: String, val sha256: String, val bytes: Long)

    private val dm = ctx.getSystemService(DownloadManager::class.java)
    private val prefs = ctx.getSharedPreferences("khayali_packs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: Job? = null
    private val dir get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "packs").apply { mkdirs() }
    val file get() = File(dir, spec.file)
    private val part get() = File(dir, spec.file + ".part")
    private val key get() = "dl_${spec.id}"
    private val _state = MutableStateFlow<Artifacts.State>(Artifacts.State.NotInstalled)
    val state: StateFlow<Artifacts.State> = _state

    init { refresh() }

    fun ready() = file.exists() && file.length() == spec.bytes

    fun refresh() {
        val id = prefs.getLong(key, -1)
        when {
            id >= 0 -> watch(id)
            ready() -> _state.value = Artifacts.State.Ready("v1", spec.bytes, file.lastModified())
            else -> _state.value = Artifacts.State.NotInstalled
        }
    }

    fun install() {
        if (ready() || prefs.getLong(key, -1) >= 0) return refresh()
        part.delete()
        val req = DownloadManager.Request(Uri.parse(spec.url))
            .setTitle("Khayali Pulao — ${spec.title}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(part))
            .setAllowedOverRoaming(false)
        val id = runCatching { dm.enqueue(req) }.getOrElse { _state.value = Artifacts.State.Failed("Android's download manager isn't available"); return }
        prefs.edit().putLong(key, id).apply()
        _state.value = Artifacts.State.Downloading(0, spec.bytes)
        watch(id)
    }

    fun delete() {
        prefs.getLong(key, -1).takeIf { it >= 0 }?.let { dm.remove(it) }
        prefs.edit().remove(key).apply(); watcher?.cancel(); part.delete(); file.delete()
        _state.value = Artifacts.State.NotInstalled
    }

    private fun watch(id: Long) {
        watcher?.cancel()
        watcher = scope.launch {
            while (isActive) {
                val row = runCatching { dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (!c.moveToFirst()) null else Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) } }.getOrNull()
                if (row == null) { prefs.edit().remove(key).apply(); _state.value = if (ready()) Artifacts.State.Ready("v1", spec.bytes, 0) else Artifacts.State.NotInstalled; return@launch }
                val (status, done, reason) = row
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { prefs.edit().remove(key).apply(); _state.value = Artifacts.State.Verifying; verify(); return@launch }
                    DownloadManager.STATUS_FAILED -> { prefs.edit().remove(key).apply(); part.delete()
                        _state.value = Artifacts.State.Failed(if (reason == 404) "Not published yet (HTTP 404)" else "Download failed (code $reason)"); return@launch }
                    DownloadManager.STATUS_PAUSED -> _state.value = Artifacts.State.Paused("Waiting for network", Artifacts.State.Downloading(done, spec.bytes))
                    else -> _state.value = Artifacts.State.Downloading(done, spec.bytes)
                }
                delay(400)
            }
        }
    }

    private fun verify() {
        val md = MessageDigest.getInstance("SHA-256")
        part.inputStream().buffered().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val ok = md.digest().joinToString("") { "%02x".format(it) } == spec.sha256
        if (ok && part.renameTo(file)) _state.value = Artifacts.State.Ready("v1", spec.bytes, System.currentTimeMillis())
        else { part.delete(); _state.value = Artifacts.State.Failed("The download didn't match its checksum — try again") }
    }
}

/** The community-screens knowledge bank: ~290 KB, published on the project site, downloaded on demand. */
object KnowledgePack {
    /** The project's own site (GitHub Pages) — the only place packs are downloaded from. */
    const val HOST = "iprajax.github.io"
    val SPEC = FilePack.Spec("knowledge", "Strategy knowledge bank", "https://$HOST/khayali-pulao/knowledge/screens-v1.kb", "screens-v1.kb",
        "a5b75d9a3f63011aa44a8b97a2aa1a6b77d5377c103383e559a8017f6813f2e8", 289178)
    @android.annotation.SuppressLint("StaticFieldLeak") @Volatile private var pack: FilePack? = null
    @Volatile private var bank: com.khayali.pulao.ai.term.Knowledge? = null
    fun get(ctx: Context) = pack ?: synchronized(this) { pack ?: FilePack(ctx.applicationContext, SPEC).also { pack = it } }
    /** The loaded bank, or null until it has been downloaded. */
    fun bank(ctx: Context): com.khayali.pulao.ai.term.Knowledge? {
        val p = get(ctx); if (!p.ready()) { bank = null; return null }
        return bank ?: synchronized(this) { bank ?: runCatching { com.khayali.pulao.ai.term.Knowledge.parse(p.file.readBytes()) }.getOrNull()?.also { bank = it } }
    }
}

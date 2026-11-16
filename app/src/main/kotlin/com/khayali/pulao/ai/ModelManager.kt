package com.khayali.pulao.ai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Model lifecycle: absent → downloading → verifying → ready.
 *
 * Uses Android's system DownloadManager: resumable (HTTP Range), survives the app
 * being closed, shows a system notification, retries on network changes. The file
 * lands in app-specific storage (no storage permission) and is only accepted after
 * its sha256 matches [ModelSpec.SHA256].
 */
class ModelManager private constructor(private val app: Context) {

    sealed class State {
        data object Absent : State()
        data class NotEnoughSpace(val needBytes: Long, val freeBytes: Long) : State()
        data class Downloading(
            val done: Long, val total: Long, val bytesPerSec: Double?, val etaSec: Long?, val paused: Boolean, val reason: String?,
        ) : State()
        data class Verifying(val done: Long, val total: Long) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dm = app.getSystemService(DownloadManager::class.java)
    private val prefs = app.getSharedPreferences("khayali_model", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Absent)
    val state: StateFlow<State> = _state
    private var watcher: Job? = null

    val dir: File get() = File(app.getExternalFilesDir(null) ?: app.filesDir, "models").apply { mkdirs() }
    val file: File get() = File(dir, ModelSpec.FILE)
    private val partFile: File get() = File(dir, ModelSpec.FILE + ".part")
    private val verifiedMarker: File get() = File(dir, ModelSpec.FILE + ".sha256-ok")

    init { refresh() }

    /** Recompute state from disk + any in-flight download. Cheap; call on resume. */
    fun refresh() {
        val id = prefs.getLong(KEY_ID, -1)
        when {
            isReady() -> _state.value = State.Ready
            id >= 0 && query(id) != null -> watch(id)
            file.exists() && file.length() == ModelSpec.SIZE_BYTES -> verify()
            else -> _state.value = State.Absent
        }
    }

    fun isReady() = file.exists() && file.length() == ModelSpec.SIZE_BYTES &&
        verifiedMarker.exists() && verifiedMarker.readText().trim() == ModelSpec.SHA256

    fun freeBytes(): Long = runCatching { StatFs(dir.path).availableBytes }.getOrDefault(0L)

    fun start(wifiOnly: Boolean) {
        if (isReady()) { _state.value = State.Ready; return }
        val existing = prefs.getLong(KEY_ID, -1)
        if (existing >= 0 && query(existing) != null) { watch(existing); return }
        val need = ModelSpec.SIZE_BYTES + ModelSpec.HEADROOM_BYTES - partFile.length().coerceAtLeast(0)
        val free = freeBytes()
        if (free < need) { _state.value = State.NotEnoughSpace(need, free); return }
        file.delete(); verifiedMarker.delete()
        val req = DownloadManager.Request(Uri.parse(ModelSpec.URL))
            .setTitle("Khayali Pulao — AI model")
            .setDescription("${ModelSpec.DISPLAY_NAME} · ${DownloadText.bytes(ModelSpec.SIZE_BYTES)} · runs on your phone")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(partFile))
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        val id = dm.enqueue(req)
        prefs.edit().putLong(KEY_ID, id).putBoolean(KEY_WIFI, wifiOnly).apply()
        watch(id)
    }

    fun cancel() {
        val id = prefs.getLong(KEY_ID, -1)
        if (id >= 0) dm.remove(id)
        prefs.edit().remove(KEY_ID).apply()
        watcher?.cancel()
        partFile.delete()
        _state.value = State.Absent
    }

    /** Free 2.6 GB. Chat falls back to the download card. */
    fun delete() {
        cancel()
        AiEngine.unload()
        file.delete(); verifiedMarker.delete()
        _state.value = State.Absent
    }

    private data class Row(val status: Int, val done: Long, val total: Long, val reason: Int)

    private fun query(id: Long): Row? = runCatching {
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            Row(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }
    }.getOrNull()

    private fun watch(id: Long) {
        watcher?.cancel()
        watcher = scope.launch {
            val meter = SpeedMeter()
            while (isActive) {
                val r = query(id)
                if (r == null) { prefs.edit().remove(KEY_ID).apply(); _state.value = State.Absent; return@launch }
                val total = if (r.total > 0) r.total else ModelSpec.SIZE_BYTES
                when (r.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        prefs.edit().remove(KEY_ID).apply()
                        if (!partFile.renameTo(file)) { _state.value = State.Failed("Couldn't save the model file"); return@launch }
                        verify(); return@launch
                    }
                    DownloadManager.STATUS_FAILED -> {
                        prefs.edit().remove(KEY_ID).apply(); partFile.delete()
                        _state.value = State.Failed(failReason(r.reason)); return@launch
                    }
                    else -> {
                        meter.sample(r.done, System.currentTimeMillis())
                        val paused = r.status == DownloadManager.STATUS_PAUSED
                        _state.value = State.Downloading(
                            r.done, total, if (paused) null else meter.bytesPerSec,
                            if (paused) null else meter.etaSeconds(r.done, total), paused,
                            if (paused) pauseReason(r.reason) else null,
                        )
                    }
                }
                delay(700)
            }
        }
    }

    private fun verify() {
        watcher?.cancel()
        watcher = scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(1 shl 20)
                var done = 0L
                file.inputStream().buffered(1 shl 20).use { input ->
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        md.update(buf, 0, n); done += n
                        if (done % (64L shl 20) < n) _state.value = State.Verifying(done, ModelSpec.SIZE_BYTES)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) } == ModelSpec.SHA256
            }
            if (ok) { verifiedMarker.writeText(ModelSpec.SHA256); _state.value = State.Ready }
            else { file.delete(); _state.value = State.Failed("Download was corrupted (checksum mismatch). Please try again.") }
        }
    }

    private fun pauseReason(r: Int) = when (r) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Retrying shortly"
        else -> "Paused"
    }

    private fun failReason(r: Int) = when (r) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "Connection dropped — tap to retry"
        else -> "Download failed (code $r) — tap to retry"
    }

    companion object {
        private const val KEY_ID = "download_id"
        private const val KEY_WIFI = "wifi_only"
        @Volatile private var instance: ModelManager? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: ModelManager(ctx.applicationContext).also { instance = it } }
    }
}

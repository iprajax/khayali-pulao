package com.khayali.pulao.ai

import java.util.Locale
import kotlin.math.roundToLong

/** Pure helpers for the download UI: smoothed speed, ETA and labels. Unit-tested. */
class SpeedMeter(private val alpha: Double = 0.25) {
    private var lastBytes = -1L
    private var lastMs = 0L
    /** Exponentially smoothed bytes/second; null until there are two samples. */
    var bytesPerSec: Double? = null
        private set

    fun sample(bytes: Long, nowMs: Long) {
        if (lastBytes >= 0 && nowMs > lastMs) {
            val inst = (bytes - lastBytes).coerceAtLeast(0) * 1000.0 / (nowMs - lastMs)
            bytesPerSec = bytesPerSec?.let { it + alpha * (inst - it) } ?: inst
        }
        lastBytes = bytes; lastMs = nowMs
    }

    fun etaSeconds(done: Long, total: Long): Long? {
        val s = bytesPerSec ?: return null
        if (s < 1 || total <= 0) return null
        return ((total - done).coerceAtLeast(0) / s).roundToLong()
    }
}

object DownloadText {
    fun bytes(b: Long): String = when {
        b >= 999_500_000 -> String.format(Locale.US, "%.2f GB", b / 1e9)
        b >= 1_000_000 -> String.format(Locale.US, "%.0f MB", b / 1e6)
        b >= 1_000 -> String.format(Locale.US, "%.0f KB", b / 1e3)
        else -> "$b B"
    }

    fun speed(bps: Double?): String = if (bps == null) "—" else bytes(bps.roundToLong()) + "/s"

    fun eta(sec: Long?): String = when {
        sec == null -> "estimating…"
        sec < 60 -> "${sec}s left"
        sec < 3600 -> "${sec / 60} min ${sec % 60}s left"
        else -> "${sec / 3600} h ${(sec % 3600) / 60} min left"
    }

    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
}

package com.khayali.pulao.ai.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/**
 * Runs the [Refresh] schedule. Called when the chat opens or resumes, and every 15 minutes while it is
 * open, so evening data arrives without a tap. Only touches what is installed — it never starts a
 * download the user didn't ask for. Each run's time is kept on the phone.
 */
class Refresher(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("khayali_refresh", Context.MODE_PRIVATE)
    private val pack get() = MarketPack.get(ctx)

    fun lastRun(id: String): Long? = prefs.getLong(id, -1).takeIf { it > 0 }
    private fun mark(id: String) = prefs.edit().putLong(id, System.currentTimeMillis()).apply()

    /** A task that hasn't run yet counts its first period as done for the heavier ones (no rebuild right after install). */
    private fun baseline() {
        if (prefs.getBoolean("baselined", false)) return
        val e = prefs.edit(); val now = System.currentTimeMillis()
        Refresh.TASKS.filter { it.every.ordinal >= Refresh.Every.MONTHLY.ordinal }.forEach { e.putLong(it.id, now) }
        e.putBoolean("baselined", true).apply()
    }

    data class Done(val task: Refresh.Task, val note: String, val fetching: Boolean = false)

    /** Runs every due task; returns what was done (empty when nothing was due or nothing is installed). */
    suspend fun runDue(now: ZonedDateTime = ZonedDateTime.now(Refresh.IST), force: String? = null): List<Done> = withContext(Dispatchers.IO) {
        if (!pack.installed || pack.busy) return@withContext emptyList()
        baseline()
        val out = mutableListOf<Done>()
        for (t in Refresh.TASKS) {
            if (force != t.id && (force != null || !Refresh.due(t.every, lastRun(t.id), now))) continue
            runCatching { run(t) }.onSuccess { d -> if (d != null) { out += d; if (!d.fetching) mark(t.id) } }
        }
        out
    }

    private fun run(t: Refresh.Task): Done? = when (t.id) {
        "daily-prices", "weekly-check" -> {
            val n = pack.install()                            // fetches only the missing trading days
            if (t.id == "weekly-check") KnowledgePack.get(ctx).let { kb -> if (kb.file.exists() && !kb.verify()) kb.delete() }
            when {
                n > 0 -> Done(t, "Fetching $n new trading day${if (n > 1) "s" else ""} from NSE…", fetching = true)
                n == 0 -> Done(t, if (t.id == "daily-prices") "Market history is up to date" else "No gaps; files check out")
                else -> null
            }
        }
        "monthly-rebuild" -> { pack.rebuild(); Done(t, "Refreshed the stock universe for this month") }
        "quarterly-compact" -> { pack.compact(); Done(t, "Tidied the market database") }
        "yearly-roll" -> { val n = pack.roll(); Done(t, if (n > 0) "Rolled the history window: dropped $n old days" else "History window already current") }
        else -> null
    }

    /** Marks a fetch-type task done once its download has finished. */
    fun fetched(id: String) = mark(id)
}

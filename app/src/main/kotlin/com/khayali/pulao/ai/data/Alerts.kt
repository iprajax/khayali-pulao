package com.khayali.pulao.ai.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

/**
 * Price alerts, checked on the phone against each day's NSE close — while the chat is open and, in the
 * background, by an Android job after the evening's file is out. Pure rules + an on-phone store + the job.
 */
object Alerts {
    enum class Kind { ABOVE, BELOW, MOVE }
    data class Rule(val id: Long, val symbol: String, val kind: Kind, val value: Double, val created: String)
    data class Hit(val rule: Rule, val close: Double, val move: Double, val date: LocalDate) {
        val text get() = when (rule.kind) {
            Kind.ABOVE -> "${rule.symbol} closed above ₹${fmt(rule.value)} — at ₹${fmt(close)}"
            Kind.BELOW -> "${rule.symbol} closed below ₹${fmt(rule.value)} — at ₹${fmt(close)}"
            Kind.MOVE -> "${rule.symbol} moved ${"%+.1f".format(move * 100)}% today (alert at ±${fmt(rule.value)}%) — ₹${fmt(close)}"
        }
    }
    private fun fmt(v: Double) = if (v >= 100) "%,.0f".format(v) else "%,.2f".format(v)

    /** "INFY above 1800", "tcs below 3,000", "SBIN moves 5%", "reliance move 3" → a rule (or null). */
    fun parse(text: String, id: Long, today: LocalDate): Rule? {
        val m = Regex("""(?i)^\s*@?([A-Za-z][A-Za-z0-9&\-]{1,19})\s+(above|over|crosses|>|below|under|<|moves?|moving|changes?)\s+(?:by\s+)?(?:rs\.?|₹)?\s*([\d,]+(?:\.\d+)?)\s*%?\s*$""").find(text) ?: return null
        val v = m.groupValues[3].replace(",", "").toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        val kind = when (m.groupValues[2].lowercase()) { "above", "over", "crosses", ">" -> Kind.ABOVE; "below", "under", "<" -> Kind.BELOW; else -> Kind.MOVE }
        return Rule(id, m.groupValues[1].uppercase(), kind, v, today.toString())
    }

    fun check(r: Rule, close: Double, move: Double, date: LocalDate): Hit? = when (r.kind) {
        Kind.ABOVE -> if (close >= r.value) Hit(r, close, move, date) else null
        Kind.BELOW -> if (close <= r.value) Hit(r, close, move, date) else null
        Kind.MOVE -> if (abs(move) * 100 >= r.value) Hit(r, close, move, date) else null
    }

    /** How far each rule is from firing, for the list. */
    fun distance(r: Rule, close: Double, move: Double): String = when (r.kind) {
        Kind.ABOVE -> if (close >= r.value) "triggered" else "%.1f%% to go".format((r.value / close - 1) * 100)
        Kind.BELOW -> if (close <= r.value) "triggered" else "%.1f%% to go".format((1 - r.value / close) * 100)
        Kind.MOVE -> "today ${"%+.1f".format(move * 100)}%"
    }

    /** Rules that fire on the panel's last day and haven't fired for that day yet. */
    fun evaluate(rules: List<Rule>, p: Panel, firedOn: Map<Long, String>): List<Hit> {
        val d = p.days - 1; val day = p.dates[d]
        return rules.mapNotNull { r -> if (firedOn[r.id] == day.toString()) return@mapNotNull null
            val s = p.of(r.symbol) ?: return@mapNotNull null
            val close = p.adj[s][d].toDouble().takeIf { !it.isNaN() } ?: return@mapNotNull null
            check(r, close, p.ret[s][d].toDouble().takeIf { !it.isNaN() } ?: 0.0, day) }
    }

    // ---------------------------------------------------------------- store (on the phone)

    class Store(ctx: Context) {
        private val app = ctx.applicationContext
        private val prefs = app.getSharedPreferences("khayali_alerts", Context.MODE_PRIVATE)
        private val gson = Gson()
        fun rules(): List<Rule> = runCatching { gson.fromJson<List<Rule>>(prefs.getString("rules", "[]"), object : TypeToken<List<Rule>>() {}.type) }.getOrNull().orEmpty()
        private fun save(r: List<Rule>) { prefs.edit().putString("rules", gson.toJson(r)).commit(); schedule(app) }
        fun add(r: Rule) = save(rules() + r)
        fun remove(index: Int): Rule? { val r = rules(); return r.getOrNull(index)?.also { save(r - it) } }
        fun clear() = save(emptyList())
        fun firedOn(): Map<Long, String> = runCatching { gson.fromJson<Map<Long, String>>(prefs.getString("fired", "{}"), object : TypeToken<Map<Long, String>>() {}.type) }.getOrNull().orEmpty()
        fun markFired(h: List<Hit>) { if (h.isNotEmpty()) prefs.edit().putString("fired", gson.toJson(firedOn() + h.associate { it.rule.id to it.date.toString() })).apply() }
        fun nextId() = (rules().maxOfOrNull { it.id } ?: 0) + 1
    }

    /** Checks the rules on the phone's latest data; notifies and remembers what fired. */
    fun run(ctx: Context): List<Hit> {
        val store = Store(ctx); val rules = store.rules(); if (rules.isEmpty()) return emptyList()
        val p = MarketPack.get(ctx).panel() ?: return emptyList()
        val hits = evaluate(rules, p, store.firedOn())
        store.markFired(hits); notify(ctx, hits)
        return hits
    }

    private fun notify(ctx: Context, hits: List<Hit>) {
        if (hits.isEmpty()) return
        if (android.os.Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("alerts", "Price alerts", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Your price alerts, checked on the phone" })
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, com.khayali.pulao.ai.ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        hits.forEachIndexed { i, h ->
            nm.notify(9100 + (h.rule.id % 900).toInt(), android.app.Notification.Builder(ctx, "alerts")
                .setSmallIcon(com.khayali.pulao.R.drawable.bubble_icon).setContentTitle("🔔 ${h.rule.symbol}").setContentText(h.text)
                .setContentIntent(open).setAutoCancel(true).setWhen(System.currentTimeMillis() + i).build())
        }
    }

    /** Schedules (or cancels) the background check: every ~3 hours, only with a network connection. */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (Store(ctx).rules().isEmpty()) { js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, Job::class.java))
            .setPeriodic(3 * 60 * 60 * 1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
    }
    private const val JOB_ID = 7401

    /** The background check: after 7 pm (Indian time), fetch the day's NSE file if it's new, then check the alerts. */
    class Job : JobService() {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        override fun onStartJob(params: JobParameters?): Boolean {
            scope.launch {
                runCatching {
                    val pack = MarketPack.get(applicationContext)
                    if (pack.installed && pack.install() > 0) {
                        var waited = 0
                        while (pack.busy && waited < 300) { delay(1000); waited++ }
                    }
                    run(applicationContext)
                }
                jobFinished(params, false)
            }
            return true
        }
        override fun onStopJob(params: JobParameters?) = true
    }
}

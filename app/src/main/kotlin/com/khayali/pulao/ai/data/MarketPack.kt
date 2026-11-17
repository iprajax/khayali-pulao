package com.khayali.pulao.ai.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The market-history pack: the user's own copy of NSE daily data for the last 1, 3 or 5 years,
 * downloaded by this phone and kept up to date. One [Artifacts] state machine drives it.
 */
class MarketPack private constructor(private val ctx: Context) {
    enum class Tier(val label: String, val years: Long, val approxMb: Int) { Y1("1 year", 1, 25), Y3("3 years", 3, 75), Y5("5 years", 5, 125) }

    private val prefs = ctx.getSharedPreferences("khayali", Context.MODE_PRIVATE)
    private val store get() = MarketStore.get(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow<Artifacts.State>(Artifacts.State.NotInstalled)
    val state: StateFlow<Artifacts.State> = _state
    @Volatile private var cached: Pair<Int, Panel>? = null

    var tier: Tier
        get() = runCatching { Tier.valueOf(prefs.getString("market_tier", "Y1")!!) }.getOrDefault(Tier.Y1)
        set(v) { prefs.edit().putString("market_tier", v.name).apply() }

    private val ist = ZoneId.of("Asia/Kolkata")
    /** NSE publishes a day's file in the evening; before 7 pm the latest complete day is yesterday. */
    private fun latest(): LocalDate { val now = java.time.ZonedDateTime.now(ist); return if (now.hour >= 19) now.toLocalDate() else now.toLocalDate().minusDays(1) }

    // Start from the last known summary (no database work on the main thread), then check in the background.
    init { _state.value = remembered(); refresh() }

    private fun remembered(): Artifacts.State {
        val f = prefs.getString("market_summary", null)?.split('|') ?: return Artifacts.State.NotInstalled
        return if (f.size == 4) Artifacts.State.Ready("${f[0]} to ${f[1]}", f[3].toLongOrNull() ?: 0, 0, "${f[2]} days") else Artifacts.State.NotInstalled
    }

    /** Re-checks the state against the database, off the main thread. */
    fun refresh() { scope.launch { refreshNow() } }

    @Synchronized private fun refreshNow() {
        if (_state.value.let { it is Artifacts.State.Downloading || it is Artifacts.State.Building || it is Artifacts.State.Updating }) return
        val last = store.lastDay()
        prefs.edit().apply { if (last == null) remove("market_summary") else putString("market_summary", "${store.firstDay()}|$last|${store.dayCount()}|${store.bytes(ctx)}") }.apply()
        _state.value = when {
            last == null -> Artifacts.State.NotInstalled
            todo(last.plusDays(1)).isNotEmpty() -> Artifacts.State.Stale(version(), store.bytes(ctx), "${todo(last.plusDays(1)).size} trading day(s) behind")
            else -> Artifacts.State.Ready(version(), store.bytes(ctx), System.currentTimeMillis(), "${store.dayCount()} days")
        }
    }

    private fun version() = "${store.firstDay()} to ${store.lastDay()}"
    private fun todo(from: LocalDate): List<LocalDate> {
        val have = store.haveDays(); val none = store.missingDays()
        return Bhavcopy.tradingDayCandidates(from, latest()).filter { it !in have && it !in none }
    }

    /** Downloads what's missing for the chosen tier (or only the new days, when already installed). */
    /** Returns how many days are left to fetch (0 = already up to date, -1 = a download is already running). */
    fun install(): Int {
        if (job?.isActive == true) return -1
        val start = store.firstDay()?.takeIf { it <= latest().minusYears(tier.years) } ?: latest().minusYears(tier.years)
        val days = todo(start)
        if (days.isEmpty()) { refresh(); return 0 }
        // extending or updating existing data keeps it usable while new days arrive; a first install downloads
        _state.value = if (store.lastDay() != null) Artifacts.State.Updating(0, days.size) else Artifacts.State.Downloading(0, days.size.toLong())
        job = scope.launch {
            try {
                var done = 0
                // a few at a time from the network, always added to the store in date order
                for (chunk in days.chunked(8)) {
                    if (!isActive) return@launch
                    val got = chunk.map { d -> async { d to com.khayali.pulao.ai.MarketFetcher.fetch(d) } }.awaitAll()
                    for ((d, r) in got.sortedBy { it.first }) when (r) { is com.khayali.pulao.ai.MarketFetcher.Day.Got -> store.add(d, r.bars); com.khayali.pulao.ai.MarketFetcher.Day.NoTrading -> store.markMissing(d) }
                    done += chunk.size
                    _state.value = Artifacts.next(_state.value, Artifacts.Ev.Progress(done.toLong(), days.size.toLong()))
                }
                cached = null                                     // the snapshot is extended with the new days on next use
                _state.value = Artifacts.State.Ready(version(), store.bytes(ctx), System.currentTimeMillis(), "${store.dayCount()} days")
                prefs.edit().putString("market_summary", "${store.firstDay()}|${store.lastDay()}|${store.dayCount()}|${store.bytes(ctx)}").apply()
                warm()
            } catch (t: Throwable) {
                _state.value = Artifacts.State.Failed(com.khayali.pulao.ai.Net.friendly(t, "NSE's archive"))
            }
        }
        return days.size
    }

    fun cancel() { job?.cancel(); cached = null; refresh() }
    fun delete() { job?.cancel(); cached = null; snapshot.delete(); store.wipe(ctx); prefs.edit().remove("market_summary").apply(); _state.value = Artifacts.State.NotInstalled }

    private val snapshot get() = java.io.File(ctx.filesDir, "market_panel.bin")

    /** Liquid stock symbols in the user's history (empty until the snapshot exists). */
    fun symbols(): Set<String> = cached?.second?.symbols?.toSet() ?: PanelFile.symbols(snapshot)

    /**
     * Up to five years of trading days (every analysis uses the latest day, so one panel serves all), from memory,
     * else from the snapshot file if it matches the database, else rebuilt from the database and saved.
     */
    @Synchronized fun panel(@Suppress("UNUSED_PARAMETER") days: Int = 300): Panel? {
        cached?.let { return it.second }
        val t0 = System.currentTimeMillis()
        val last = store.lastDay() ?: return null
        val need = minOf(store.dayCount(), FULL)
        val snap = PanelFile.read(snapshot)
        var how = "read from snapshot"
        val p = when {
            snap != null && snap.dates.last() == last && snap.days >= need -> snap
            // a day or two newer than the snapshot: add just those days (milliseconds, instead of rebuilding from every row)
            snap != null && snap.dates.last() < last && snap.days + store.daysAfter(snap.dates.last()) >= need ->
                store.since(snap.dates.last(), snap.symbols.toSet())?.let { PanelFile.append(snap, it, FULL) }?.also { PanelFile.write(it, snapshot); how = "snapshot extended" }
            else -> null
        } ?: store.panel(FULL)?.let { PanelFile.slim(it) }?.also { PanelFile.write(it, snapshot); how = "built from database" } ?: return null
        cached = p.days to p
        android.util.Log.i("KhayaliMarket", "panel ${p.days} days × ${p.symbols.size} stocks $how in ${System.currentTimeMillis() - t0} ms")
        return p
    }

    /** Builds the snapshot (and warms the backtests) in the background, so the first question after a download is quick. */
    private fun warm() { scope.launch { runCatching { panel()?.let { Market.backtestAll(it) } } } }

    companion object {
        /** Five years of trading days: enough for every strategy's warm-up plus four years of testing. */
        const val FULL = 1300
        @android.annotation.SuppressLint("StaticFieldLeak") @Volatile private var instance: MarketPack? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: MarketPack(ctx.applicationContext).also { instance = it } }
    }
}

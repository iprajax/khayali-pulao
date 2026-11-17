package com.khayali.pulao.ai.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The user's own copy of NSE's daily data, in a private SQLite database on the phone.
 * Days are added in date order; each stock's price is chained from NSE's adjusted previous close
 * as it is added, so splits and bonuses never look like crashes. Never backed up, never uploaded.
 */
class MarketStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "market.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE syms (id INTEGER PRIMARY KEY, sym TEXT UNIQUE NOT NULL, last_adj REAL, last_d INTEGER)")
        db.execSQL("CREATE TABLE bars (d INTEGER NOT NULL, s INTEGER NOT NULL, adj REAL, ret REAL, val REAL, vol REAL, PRIMARY KEY (d, s)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE days (d INTEGER PRIMARY KEY, n INTEGER)")
        db.execSQL("CREATE TABLE missing (d INTEGER PRIMARY KEY)")
    }
    override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) = Unit

    private fun key(day: LocalDate) = day.format(DateTimeFormatter.BASIC_ISO_DATE).toInt()
    private fun day(k: Int) = LocalDate.parse(k.toString(), DateTimeFormatter.BASIC_ISO_DATE)

    fun haveDays(): Set<LocalDate> = readableDatabase.rawQuery("SELECT d FROM days", null).use { c -> buildSet { while (c.moveToNext()) add(day(c.getInt(0))) } }
    fun missingDays(): Set<LocalDate> = readableDatabase.rawQuery("SELECT d FROM missing", null).use { c -> buildSet { while (c.moveToNext()) add(day(c.getInt(0))) } }
    fun lastDay(): LocalDate? = readableDatabase.rawQuery("SELECT MAX(d) FROM days", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) day(c.getInt(0)) else null }
    fun firstDay(): LocalDate? = readableDatabase.rawQuery("SELECT MIN(d) FROM days", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) day(c.getInt(0)) else null }
    fun dayCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM days", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    fun markMissing(d: LocalDate) { writableDatabase.insertWithOnConflict("missing", null, ContentValues().apply { put("d", key(d)) }, SQLiteDatabase.CONFLICT_IGNORE) }
    fun bytes(ctx: Context): Long = ctx.getDatabasePath("market.db").length()

    /** Adds one trading day. Must be called in date order for the price chaining to be right; a day already present is skipped. */
    @Synchronized fun add(d: LocalDate, bars: List<Bhavcopy.Bar>) {
        val k = key(d); val db = writableDatabase
        if (db.rawQuery("SELECT 1 FROM days WHERE d=?", arrayOf(k.toString())).use { it.moveToFirst() }) return
        db.beginTransaction()
        try {
            val ids = HashMap<String, Pair<Long, Double?>>()
            db.rawQuery("SELECT id, sym, last_adj FROM syms", null).use { c -> while (c.moveToNext()) ids[c.getString(1)] = c.getLong(0) to (if (c.isNull(2)) null else c.getDouble(2)) }
            val ins = db.compileStatement("INSERT OR REPLACE INTO bars (d, s, adj, ret, val, vol) VALUES (?, ?, ?, ?, ?, ?)")
            val upd = db.compileStatement("UPDATE syms SET last_adj=?, last_d=? WHERE id=?")
            for (b in bars) {
                var (id, lastAdj) = ids[b.symbol] ?: (db.insert("syms", null, ContentValues().apply { put("sym", b.symbol) }) to null).also { ids[b.symbol] = it }
                val r = if (b.prev > 0) b.close / b.prev - 1 else 0.0
                val adj = lastAdj?.let { it * (1 + r) } ?: b.close
                ins.clearBindings(); ins.bindLong(1, k.toLong()); ins.bindLong(2, id); ins.bindDouble(3, adj); ins.bindDouble(4, r); ins.bindDouble(5, b.value); ins.bindDouble(6, b.volume.toDouble()); ins.executeInsert()
                upd.clearBindings(); upd.bindDouble(1, adj); upd.bindLong(2, k.toLong()); upd.bindLong(3, id); upd.executeUpdateDelete()
            }
            db.insert("days", null, ContentValues().apply { put("d", k); put("n", bars.size) })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** The last [days] trading days as a [Panel] (null if there is no data yet). */
    fun panel(days: Int): Panel? {
        val db = readableDatabase
        val dates = db.rawQuery("SELECT d FROM days ORDER BY d DESC LIMIT ?", arrayOf(days.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }.reversed()
        if (dates.size < 2) return null
        val dIx = dates.withIndex().associate { it.value to it.index }
        val names = HashMap<Long, String>(); db.rawQuery("SELECT id, sym FROM syms", null).use { c -> while (c.moveToNext()) names[c.getLong(0)] = c.getString(1) }
        // stocks that traded at least ₹5 crore on some day in the window; the rest can never be "liquid" in the analysis
        val used = db.rawQuery("SELECT DISTINCT s FROM bars WHERE d >= ? AND val >= ?", arrayOf(dates.first().toString(), Market.LIQUID_VALUE.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        val syms = used.mapNotNull { names[it] }.sorted(); val pos = syms.withIndex().associate { it.value to it.index }; val sIx = HashMap<Long, Int>(); used.forEach { id -> names[id]?.let { n -> sIx[id] = pos.getValue(n) } }
        val n = dates.size
        val adj = Array(syms.size) { FloatArray(n) { Float.NaN } }; val ret = Array(syms.size) { FloatArray(n) { Float.NaN } }
        val v = Array(syms.size) { FloatArray(n) { Float.NaN } }; val q = Array(syms.size) { FloatArray(n) { Float.NaN } }
        db.rawQuery("SELECT d, s, adj, ret, val, vol FROM bars WHERE d >= ?", arrayOf(dates.first().toString())).use { c ->
            while (c.moveToNext()) { val d = dIx[c.getInt(0)] ?: continue; val s = sIx[c.getLong(1)] ?: continue
                adj[s][d] = c.getDouble(2).toFloat(); ret[s][d] = c.getDouble(3).toFloat(); v[s][d] = c.getDouble(4).toFloat(); q[s][d] = c.getDouble(5).toFloat() }
        }
        return Panel(dates.map(::day), syms, adj, ret, v, q)
    }

    fun wipe(ctx: Context) { close(); ctx.deleteDatabase("market.db"); instance = null }

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") @Volatile private var instance: MarketStore? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: MarketStore(ctx).also { instance = it } }
    }
}

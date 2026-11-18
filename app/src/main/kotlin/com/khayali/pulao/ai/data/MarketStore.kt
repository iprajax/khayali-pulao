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
class MarketStore private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "market.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE syms (id INTEGER PRIMARY KEY, sym TEXT UNIQUE NOT NULL, last_adj REAL, last_d INTEGER)")
        db.execSQL("CREATE TABLE bars (d INTEGER NOT NULL, s INTEGER NOT NULL, adj REAL, ret REAL, val REAL, vol REAL, PRIMARY KEY (d, s)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE days (d INTEGER PRIMARY KEY, n INTEGER)")
        db.execSQL("CREATE TABLE missing (d INTEGER PRIMARY KEY)")
        sectorTables(db)
        nameTable(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) { if (a < 2) sectorTables(db); if (a < 3) nameTable(db) }

    private fun nameTable(db: SQLiteDatabase) = db.execSQL("CREATE TABLE IF NOT EXISTS names (sym TEXT PRIMARY KEY, name TEXT NOT NULL) WITHOUT ROWID")

    @Synchronized fun setNames(names: Map<String, String>) {
        if (names.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try { names.forEach { (s, n) -> db.insertWithOnConflict("names", null, ContentValues().apply { put("sym", s); put("name", n) }, SQLiteDatabase.CONFLICT_REPLACE) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    fun names(): Map<String, String> = readableDatabase.rawQuery("SELECT sym, name FROM names", null).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) } }

    /** Official index closes (with P/E, P/B, dividend yield) per day, and each sector index's members. */
    private fun sectorTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS idx (d INTEGER NOT NULL, name TEXT NOT NULL, close REAL, pe REAL, pb REAL, dy REAL, PRIMARY KEY (d, name)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE IF NOT EXISTS members (idx TEXT NOT NULL, sym TEXT NOT NULL, PRIMARY KEY (idx, sym)) WITHOUT ROWID")
    }

    /** Stores one day's index closes; an empty list marks the day as checked (no file). */
    @Synchronized fun addIndices(d: LocalDate, rows: List<Bhavcopy.IndexClose>) {
        val db = writableDatabase; val k = key(d).toLong()
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT OR REPLACE INTO idx (d, name, close, pe, pb, dy) VALUES (?, ?, ?, ?, ?, ?)")
            if (rows.isEmpty()) { st.bindLong(1, k); st.bindString(2, ""); st.bindNull(3); st.bindNull(4); st.bindNull(5); st.bindNull(6); st.executeInsert() }
            for (r in rows) {
                st.clearBindings(); st.bindLong(1, k); st.bindString(2, r.name); st.bindDouble(3, r.close)
                r.pe?.let { st.bindDouble(4, it) } ?: st.bindNull(4); r.pb?.let { st.bindDouble(5, it) } ?: st.bindNull(5); r.dy?.let { st.bindDouble(6, it) } ?: st.bindNull(6)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Trading days already in the store that don't have their index file yet. */
    fun daysWithoutIndices(): List<LocalDate> = readableDatabase.rawQuery("SELECT d FROM days WHERE d NOT IN (SELECT DISTINCT d FROM idx) ORDER BY d", null)
        .use { c -> buildList { while (c.moveToNext()) add(day(c.getInt(0))) } }

    fun indexSeries(names: Collection<String>): Map<String, Sectors.Series> {
        val out = HashMap<String, MutableList<Pair<LocalDate, Double>>>()
        val qs = names.joinToString(",") { "?" }
        readableDatabase.rawQuery("SELECT name, d, close FROM idx WHERE name IN ($qs) AND close IS NOT NULL ORDER BY d", names.toTypedArray()).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getString(0)) { mutableListOf() } += day(c.getInt(1)) to c.getDouble(2)
        }
        return out.mapValues { Sectors.Series(it.value) }
    }

    /** Latest P/E, P/B and dividend yield for each index. */
    fun indexMeta(names: Collection<String>): Map<String, Triple<Double?, Double?, Double?>> {
        val qs = names.joinToString(",") { "?" }
        return readableDatabase.rawQuery("SELECT name, pe, pb, dy FROM idx WHERE name IN ($qs) AND d = (SELECT MAX(d) FROM idx WHERE name <> '') ", names.toTypedArray()).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), Triple(c.dn(1), c.dn(2), c.dn(3))) }
        }
    }
    private fun android.database.Cursor.dn(i: Int) = if (isNull(i)) null else getDouble(i)

    @Synchronized fun setMembers(index: String, syms: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try { db.delete("members", "idx = ?", arrayOf(index)); syms.forEach { db.insert("members", null, ContentValues().apply { put("idx", index); put("sym", it) }) }; db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    fun members(): Map<String, List<String>> = readableDatabase.rawQuery("SELECT idx, sym FROM members ORDER BY idx, sym", null).use { c ->
        val m = LinkedHashMap<String, MutableList<String>>(); while (c.moveToNext()) m.getOrPut(c.getString(0)) { mutableListOf() } += c.getString(1); m }

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

    fun daysAfter(after: LocalDate): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM days WHERE d > ?", arrayOf(key(after).toString())).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Only the trading days after [after], for [keep] symbols plus any that traded at least ₹5 crore on one of those days.
     * Used to extend the snapshot with a day or two of new data instead of rebuilding it from millions of rows.
     */
    fun since(after: LocalDate, keep: Set<String>): Panel? {
        val db = readableDatabase; val from = key(after).toString()
        val dates = db.rawQuery("SELECT d FROM days WHERE d > ? ORDER BY d", arrayOf(from)).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }
        if (dates.isEmpty()) return null
        val names = HashMap<Long, String>(); db.rawQuery("SELECT id, sym FROM syms", null).use { c -> while (c.moveToNext()) names[c.getLong(0)] = c.getString(1) }
        val liquid = db.rawQuery("SELECT DISTINCT s FROM bars WHERE d > ? AND val >= ?", arrayOf(from, Market.LIQUID_VALUE.toString())).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
        val used = names.filter { (id, n) -> n in keep || id in liquid }.keys.toList()
        return load(dates, used, names, from, ">")
    }

    /** The last [days] trading days as a [Panel] (null if there is no data yet). */
    fun panel(days: Int): Panel? {
        val db = readableDatabase
        val dates = db.rawQuery("SELECT d FROM days ORDER BY d DESC LIMIT ?", arrayOf(days.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }.reversed()
        if (dates.size < 2) return null
        val names = HashMap<Long, String>(); db.rawQuery("SELECT id, sym FROM syms", null).use { c -> while (c.moveToNext()) names[c.getLong(0)] = c.getString(1) }
        // stocks that traded at least ₹5 crore on some day in the window; the rest can never be "liquid" in the analysis
        val used = db.rawQuery("SELECT DISTINCT s FROM bars WHERE d >= ? AND val >= ?", arrayOf(dates.first().toString(), Market.LIQUID_VALUE.toString())).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        return load(dates, used, names, dates.first().toString(), ">=")
    }

    private fun load(dates: List<Int>, used: List<Long>, names: Map<Long, String>, from: String, op: String): Panel {
        val db = readableDatabase
        val dIx = dates.withIndex().associate { it.value to it.index }
        val syms = used.mapNotNull { names[it] }.sorted(); val pos = syms.withIndex().associate { it.value to it.index }; val sIx = HashMap<Long, Int>(); used.forEach { id -> names[id]?.let { n -> sIx[id] = pos.getValue(n) } }
        val n = dates.size
        val adj = Array(syms.size) { FloatArray(n) { Float.NaN } }; val ret = Array(syms.size) { FloatArray(n) { Float.NaN } }
        val v = Array(syms.size) { FloatArray(n) { Float.NaN } }; val q = Array(syms.size) { FloatArray(n) { Float.NaN } }
        db.rawQuery("SELECT d, s, adj, ret, val, vol FROM bars WHERE d $op ?", arrayOf(from)).use { c ->
            while (c.moveToNext()) { val d = dIx[c.getInt(0)] ?: continue; val s = sIx[c.getLong(1)] ?: continue
                adj[s][d] = c.getDouble(2).toFloat(); ret[s][d] = c.getDouble(3).toFloat(); v[s][d] = c.getDouble(4).toFloat(); q[s][d] = c.getDouble(5).toFloat() }
        }
        return Panel(dates.map(::day), syms, adj, ret, v, q)
    }

    /** Deletes days before [before] (prices after them stay chained correctly: each row already holds its adjusted price). */
    @Synchronized fun prune(before: LocalDate): Int {
        val k = key(before).toString(); val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("bars", "d < ?", arrayOf(k)); db.delete("missing", "d < ?", arrayOf(k))
            val n = db.delete("days", "d < ?", arrayOf(k)); db.setTransactionSuccessful(); return n
        } finally { db.endTransaction() }
    }

    /** Reclaims the space left by deleted rows. */
    fun compact() { writableDatabase.execSQL("VACUUM") }

    fun wipe(ctx: Context) { close(); ctx.deleteDatabase("market.db"); instance = null }

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") @Volatile private var instance: MarketStore? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: MarketStore(ctx).also { instance = it } }
    }
}

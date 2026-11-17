package com.khayali.pulao.ai.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate

/**
 * A compact snapshot of the analysis panel, so opening it takes one file read instead of millions of
 * database rows. Pure Java I/O on a private file; rebuilt whenever the market history changes.
 */
object PanelFile {
    private const val MAGIC = 0x4B504E31          // "KPN1"

    /** Keeps only stocks that were liquid at some month-end — the only ones any analysis can pick. */
    fun slim(p: Panel): Panel {
        val keep = sortedSetOf<Int>()
        for (d in Market.monthEnds(p) + (p.days - 1)) keep += Market.liquid(p, d)
        val ix = keep.toList()
        return Panel(p.dates, ix.map { p.symbols[it] }, Array(ix.size) { p.adj[ix[it]] }, Array(ix.size) { p.ret[ix[it]] }, Array(ix.size) { p.value[ix[it]] }, Array(ix.size) { p.volume[ix[it]] })
    }

    /**
     * [old] extended with the days in [tail] that come after it, keeping at most [max] days (the oldest drop off).
     * Symbols are the union; a symbol missing on a day is NaN, as everywhere else.
     */
    fun append(old: Panel, tail: Panel, max: Int): Panel {
        val newDays = tail.dates.withIndex().filter { it.value > old.dates.last() }.map { it.index }
        if (newDays.isEmpty()) return old
        val syms = (old.symbols + tail.symbols).distinct().sorted()
        val oi = old.symbols.withIndex().associate { it.value to it.index }; val ti = tail.symbols.withIndex().associate { it.value to it.index }
        val total = old.days + newDays.size; val drop = maxOf(0, total - max); val n = total - drop
        fun merge(pick: (Panel) -> Array<FloatArray>) = Array(syms.size) { s ->
            val o = oi[syms[s]]; val t = ti[syms[s]]
            FloatArray(n) { k -> val d = k + drop
                if (d < old.days) (if (o != null) pick(old)[o][d] else Float.NaN) else (if (t != null) pick(tail)[t][newDays[d - old.days]] else Float.NaN) }
        }
        val dates = (old.dates + newDays.map { tail.dates[it] }).drop(drop)
        return Panel(dates, syms, merge { it.adj }, merge { it.ret }, merge { it.value }, merge { it.volume })
    }

    fun write(p: Panel, file: File) {
        val tmp = File(file.path + ".part")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { o ->
            o.writeInt(MAGIC); o.writeInt(p.days); o.writeInt(p.symbols.size)
            p.dates.forEach { o.writeLong(it.toEpochDay()) }
            p.symbols.forEach { o.writeUTF(it) }
            val buf = ByteBuffer.allocate(p.days * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (arrs in listOf(p.adj, p.ret, p.value, p.volume)) for (a in arrs) { buf.clear(); buf.asFloatBuffer().put(a); o.write(buf.array(), 0, p.days * 4) }
        }
        tmp.renameTo(file)
    }

    /** Just the symbols, read from the header — cheap enough for the main thread. */
    fun symbols(file: File): Set<String> = runCatching {
        DataInputStream(file.inputStream().buffered(1 shl 14)).use { i ->
            if (i.readInt() != MAGIC) return emptySet()
            val n = i.readInt(); val m = i.readInt(); i.skipBytes(n * 8)
            buildSet { repeat(m) { add(i.readUTF()) } }
        }
    }.getOrDefault(emptySet())

    fun read(file: File): Panel? = runCatching {
        DataInputStream(file.inputStream().buffered(1 shl 16)).use { i ->
            if (i.readInt() != MAGIC) return null
            val n = i.readInt(); val m = i.readInt()
            val dates = List(n) { LocalDate.ofEpochDay(i.readLong()) }
            val syms = List(m) { i.readUTF() }
            val bytes = ByteArray(n * 4)
            fun block() = Array(m) { i.readFully(bytes); FloatArray(n).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) } }
            val adj = block(); val ret = block(); val value = block(); val vol = block()
            Panel(dates, syms, adj, ret, value, vol)
        }
    }.getOrNull()
}

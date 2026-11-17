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

package com.khayali.pulao

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout

/** All user controls. Used in the app screen and inside the overlay (⚙). Writes straight to [Prefs]. */
object SettingsPanel {

    fun build(ctx: Context, prefs: Prefs, onChange: () -> Unit, onReset: () -> Unit): LinearLayout {
        val k = Kit(ctx)
        val col = k.column()
        fun gap() = col.addView(k.space(12))
        fun LinearLayout.label(s: String) {
            addView(k.text(s, 13f, Pal.MUTED, 500)); addView(k.space(8))
        }

        col.addView(k.section("Ladder steps") {
            label("Gains (%)")
            addView(k.input(prefs.gainsText, Levels.DEFAULT_GAINS, numeric = false) { prefs.gainsText = it; onChange() })
            addView(k.space(4))
            addView(k.switchRow("Show losses too", "Keep the daydream honest", prefs.showLosses) {
                prefs.showLosses = it; onChange()
            })
            label("Losses (%)")
            addView(k.input(prefs.lossesText, Levels.DEFAULT_LOSSES, numeric = false) { prefs.lossesText = it; onChange() })
            addView(k.text("Comma-separated. Decimals like 2.5 work.", 11f, Pal.DIM).apply { setPadding(0, k.dp(8), 0, 0) })
        })
        gap()

        col.addView(k.section("Holdings") {
            label("Start the daydream from")
            addView(k.segmented(listOf("My avg price", "Today's LTP"), prefs.base.ordinal) {
                prefs.base = Base.entries[it]; onChange()
            })
        })
        gap()

        col.addView(k.section("Watchlist") {
            label("Daydream by")
            addView(k.segmented(listOf("₹ Amount", "Shares"), prefs.watchMode.ordinal) {
                prefs.watchMode = QtyMode.entries[it]; onChange()
            })
            addView(k.space(12))
            val r = k.row()
            val a = k.column().apply {
                addView(k.text("Default ₹ amount", 12f, Pal.MUTED)); addView(k.space(6))
                addView(k.input(Fmt.qty(prefs.watchAmount), "10000") { t ->
                    t.toDoubleOrNull()?.takeIf { it > 0 }?.let { prefs.watchAmount = it; onChange() }
                })
            }
            val s = k.column().apply {
                addView(k.text("Default shares", 12f, Pal.MUTED)); addView(k.space(6))
                addView(k.input(Fmt.qty(prefs.watchShares), "10") { t ->
                    t.toDoubleOrNull()?.takeIf { it > 0 }?.let { prefs.watchShares = it; onChange() }
                })
            }
            r.addView(a, k.lp(0, WRAP_CONTENT, 1f)); r.addView(k.space(1, 12)); r.addView(s, k.lp(0, WRAP_CONTENT, 1f))
            addView(r, k.lp(MATCH_PARENT))
        })
        gap()

        col.addView(k.section("Numbers & slider") {
            label("Big numbers")
            addView(k.segmented(listOf("1,23,45,678", "1.23 Cr"), if (prefs.compact) 1 else 0) {
                prefs.compact = it == 1; onChange()
            })
            addView(k.space(14))
            label("Slider goes up to")
            val maxes = listOf(50, 100, 200, 500)
            addView(k.segmented(maxes.map { "+$it%" }, maxes.indexOf(prefs.sliderMax).coerceAtLeast(0)) {
                prefs.sliderMax = maxes[it]; onChange()
            })
        })
        gap()

        col.addView(k.section("Tone & daydream extras") {
            addView(k.switchRow("Pulao maths", "Gains in cutting chais, Bhrigu Express tickets, bhainses, plots in Ballia & Jhumri Telaiya (off in Serious)", prefs.funOn) {
                prefs.funOn = it; onChange()
            })
            label("Tone")
            addView(k.segmented(listOf("Serious", "Light", "Masala 🌶️"), prefs.tone.ordinal) {
                prefs.tone = Tone.entries[it]; onChange()
            })
            addView(k.text("Serious: numbers only.  Light: calm one-liners.  Masala: FD wale uncle & Sharma ji ka beta comment on your gains.",
                11f, Pal.DIM).apply { setPadding(0, k.dp(8), 0, k.dp(4)); setLineSpacing(0f, 1.3f) })
            addView(k.space(8))
            label("My own dream unit (e.g. Thar for the farm, Kedarnath trip)")
            val dr = k.row()
            dr.addView(k.input(prefs.customName, "name", numeric = false) { prefs.customName = it; onChange() },
                k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
            dr.addView(k.space(1, 8))
            dr.addView(k.input(if (prefs.customPrice > 0) Fmt.qty(prefs.customPrice) else "", "₹ price") { t ->
                prefs.customPrice = t.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0; onChange()
            }, k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(dr, k.lp(MATCH_PARENT))
            addView(k.space(10))
            addView(k.space(6))
            label("Default yearly return (Goal & Years tabs)")
            val rates = listOf(8.0, 12.0, 15.0, 20.0)
            addView(k.segmented(rates.map { Fmt.pct(it).removePrefix("+") }, rates.indexOf(prefs.cagr).coerceAtLeast(1)) {
                prefs.cagr = rates[it]; onChange()
            })
        })
        gap()

        col.addView(k.section("Book it? — in-hand after tax") {
            addView(k.switchRow("Show in-hand amount", "Gain after tax, STT & DP charges (approx.)", prefs.taxOn) {
                prefs.taxOn = it; onChange()
            })
            addView(k.space(6))
            label("If I haven't said how long I've held it, assume")
            addView(k.segmented(listOf("LTCG 12.5%\n₹1.25L free", "STCG 20%", "Slab 30%"), prefs.taxKind.ordinal) {
                prefs.taxKind = TaxKind.entries[it]; onChange()
            })
            addView(k.text("Held over a year (set in the vs FD tab) → LTCG automatically, under a year → STCG.", 11f, Pal.DIM).apply {
                setPadding(0, k.dp(8), 0, 0); setLineSpacing(0f, 1.3f)
            })
        })
        gap()

        col.addView(k.section("Compare against") {
            fun numRow(title: String, value: Double, set: (Double) -> Unit) {
                val r = k.row().apply { setPadding(0, k.dp(4), 0, k.dp(4)) }
                r.addView(k.text(title, 14f, Pal.TEXT), k.lp(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                r.addView(k.input(Fmt.qty(value), "") { t -> t.toDoubleOrNull()?.takeIf { it >= 0 }?.let { set(it); onChange() } }, k.lp(k.dp(120)))
                addView(r, k.lp(MATCH_PARENT))
            }
            numRow("🧾 Bank FD (% / yr)", prefs.fdRate) { prefs.fdRate = it }
            numRow("🏦 Savings account (% / yr)", prefs.savingsRate) { prefs.savingsRate = it }
            numRow("📮 PPF (% / yr)", prefs.ppfRate) { prefs.ppfRate = it }
            numRow("🪙 Gold, assumed (% / yr)", prefs.goldRate) { prefs.goldRate = it }
            numRow("📈 Nifty 50, assumed (% / yr)", prefs.niftyRate) { prefs.niftyRate = it }
            numRow("🛒 Inflation (% / yr)", prefs.inflation) { prefs.inflation = it }
            numRow("🛋️ Monthly expenses (₹)", prefs.monthlyExpense) { prefs.monthlyExpense = it }
            addView(k.space(8))
            addView(k.button("Reset to current rates (${Rates.AS_OF})", primary = false) { prefs.resetRates(); onReset() }, k.lp(MATCH_PARENT))
            addView(k.text(Rates.NOTE, 11f, Pal.DIM).apply {
                setPadding(0, k.dp(8), 0, 0); setLineSpacing(0f, 1.3f)
            })
        })
        gap()

        col.addView(k.section("Bubble") {
            label("Size")
            val sizes = listOf("S", "M", "L")
            addView(k.segmented(listOf("Small", "Medium", "Large"), sizes.indexOf(prefs.bubbleSize).coerceAtLeast(1)) {
                prefs.bubbleSize = sizes[it]; onChange()
            })
            addView(k.space(6))
            addView(k.switchRow("Live prices", "Card follows Kite's ticking prices", prefs.live) { prefs.live = it; onChange() })
            addView(k.switchRow("Haptics", "Little taps as you slide", prefs.haptics) { prefs.haptics = it; onChange() })
            addView(k.text("Tip: long-press the bubble for settings, drag it onto ✕ to hide it.", 12f, Pal.DIM).apply {
                setPadding(0, k.dp(6), 0, 0)
            })
        })
        gap()

        col.addView(k.section(Privacy.TITLE) {
            for (pt in Privacy.POINTS) addView(k.text("• $pt", 12f, Pal.MUTED).apply {
                setPadding(0, k.dp(3), 0, k.dp(3)); setLineSpacing(0f, 1.3f)
            })
        })
        gap()
        col.addView(k.button("Reset everything to defaults", primary = false) { prefs.reset(); onReset() })
        return col
    }
}

package com.khayali.pulao

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Everything the user can control, persisted on the phone. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("khayali", Context.MODE_PRIVATE)

    var gainsText by str("gains", Levels.DEFAULT_GAINS)
    var lossesText by str("losses", Levels.DEFAULT_LOSSES)
    var showLosses by bool("showLosses", true)

    var baseName by str("base", Base.AVG.name)
    var base: Base
        get() = runCatching { Base.valueOf(baseName) }.getOrDefault(Base.AVG)
        set(v) { baseName = v.name }

    var modeName by str("watchMode", QtyMode.AMOUNT.name)
    var watchMode: QtyMode
        get() = runCatching { QtyMode.valueOf(modeName) }.getOrDefault(QtyMode.AMOUNT)
        set(v) { modeName = v.name }

    var watchAmount by dbl("watchAmount", 10_000.0)
    var watchShares by dbl("watchShares", 10.0)
    val watchInput get() = if (watchMode == QtyMode.AMOUNT) watchAmount else watchShares

    var compact by bool("compact", false)
    var sliderMax by int("sliderMax", 100)
    var taxOn by bool("taxOn", false)
    var taxRate by dbl("taxRate", 20.0)

    /** Sheet tab: MOVE, GOAL, YEARS, REGRET. */
    var tab by str("tab", "MOVE")
    var cagr by dbl("cagr", 12.0)
    var years by int("years", 10)
    var funOn by bool("funOn", false)
    var personaName by str("persona", "ANALYST")
    var disclaimerSeen by bool("disclaimerSeen", false)

    var fdRate by dbl("fdRate", Rates.FD)
    var savingsRate by dbl("savingsRate", Rates.SAVINGS)
    var ppfRate by dbl("ppfRate", Rates.PPF)
    var goldRate by dbl("goldRate", Rates.GOLD)
    var niftyRate by dbl("niftyRate", Rates.NIFTY)
    var toneName by str("tone", Tone.LIGHT.name)
    var tone: Tone
        get() = runCatching { Tone.valueOf(toneName) }.getOrDefault(Tone.LIGHT)
        set(v) { toneName = v.name }
    /** Desi units only make sense when not in Serious mode. */
    val showPulao get() = funOn && tone != Tone.SERIOUS
    var customName by str("customName", "")
    var customPrice by dbl("customPrice", 0.0)

    /** The user's own dream unit, e.g. "Thar for the farm" at ₹15L. */
    val customItem: PulaoMaths.Item?
        get() = if (customName.isBlank() || customPrice <= 0) null
        else PulaoMaths.Item(customName.trim(), customName.trim(), "⭐", customPrice)

    fun benches() = Bench.list(fdRate, inflation, savingsRate, ppfRate, goldRate, niftyRate)
    var inflation by dbl("inflation", Rates.INFLATION)
    var monthlyExpense by dbl("monthlyExpense", 50_000.0)
    var taxKindName by str("taxKind", TaxKind.LTCG.name)
    var taxKind: TaxKind
        get() = runCatching { TaxKind.valueOf(taxKindName) }.getOrDefault(TaxKind.LTCG)
        set(v) { taxKindName = v.name }

    /** How long you've held a stock (years), remembered per name. Null = not told yet. */
    fun held(name: String): Double? =
        if (sp.contains("held:$name")) java.lang.Double.longBitsToDouble(sp.getLong("held:$name", 0)) else null
    fun setHeld(name: String, years: Double) =
        sp.edit().putLong("held:$name", java.lang.Double.doubleToRawLongBits(years)).apply()

    var bubbleSize by str("bubbleSize", "M")
    var haptics by bool("haptics", true)
    var live by bool("live", true)
    var lastPct by dbl("lastPct", 10.0)
    var bubbleX by int("bubbleX", -1)
    var bubbleY by int("bubbleY", -1)

    fun ladder() = Levels.ladder(Levels.parse(gainsText), Levels.parse(lossesText), showLosses)

    fun reset() = sp.edit().clear().apply()

    /** Restore researched comparison rates (keeps everything else). */
    fun resetRates() = sp.edit().remove("fdRate").remove("savingsRate").remove("ppfRate").remove("goldRate")
        .remove("niftyRate").remove("inflation").apply()

    // --- tiny delegates -----------------------------------------------------
    private fun str(k: String, d: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getString(k, d) ?: d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) = sp.edit().putString(k, value).apply()
    }

    private fun bool(k: String, d: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = sp.edit().putBoolean(k, value).apply()
    }

    private fun int(k: String, d: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(k, d)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = sp.edit().putInt(k, value).apply()
    }

    private fun dbl(k: String, d: Double) = object : ReadWriteProperty<Any?, Double> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) =
            if (sp.contains(k)) java.lang.Double.longBitsToDouble(sp.getLong(k, 0)) else d
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Double) =
            sp.edit().putLong(k, java.lang.Double.doubleToRawLongBits(value)).apply()
    }
}

package com.khayali.pulao.ai

import com.khayali.pulao.Prefs
import com.khayali.pulao.Stock
import com.khayali.pulao.Tone

/** [ToolHost] over the real app settings. Validates every write so the model can't set nonsense. */
class PrefsToolHost(
    private val prefs: Prefs,
    private val stockProvider: () -> Stock?,
    private val opener: (String) -> Boolean,
    private val holdingsProvider: () -> List<Stock.Holding> = { emptyList() },
) : ToolHost {

    override val holdings: List<Stock.Holding> get() = holdingsProvider()

    override val stock: Stock? get() = stockProvider()

    override fun setting(name: String): Any? = when (name) {
        "fd_rate" -> prefs.fdRate
        "savings_rate" -> prefs.savingsRate
        "ppf_rate" -> prefs.ppfRate
        "gold_rate" -> prefs.goldRate
        "nifty_rate" -> prefs.niftyRate
        "inflation_rate" -> prefs.inflation
        "monthly_expense" -> prefs.monthlyExpense
        "watch_amount" -> prefs.watchAmount
        "tone" -> prefs.tone.name.lowercase()
        "compact_numbers" -> prefs.compact
        "show_in_hand" -> prefs.taxOn
        "dream_unit" -> prefs.customItem?.let { "${it.one}|${it.price.toLong()}" }
        else -> null
    }

    override fun updateSetting(name: String, value: String): String? {
        val v = value.trim()
        fun rate(set: (Double) -> Unit): String? {
            val d = v.removeSuffix("%").trim().toDoubleOrNull() ?: return "'$value' isn't a number"
            if (d < 0 || d > 50) return "A yearly rate should be between 0 and 50%"
            set(d); return null
        }
        fun money(set: (Double) -> Unit): String? {
            val d = v.replace(",", "").replace("₹", "").trim().toDoubleOrNull() ?: return "'$value' isn't an amount"
            if (d <= 0 || d > 1e10) return "That amount looks off"
            set(d); return null
        }
        fun bool(set: (Boolean) -> Unit): String? = when (v.lowercase()) {
            "true", "on", "yes", "1" -> { set(true); null }
            "false", "off", "no", "0" -> { set(false); null }
            else -> "Use on/off"
        }
        return when (name) {
            "fd_rate" -> rate { prefs.fdRate = it }
            "savings_rate" -> rate { prefs.savingsRate = it }
            "ppf_rate" -> rate { prefs.ppfRate = it }
            "gold_rate" -> rate { prefs.goldRate = it }
            "nifty_rate" -> rate { prefs.niftyRate = it }
            "inflation_rate" -> rate { prefs.inflation = it }
            "monthly_expense" -> money { prefs.monthlyExpense = it }
            "watch_amount" -> money { prefs.watchAmount = it }
            "tone" -> Tone.entries.firstOrNull { it.name.equals(v, true) }?.let { prefs.tone = it; null }
                ?: "Tone must be serious, light or masala"
            "compact_numbers" -> bool { prefs.compact = it }
            "show_in_hand" -> bool { prefs.taxOn = it }
            "dream_unit" -> {
                val parts = v.split("|")
                val price = parts.getOrNull(1)?.replace(",", "")?.replace("₹", "")?.trim()?.toDoubleOrNull()
                if (parts[0].isBlank() || price == null || price <= 0) "Use 'name|price', e.g. 'Thar for the farm|1500000'"
                else { prefs.customName = parts[0].trim().take(40); prefs.customPrice = price; null }
            }
            else -> "Unknown setting '$name'"
        }
    }

    override fun open(target: String): Boolean = opener(target.lowercase().trim())
}

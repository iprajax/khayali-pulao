package com.khayali.pulao.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khayali.pulao.Fmt

/**
 * Follow-up pills that re-run the LAST calculation with one value changed — executed by the
 * app, not interpreted by the model, so "At 15% instead" always means exactly that.
 */
object FollowUps {
    data class Pill(val label: String, val tool: String, val args: String)

    private fun with(o: JsonObject, vararg kv: Pair<String, Any>): String {
        val c = o.deepCopy()
        for ((k, v) in kv) when (v) { is Number -> c.addProperty(k, v); else -> c.addProperty(k, v.toString()) }
        return c.toString()
    }

    private fun JsonObject.num(k: String): Double? = get(k)?.takeIf { !it.isJsonNull }?.let {
        if (it.asJsonPrimitive.isNumber) it.asDouble else Money.parse(it.asString)
    }

    fun from(tool: String, argsJson: String): List<Pill> {
        val a = runCatching { JsonParser.parseString(argsJson).asJsonObject }.getOrNull() ?: return emptyList()
        fun pct(v: Double) = Fmt.pct(v)
        return when (tool) {
            "grow_for_years" -> {
                val r = a.num("yearly_return_percent") ?: 12.0; val y = a.num("years") ?: 10.0
                listOf(8.0, 15.0, 20.0).filter { it != r }.take(2).map { Pill("At ${Fmt.qty(it)}% a year", tool, with(a, "yearly_return_percent" to it)) } +
                    listOf(20.0, 30.0).filter { it != y }.take(1).map { Pill("For ${Fmt.qty(it)} years", tool, with(a, "years" to it)) }
            }
            "project_move" -> {
                val p = a.num("percent") ?: 10.0
                listOf(-10.0, 25.0, 50.0).filter { it != p }.map { Pill("If it moves ${pct(it)}", tool, with(a, "percent" to it)) }
            }
            "goal_plan" -> {
                val g = a.num("target_rupees") ?: return emptyList()
                listOf(2.0, 0.5).map { m -> Pill("Goal ₹${Fmt.money(g * m, true)}", tool, with(a, "target_rupees" to g * m)) }
            }
            "compare_with_fd" -> {
                val y = a.num("years_held") ?: 2.0
                listOf(1.0, 3.0, 5.0).filter { it != y }.map { Pill("Held ${Fmt.qty(it)} ${if (it == 1.0) "year" else "years"}", tool, with(a, "years_held" to it)) }
            }
            "regret" -> {
                val p = a.num("past_price") ?: return emptyList()
                listOf(0.8, 0.6).map { f -> Pill("Bought at ₹${Fmt.price(p * f)}", tool, with(a, "past_price" to p * f)) }
            }
            "in_hand_after_tax" -> listOf(
                Pill("If held under a year (STCG)", tool, with(a, "held_over_one_year" to "false")),
                Pill("If held over a year (LTCG)", tool, with(a, "held_over_one_year" to "true")),
            )
            else -> emptyList()
        }
    }
}

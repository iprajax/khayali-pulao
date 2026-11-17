package com.khayali.pulao.ai

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * What a tool returns goes two ways: the full JSON draws the card, and a COMPACT copy goes back
 * to the model. A small on-device model has a small context, so chart points, links and long
 * lists are left out of its copy — the card already shows them.
 */
object ToolJson {
    private val DROP = setOf("points", "url", "token", "exchange", "instructions_for_ui", "day_change_percent", "today")
    const val MAX_ROWS = 10

    fun compact(json: String, maxRows: Int = MAX_ROWS): String {
        val el = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return json.take(1500)
        return shrink(el, maxRows).toString()
    }

    private fun shrink(e: JsonElement, maxRows: Int): JsonElement = when {
        e.isJsonObject -> JsonObject().also { o ->
            for ((k, v) in e.asJsonObject.entrySet()) {
                if (k in DROP) continue
                if (v.isJsonArray && v.asJsonArray.size() > maxRows) o.addProperty("${k}_shown", "$maxRows of ${v.asJsonArray.size()}")
                o.add(k, shrink(v, maxRows))
            }
        }
        e.isJsonArray -> JsonArray().also { a -> e.asJsonArray.take(maxRows).forEach { a.add(shrink(it, maxRows)) } }
        e.isJsonPrimitive && e.asJsonPrimitive.isString && e.asString.length > 400 -> com.google.gson.JsonPrimitive(e.asString.take(400))
        else -> e
    }

    /** A terminal result without its drawing instructions — the model needs the summary, not the chart data. */
    fun withoutCard(json: String): String = runCatching { JsonParser.parseString(json).asJsonObject.apply { remove("card"); remove("pills") }.toString() }.getOrDefault(json)

    fun summary(json: String): String? = runCatching { JsonParser.parseString(json).asJsonObject["summary"]?.asString }.getOrNull()
    fun error(json: String): String? = runCatching { JsonParser.parseString(json).asJsonObject["error"]?.asString }.getOrNull()
    fun ok(json: String) = error(json) == null
}

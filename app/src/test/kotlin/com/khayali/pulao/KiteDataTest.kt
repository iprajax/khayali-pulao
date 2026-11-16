package com.khayali.pulao

import com.google.gson.JsonParser
import com.khayali.pulao.ai.KiteData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kite MCP helpers. All data below is fictional. */
class KiteDataTest {

    @Test fun readOnlyAllowlistBlocksEveryWrite() {
        // Writes live under the same tool names with a different mode — all must be refused.
        listOf("orders" to "place", "orders" to "modify", "orders" to "cancel", "gtt" to "place", "gtt" to "modify",
            "gtt" to "delete", "alerts" to "create", "alerts" to "modify", "alerts" to "delete", "session" to "logout")
            .forEach { (t, m) -> assertFalse("$t/$m", KiteData.allowed(t, m)) }
        assertFalse(KiteData.allowed("orders", null))           // no mode → refused
        assertFalse(KiteData.allowed("place_order", "any"))     // unknown tool → refused
        assertTrue(KiteData.allowed("portfolio", "holdings"))
        assertTrue(KiteData.SECTIONS.values.all { (t, m) -> KiteData.allowed(t, m) })
    }

    @Test fun summarizesCurrentKiteShape() {
        // Kite MCP now wraps rows as {data:[…], pagination:{…}}. Fictional values.
        val raw = """{"data":[{"tradingsymbol":"INFRA","quantity":250,"t1_quantity":0,"average_price":1200,"last_price":1450}],
                      "pagination":{"from":0,"limit":500,"total":1,"has_more":false,"returned":1}}"""
        val o = JsonParser.parseString(KiteData.summarize("holdings", raw)).asJsonObject
        assertEquals(1, o["count"].asInt)
        assertEquals(362500.0, o["current"].asDouble, 0.0)
    }

    @Test fun parsesJsonAndSseReplies() {
        val json = """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"hello"}]}}"""
        assertEquals("hello", KiteData.resultText(KiteData.parseRpc(json)!!))
        val sse = "event: message\ndata: $json\n\n"
        assertEquals("hello", KiteData.resultText(KiteData.parseRpc(sse)!!))
        val err = """{"jsonrpc":"2.0","id":4,"result":{"isError":true,"content":[{"type":"text","text":"Please log in"}]}}"""
        assertTrue(KiteData.resultText(KiteData.parseRpc(err)!!).contains("error"))
    }

    @Test fun findsLoginLink() {
        val t = "Please open https://kite.zerodha.com/connect/login?api_key=abc&v=3 to log in."
        assertEquals("https://kite.zerodha.com/connect/login?api_key=abc&v=3", KiteData.loginUrl(t))
        assertTrue(KiteData.looksLoggedOut("Error: not logged in"))
    }

    @Test fun summarizesHoldings() {
        val raw = """[{"tradingsymbol":"INFRA","quantity":250,"t1_quantity":0,"average_price":1200,"last_price":1450,"day_change_percentage":1.2},
                      {"tradingsymbol":"CHAICO","quantity":10,"t1_quantity":0,"average_price":700,"last_price":658.2}]"""
        val o = JsonParser.parseString(KiteData.summarize("holdings", raw)).asJsonObject
        assertEquals(2, o["count"].asInt)
        assertEquals(307000.0, o["invested"].asDouble, 0.0)
        assertEquals(369082.0, o["current"].asDouble, 0.0)
        assertEquals("INFRA", o.getAsJsonArray("holdings")[0].asJsonObject["symbol"].asString)
        assertEquals(98.2, o["largest_weight_percent"].asDouble, 0.1)
        assertTrue(o["summary"].asString.contains("₹3,69,082"))
    }

    @Test fun profileNeverLeaksContactDetails() {
        val raw = """{"user_name":"Test Person","user_shortname":"Test","email":"x@example.com","phone":"999","pan":"ABCDE1234F","broker":"ZERODHA"}"""
        val s = KiteData.summarize("profile", raw)
        assertFalse(s.contains("example.com") || s.contains("ABCDE") || s.contains("999"))
        assertTrue(s.contains("Test"))
    }
}

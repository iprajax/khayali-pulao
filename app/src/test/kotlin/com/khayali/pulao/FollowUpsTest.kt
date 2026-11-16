package com.khayali.pulao

import com.google.gson.JsonParser
import com.khayali.pulao.ai.AppTools
import com.khayali.pulao.ai.FollowUps
import com.khayali.pulao.ai.ToolHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpsTest {
    private val host = object : ToolHost {
        override val stock: Stock? = null
        override fun setting(name: String): Any? = null
        override fun updateSetting(name: String, value: String): String? = null
        override fun open(target: String) = false
    }

    @Test fun at15PercentMeansExactly15() {
        val pills = FollowUps.from("grow_for_years", """{"yearly_return_percent":12,"years":10,"amount_rupees":"1 lakh"}""")
        val p = pills.first { it.label == "At 15% a year" }
        val r = JsonParser.parseString(AppTools(host).execute(p.tool, p.args)).asJsonObject
        assertEquals(15.0, r["yearly_return_percent"].asDouble, 0.0)
        assertEquals(10.0, r["years"].asDouble, 0.0)
        assertEquals(404556.0, r["future_value"].asDouble, 1.0)     // 1,00,000 × 1.15^10
        assertTrue(pills.none { it.label.contains("12%") })          // never offers the same value again
    }

    @Test fun moveFollowUpsKeepSign() {
        val pills = FollowUps.from("project_move", """{"percent":10,"ltp":100}""")
        assertEquals(listOf("If it moves −10%", "If it moves +25%", "If it moves +50%"), pills.map { it.label })
    }
}

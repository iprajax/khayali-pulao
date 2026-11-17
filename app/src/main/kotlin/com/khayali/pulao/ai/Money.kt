package com.khayali.pulao.ai

/** Parses money the way people say it in India: "10 lakh", "1.5 cr", "50k", "₹2,50,000". */
object Money {
    private val RX = Regex("""(?i)^\s*([+\-−])?\s*(?:rs\.?|inr|₹)?\s*([\d,]*\.?\d+)\s*(lakhs?|lacs?|lac|l|crores?|cr|k|thousand|million|mn|m)?\s*%?\s*$""")

    fun parse(text: String?): Double? {
        val m = RX.find(text?.trim() ?: return null) ?: return null
        val n = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        val sign = if (m.groupValues[1] == "-" || m.groupValues[1] == "−") -1 else 1
        val mult = sign * when (m.groupValues[3].lowercase()) {
            "" -> 1.0
            "k", "thousand" -> 1e3
            "l", "lac", "lacs", "lakh", "lakhs" -> 1e5
            "cr", "crore", "crores" -> 1e7
            "m", "mn", "million" -> 1e6
            else -> 1.0
        }
        return n * mult
    }
}

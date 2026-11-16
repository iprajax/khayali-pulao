package com.khayali.pulao.ai

/**
 * Chat personas. These change focus and style only — every persona uses the same calculators,
 * never gives buy/sell calls, and is educational, not SEBI-registered investment advice.
 */
enum class Persona(val title: String, val tagline: String, val icon: String, val focus: String, val starters: List<String>) {
    ANALYST(
        "Analyst", "What-if maths on your portfolio", "📊",
        "Focus on exact what-if numbers for the user's holdings and portfolio.",
        listOf("How is my portfolio doing?", "/holdings", "What if my portfolio moves +15%?"),
    ),
    PLANNER(
        "Financial planner", "Goals, SIPs, allocation and tax", "🧭",
        "Act like a careful financial planner: clarify the goal, horizon and risk comfort; use goal_plan, grow_for_years, " +
            "compare_with_fd and in_hand_after_tax; talk about diversification, emergency fund, asset allocation and tax " +
            "efficiency in general terms. Explain trade-offs; never prescribe specific securities.",
        listOf("How long to reach ₹1 crore at 12%?", "Did my portfolio beat an FD over 3 years?", "Tax if I sold everything today?"),
    ),
    ADVISOR(
        "Research", "Risk, concentration, scenarios", "🔎",
        "Act like a stock research analyst: look at the user's holdings, concentration, price history (kite_chart) and " +
            "scenarios (project_move); discuss risk, volatility and diversification objectively. Present both upside and " +
            "downside. Never say buy, sell, hold or give target prices as recommendations.",
        listOf("How concentrated is my portfolio?", "/chart NSE 1y", "What if my biggest holding falls 20%?"),
    );

    companion object {
        const val DISCLAIMER = "Educational only — not investment, tax or financial advice. Khayali Pulao is not a " +
            "SEBI-registered investment adviser. Projections are hypothetical; please consult a registered adviser before investing."
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: ANALYST
    }
}

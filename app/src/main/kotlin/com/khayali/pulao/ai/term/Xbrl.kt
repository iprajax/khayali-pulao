package com.khayali.pulao.ai.term

/**
 * Reads the profit-and-loss lines from an exchange results filing in XBRL (the "integrated filing"
 * format Indian listed companies use since 2025). Only the quarter's own context is read; values
 * are in rupees in the file and returned in ₹ crore. Pure; unit-tested on a real filing.
 */
object Xbrl {
    data class Pnl(
        val symbol: String, val from: String, val to: String, val standalone: Boolean,
        val revenue: Double, val otherIncome: Double, val materials: Double, val staff: Double, val finance: Double,
        val depreciation: Double, val otherExpenses: Double, val beforeTax: Double, val tax: Double, val profit: Double, val eps: Double?,
    ) { val income get() = revenue + otherIncome }

    private fun facts(xml: String, context: String): Map<String, String> =
        Regex("""<[a-zA-Z\-]+:([A-Za-z]+)\s[^>]*contextRef="$context"[^>]*>([^<]{0,80})</""").findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }

    /** The quarter is the context whose period ends on the reporting date and is about three months long; falls back to "OneD". */
    private fun quarterContext(xml: String): String {
        val ctx = Regex("""<xbrli:context id="([^"]+)">.*?<xbrli:startDate>([\d-]+)</xbrli:startDate>\s*<xbrli:endDate>([\d-]+)</xbrli:endDate>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .mapNotNull { m -> runCatching { Triple(m.groupValues[1], java.time.LocalDate.parse(m.groupValues[2]), java.time.LocalDate.parse(m.groupValues[3])) }.getOrNull() }
            .filter { (id, a, b) -> java.time.temporal.ChronoUnit.DAYS.between(a, b) in 85..95 && !id.contains("Expenses") }.toList()
        return ctx.maxByOrNull { it.third }?.first ?: "OneD"
    }

    fun parse(xml: String): Pnl? {
        val f = facts(xml, quarterContext(xml))
        fun cr(k: String) = (f[k]?.toDoubleOrNull() ?: 0.0) / 1e7
        val revenue = cr("RevenueFromOperations")
        if (revenue <= 0) return null
        return Pnl(f["Symbol"].orEmpty(), f["DateOfStartOfReportingPeriod"].orEmpty(), f["DateOfEndOfReportingPeriod"].orEmpty(),
            f["NatureOfReportStandaloneConsolidated"]?.startsWith("Standalone", true) != false,
            revenue, cr("OtherIncome"), cr("CostOfMaterialsConsumed") + cr("PurchasesOfStockInTrade") + cr("ChangesInInventoriesOfFinishedGoodsWorkInProgressAndStockInTrade"),
            cr("EmployeeBenefitExpense"), cr("FinanceCosts"), cr("DepreciationDepletionAndAmortisationExpense"), cr("OtherExpenses"),
            cr("ProfitBeforeTax"), cr("TaxExpense"), cr("ProfitLossForPeriod"),
            f["BasicEarningsLossPerShareFromContinuingOperations"]?.toDoubleOrNull() ?: f["BasicEarningsLossPerShareFromContinuingAndDiscontinuedOperations"]?.toDoubleOrNull())
    }
}

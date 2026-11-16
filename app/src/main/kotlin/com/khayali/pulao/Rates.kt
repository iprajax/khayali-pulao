package com.khayali.pulao

/**
 * Default comparison rates, researched as of November 2026. Users can change every one in
 * Settings; "Reset to current rates" restores these. Update this one object when rates move.
 */
object Rates {
    const val AS_OF = "Nov 2026"
    const val FD = 6.45          // SBI best general-public rate (444-day Amrit Vrishti); 1–3 yr 6.25–6.40%
    const val SAVINGS = 2.5      // SBI savings account
    const val PPF = 7.1          // Oct–Dec 2026 quarter (unchanged)
    const val INFLATION = 6.1    // CPI, Oct 2026: 6.09%
    const val NIFTY = 11.5       // Nifty 50 10-yr CAGR, price index (~12.4% with dividends)
    const val GOLD = 13.0        // Gold 10-yr CAGR in INR — estimates range ~10–17%; assumption
    const val NOTE = "Defaults as of $AS_OF: SBI FD 6.45% (444-day), savings 2.5%, PPF 7.1% (Oct–Dec 2026), " +
        "CPI inflation 6.1% (Oct 2026), Nifty 50 ~11.5% and gold ~13% are 10-year averages (assumptions). Check your bank."
}

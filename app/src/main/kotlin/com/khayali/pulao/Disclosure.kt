package com.khayali.pulao

/**
 * The in-app prominent disclosure required by Google Play for apps that use the
 * Accessibility Service API but are not accessibility tools. Shown before the user
 * is sent to Android's Accessibility settings, with "Agree" / "No thanks".
 * Keep in sync with PRIVACY.md and playstore/accessibility-declaration.md.
 */
object Disclosure {
    const val TITLE = "Khayali Pulao uses the Accessibility Service"

    const val INTRO =
        "To show daydream projections on top of Zerodha Kite, Khayali Pulao uses Android's " +
            "Accessibility Service API. Please read how before you turn it on."

    val SECTIONS = listOf(
        "What it reads" to
            "Only text the Zerodha Kite app shows on screen — a stock's name, quantity, average price, " +
            "last traded price, and your Invested / Current totals — and only while Kite is open and you " +
            "tap a row with the bubble switched on.",
        "Why" to
            "To calculate what-if projections on this phone: price moves, FD comparison, goals, and " +
            "estimated in-hand amounts after tax.",
        "What it does on your screen" to
            "Shows a floating bubble and a results sheet over Kite. When you swipe, it scrolls Kite's list; " +
            "when you tap Kite's own navigation tabs, it opens them. It never taps Buy or Sell and never places orders.",
        "What it never does" to
            "It does not collect, store or share this information — it never leaves your phone. The app uses the " +
            "internet only to download its optional AI model once; nothing is uploaded. No account, no analytics, no ads.",
        "Your control" to
            "You can turn it off at any time in Settings → Accessibility → Khayali Pulao. Uninstalling removes everything.",
    )
}

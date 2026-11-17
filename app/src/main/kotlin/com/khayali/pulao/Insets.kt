package com.khayali.pulao

import android.os.Build
import android.view.View
import android.view.WindowInsets

/** System-bar and keyboard padding that also works on Android 9 and 10 (the typed insets API is 30+). */
object Insets {
    /**
     * Pads a root view on all four sides so content clears the status bar, the navigation bar
     * (which sits on the side in landscape), display cut-outs and the keyboard.
     */
    @Suppress("DEPRECATION")
    fun pad(v: View, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        } else v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }
}

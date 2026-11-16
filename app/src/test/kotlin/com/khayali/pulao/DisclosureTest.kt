package com.khayali.pulao

import org.junit.Assert.assertTrue
import org.junit.Test

/** The Play prominent disclosure must say what is accessed, why, and whether it is shared. */
class DisclosureTest {
    private val all = (Disclosure.INTRO + Disclosure.SECTIONS.joinToString(" ") { it.first + " " + it.second }).lowercase()

    @Test fun coversWhatWhyAndSharing() {
        listOf("accessibility service", "what it reads", "why", "does not collect, store or share",
            "no internet permission", "never places orders", "turn it off").forEach {
            assertTrue("disclosure must mention '$it'", all.contains(it))
        }
    }
}

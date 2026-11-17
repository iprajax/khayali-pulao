package com.khayali.pulao

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kotlin runs property initialisers and init blocks top to bottom. A `by lazy` property declared below
 * an init block is still null while that block runs, so anything init calls that touches it crashes
 * the screen on open (this happened to the chat once). Every lazy property in a view or activity
 * must therefore be declared above the first init block.
 */
class InitOrderTest {
    @Test fun lazyPropertiesComeBeforeInit() {
        val roots = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
        for (f in roots) {
            val lines = f.readLines()
            val initAt = lines.indexOfFirst { it.trim().startsWith("init {") || it.trim() == "init {" }
            if (initAt < 0) continue
            // only class-level declarations (4-space indent) belong to that class's init order
            val late = lines.withIndex().filter { (i, l) -> i > initAt && Regex("""^    (private |internal )?(val|var) \w+(: [^=]+)? by lazy""").containsMatchIn(l) }
            assertTrue("${f.name}: lazy properties declared after init: " + late.joinToString { "line ${it.index + 1}" }, late.isEmpty())
        }
    }
}

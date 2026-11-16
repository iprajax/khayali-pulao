buildscript {
    dependencies {
        // AGP 9 uses built-in Kotlin; pin KGP so we can read LiteRT-LM (compiled with Kotlin 2.4).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.3.3" apply false
}

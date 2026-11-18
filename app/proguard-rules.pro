# Khayali Pulao has no reflection, no serialization and no network code.
# Android keeps the AccessibilityService / Activities declared in the manifest automatically.

# Strip debug logging from release builds (belt and braces: logs are already BuildConfig.DEBUG-guarded).
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# LiteRT-LM: native code calls back into these classes by name (JNI) — keep them intact.
-keep class com.google.ai.edge.litertlm.** { *; }

# sherpa-onnx (the natural voice): its native code reads these config classes' fields by name (JNI) — keep them intact.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

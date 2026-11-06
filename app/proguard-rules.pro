# Add project specific ProGuard rules here.
-keep class com.example.musicplayer.model.** { *; }

# ── jaudiotagger ─────────────────────────────────────────────
# Frame bodies and tag fields are instantiated reflectively by name
# (e.g. Class.forName("...framebody.FrameBody" + frameId)), so R8 must not
# rename or strip them.
-keep class org.jaudiotagger.** { *; }
# Desktop-only APIs referenced by jaudiotagger code paths we never hit on Android.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-dontwarn java.beans.**
-dontwarn javax.sound.**

# ── Logging ──────────────────────────────────────────────────
# Strip verbose/debug logs from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

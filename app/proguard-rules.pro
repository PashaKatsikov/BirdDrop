# ══ Attribute preservation ═════════════════════════════════════════════════
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ══ WebView JS bridge ══════════════════════════════════════════════════════
# The @JavascriptInterface methods are called by name from JS; R8 must not
# rename them or strip them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ══ Firebase ═══════════════════════════════════════════════════════════════
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**

# ══ AppsFlyer ══════════════════════════════════════════════════════════════
-keep class com.appsflyer.** { *; }
-keep class com.android.installreferrer.** { *; }
-dontwarn com.appsflyer.**

# ══ OkHttp ═════════════════════════════════════════════════════════════════
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }

# ══ Security-crypto ═══════════════════════════════════════════════════════
-keep class androidx.security.crypto.** { *; }

# ══ Gray-part entry classes ════════════════════════════════════════════════
# rebrand.py updates the gray package path here when it renames the gray flow.
-keep class com.birddrop.birddropgame.netmon.FcmCanyon
-keep class com.birddrop.birddropgame.notif.GorgeRouter

# ══ Application + game (white part) entry activities ═══════════════════════
# goNative() launches MainMenuActivity by class reference; keep the game entry
# points so R8 cannot prove them dead.
-keep class com.birddrop.birddropgame.BirdDropApp
-keep class com.birddrop.birddropgame.LoadingActivity
-keep class com.birddrop.birddropgame.MainMenuActivity
-keep class com.birddrop.birddropgame.LevelSelectActivity
-keep class com.birddrop.birddropgame.GameActivity
-keep class com.birddrop.birddropgame.WebViewActivity

# ══ Strip debug-level logs in release ══════════════════════════════════════
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

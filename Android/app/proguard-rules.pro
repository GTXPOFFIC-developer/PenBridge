# PenBridge Tablet — ProGuard / R8 rules

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keepclassmembers class **$WhenMappings { *; }
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ── Coroutines ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── Compose ───────────────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Dashboard protocol: keep data classes so wire format isn't broken ─────────
-keep class com.dashboard.core.Frame { *; }
-keep class com.dashboard.core.PenEvent { *; }
-keep class com.dashboard.core.HostInfo { *; }
-keep class com.dashboard.core.AppSettings { *; }
-keep class com.dashboard.core.CurvePoint { *; }
-keep class com.dashboard.core.Const { *; }

# ── Google OAuth helpers ──────────────────────────────────────────────────────
-keep class org.json.** { *; }

# ── NSD / mDNS ───────────────────────────────────────────────────────────────
-keep class android.net.nsd.** { *; }

# ── Keep enum names (used in SharedPreferences string serialisation) ──────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Debug info ────────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# ============================================================
# ProGuard / R8 rules for CyberGuard (V2rayNG fork)
# ============================================================

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Gson serialization ----
# Keep all DTO classes used with Gson (fields must not be renamed)
-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.enums.** { *; }

# Gson TypeToken requires signature info
-keepattributes Signature
-keepattributes *Annotation*

# Gson specific
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- MMKV ----
-keep class com.tencent.mmkv.** { *; }

# ---- V2Ray / Xray native library ----
-keep class libv2ray.** { *; }
-keep class go.** { *; }

# ---- Android components ----
# Keep all Activities, Services, Receivers declared in Manifest
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep ViewBinding generated classes
-keep class com.v2ray.ang.databinding.** { *; }

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ---- Coroutines ----
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ---- Toasty ----
-keep class es.dmoral.toasty.** { *; }

# ---- Multidex ----
-keep class androidx.multidex.** { *; }

# ---- hev-socks5-tunnel native ----
-keep class hev.** { *; }

# ---- Prevent R8 from removing crypto helpers ----
-keep class com.v2ray.ang.util.CryptoHelper { *; }
-keep class com.v2ray.ang.util.DeviceManager { *; }

# ProGuard rules for SOAR Client
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class com.soar.client.data.local.** { *; }
-keep class com.soar.client.data.remote.** { *; }

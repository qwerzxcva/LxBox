-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**
-keepclassmembers class * implements kotlinx.serialization.Serializable {
    <fields>;
}

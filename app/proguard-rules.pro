# Proguard rules for lemezt
-keep class com.lemezt.app.model.** { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <fields>;
    @androidx.annotation.Keep <methods>;
}

-keepattributes *Annotation*, InnerClasses
-dontwarn kotlinx.serialization.**
-keep,includedescriptorclasses class com.habitlair.app.**$$serializer { *; }
-keepclassmembers class com.habitlair.app.** { *** Companion; }
-keepclasseswithmembers class com.habitlair.app.** { kotlinx.serialization.KSerializer serializer(...); }

# Glance widget callbacks are instantiated by class name
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { <init>(); }

# Tink (used by EncryptedSharedPreferences) references optional annotation classes
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# Alarm service/activity and widget receivers are referenced from the manifest
-keep class com.habitlair.app.work.AlarmService { *; }
-keep class com.habitlair.app.work.AlarmRingActivity { *; }
-keep class com.habitlair.app.widget.** { *; }

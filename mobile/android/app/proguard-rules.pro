# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keep class com.vibetavern.launcher.** { *; }
-keep class com.vibetavern.client.** { *; }
-keepclassmembers class com.vibetavern.client.** { *** Companion; }

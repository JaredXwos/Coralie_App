# WebRTC / jni_zero — these classes are only referenced from native code
# (JNI_OnLoad), so R8 can't see the usage and will strip them without
# this rule, causing a native SIGTRAP crash on startup.
-keep class org.jni_zero.** { *; }
-keep class org.webrtc.** { *; }

# Also keep anything annotated as a JNI-called target, in case the
# library uses this pattern instead of/in addition to package-level keep
-keepclasseswithmembers class * {
    @org.jni_zero.* <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}
# WebRTC calls into Java from native code through JNI; keep everything it needs.
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }
-dontwarn org.webrtc.**

# kotlinx.serialization: keep generated serializers for our protocol classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class io.github.nomskis.earshot.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.nomskis.earshot.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Shizuku instantiates the Turbo user service by reflection (constructor with a Context).
-keep class io.github.nomskis.earshot.turbo.TurboService { <init>(...); *; }
-keep class io.github.nomskis.earshot.turbo.ITurboService** { *; }

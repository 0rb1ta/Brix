# RootEncoder — reflection-based codec enumeration
-keep class com.pedro.library.** { *; }
-keep class com.pedro.common.** { *; }
-keep class com.pedro.srt.** { *; }
-keep class com.pedro.encoder.** { *; }
-keep class com.pedro.library.util.** { *; }
-keep class com.pedro.library.input.** { *; }
-keep class com.pedro.library.output.** { *; }
-keep class com.pedro.library.rtmp.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class app.brix.core.** {
    *** Companion;
}
-keepclasseswithmembers class app.brix.core.**$$serializer {
    *** INSTANCE;
}

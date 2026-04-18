# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.gime.android.**$$serializer { *; }
-keepclassmembers class com.gime.android.** { *** Companion; }
-keepclasseswithmembers class com.gime.android.** { kotlinx.serialization.KSerializer serializer(...); }

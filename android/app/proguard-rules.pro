# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.gime.android.**$$serializer { *; }
-keepclassmembers class com.gime.android.** { *** Companion; }
-keepclasseswithmembers class com.gime.android.** { kotlinx.serialization.KSerializer serializer(...); }

# JNI（libhechima.so = Mozc）。native メソッドを持つクラスは名前を変えられない。
# proguard-android-optimize.txt の既定にも同種の規則はあるが、
# ここが崩れると **release ビルドだけ UnsatisfiedLinkError で落ちる**ので明示しておく。
-keepclasseswithmembernames,includedescriptorclasses class com.gime.android.engine.HechimaNative {
    native <methods>;
}
-keep class com.gime.android.engine.HechimaNative { *; }

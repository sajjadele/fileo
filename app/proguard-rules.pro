# فایلو — قوانین R8/ProGuard

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class ir.fileo.**$$serializer { *; }
-keepclassmembers class ir.fileo.** {
    *** Companion;
}
-keepclasseswithmembers class ir.fileo.** {
    kotlinx.serialization.KSerializer serializer(...);
}

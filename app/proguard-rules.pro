# ML Kit text recognition ships optional native entry points that are only invoked
# reflectively; without these the shrinker strips them and OCR fails silently in release.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.android.gms.internal.mlkit_**

# The OCR model data must survive resource shrinking.
-keep class com.google.mlkit.vision.text.** { *; }

# Scanned page records are read back by column name; keep the model classes intact.
-keep class com.kscanner.data.** { *; }

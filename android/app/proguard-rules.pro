# R8 rules for the release build.
# Our own code is kept whole: views are built in code, JS bridges are called by name from web pages,
# and settings are read by key. R8 then only trims unused parts of the libraries (Kotlin stdlib…).
-keep class vn.jiner.xrealspace.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# Vosk (offline speech) talks to its native library through JNA, which looks classes/fields up by name.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

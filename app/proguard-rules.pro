# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable

# Keep important classes and methods for TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-keep interface org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep interface org.tensorflow.lite.support.** { *; }

# Keep Tesseract OCR classes
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# Keep ML Kit classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }

# Keep Gson classes
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**

# Keep model classes
-keep class com.example.coupontracker.data.model.** { *; }
-keep class com.example.coupontracker.ml.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Rules for test dependencies
-dontwarn com.sun.jna.**
-dontwarn com.sun.jna.platform.win32.**
-dontwarn com.sun.jna.ptr.**
-dontwarn com.sun.jna.win32.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.lang.instrument.**
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn javax.tools.**
-dontwarn org.apiguardian.api.**
-dontwarn org.mockito.**
-dontwarn org.slf4j.**

# Keep JUnit classes
-keep class org.junit.** { *; }
-keep interface org.junit.** { *; }

# Keep Mockito classes
-keep class org.mockito.** { *; }
-keep interface org.mockito.** { *; }

# Keep MockK classes
-keep class io.mockk.** { *; }
-keep interface io.mockk.** { *; }

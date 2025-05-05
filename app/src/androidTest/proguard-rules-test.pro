# ProGuard rules for Android tests

# Keep JUnit classes
-keep class org.junit.** { *; }
-keep interface org.junit.** { *; }

# Keep Mockito classes
-keep class org.mockito.** { *; }
-keep interface org.mockito.** { *; }

# Keep MockK classes
-keep class io.mockk.** { *; }
-keep interface io.mockk.** { *; }

# Keep JNA classes
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }

# Keep Java instrumentation classes
-keep class java.lang.instrument.** { *; }
-keep interface java.lang.instrument.** { *; }

# Keep SLF4J classes
-keep class org.slf4j.** { *; }
-keep interface org.slf4j.** { *; }

# Keep API Guardian classes
-keep class org.apiguardian.** { *; }
-keep interface org.apiguardian.** { *; }

# Keep FindBugs annotations
-keep class edu.umd.cs.findbugs.** { *; }
-keep interface edu.umd.cs.findbugs.** { *; }

# Keep javax.tools classes
-keep class javax.tools.** { *; }
-keep interface javax.tools.** { *; }

# Ignore warnings for missing classes
-dontwarn com.sun.jna.**
-dontwarn com.sun.jna.platform.win32.**
-dontwarn com.sun.jna.ptr.**
-dontwarn com.sun.jna.win32.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn java.lang.instrument.**
-dontwarn javax.tools.**
-dontwarn org.apiguardian.api.**
-dontwarn org.mockito.**
-dontwarn org.slf4j.**

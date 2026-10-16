# Add project specific ProGuard rules here.

# JNI/C++ accesses wallet model classes by fully qualified name and reflection.
# R8 must not remove or rename classes that are referenced only from native code.
-keep class io.anonero.model.** { *; }
-keep class io.anonero.model.node.** { *; }

# JNI methods are exported by their exact Java/Kotlin class and method names.
# R8 must not rename classes or members that have native implementations.
-dontwarn java.lang.management.**

# This optional JVM-only API is not present on Android; the library uses it only on JVMs.
-keepclasseswithmembernames,includedescriptorclasses class * {
native <methods>;
}


# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# Keep `Companion` object fields of serializable classes.
# This avoids serializer lookup through `getDeclaredClasses` as done for named companion objects.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (both default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
static **$* *;
}
-keepclassmembers class <2>$<3> {
kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
public static ** INSTANCE;
}
-keepclassmembers class <1> {
public static <1> INSTANCE;
kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable and @Polymorphic are used at runtime for polymorphic serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Don't print notes about potential mistakes or omissions in the configuration for kotlinx-serialization classes
# See also https://github.com/Kotlin/kotlinx.serialization/issues/1900
-dontnote kotlinx.serialization.**

# Serialization core uses `java.lang.ClassValue` for caching inside these specified classes.
# If there is no `java.lang.ClassValue` (for example, in Android), then R8/ProGuard will print a warning.
# However, since in this case they will not be used, we can disable these warnings
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-dontwarn java.lang.management.ManagementFactory

# disable optimisation for descriptor field because in some versions of ProGuard, optimization generates incorrect bytecode that causes a verification error
# see https://github.com/Kotlin/kotlinx.serialization/issues/2719
-keepclassmembers public class **$$serializer {
private ** descriptor;
}

# Jetpack Navigation's type-safe routes resolve kotlinx-serialization type names
# reflectively. Keep the serialized class names stable in minified Release builds;
# otherwise LockScreenShortCut's descriptor still names the original FQCN while
# R8 renames the actual enum, causing ClassNotFoundException during composition.
-keep,allowoptimization enum io.anonero.ui.home.LockScreenShortCut { *; }
-keep,allowoptimization enum io.anonero.ui.home.LockScreenMode { *; }
-keepnames @kotlinx.serialization.Serializable class io.anonero.ui.home.graph.routes.**

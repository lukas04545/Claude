# TensorFlow Lite
-keep class org.tensorflow.** { *; }
-keep class com.google.flatbuffers.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }

# App model classes
-keep class com.clashdetector.model.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

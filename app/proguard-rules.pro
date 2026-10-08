-keep class org.tensorflow.lite.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.dhrashta.x.data.** { *; }

# ONNX Runtime uses JNI to reach these classes by name.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { native <methods>; }

# CameraX, Compose, and DataStore ship their own consumer rules.
# ONNX Runtime resolves native operators through its Java API at runtime.
-keep class ai.onnxruntime.** { *; }

# MediaPipe Tasks resolves options/listeners and protobuf-backed results reflectively.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# CameraX, Compose, and DataStore ship their own consumer rules.
# ONNX Runtime resolves native operators through its Java API at runtime.
-keep class ai.onnxruntime.** { *; }

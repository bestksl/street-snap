# CameraX, Compose, and DataStore ship their own consumer rules.
# ONNX Runtime resolves native operators through its Java API at runtime.
-keep class ai.onnxruntime.** { *; }

# MediaPipe Tasks resolves options/listeners and protobuf-backed results reflectively.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
# Consumer rules do not cover the two failure modes observed in minified preview
# builds (debug builds never tripped them): flogger caller-stack inference and
# protobuf lite field reflection while loading the bundled binary graph. Both
# rules are required for pose live mode to leave demo fallback.

# Flogger (a MediaPipe transitive dependency) infers the logger name by walking
# its own caller stack frames. Under R8 full mode, inlining/outlining removes
# the FluentLogger frame and Graph.<clinit> dies with
# "IllegalStateException: no caller found on the stack for: ...", which makes
# pose live mode fall back to demo on every minified build. Keep the library
# intact so its stack-walk shape survives optimization.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# MediaPipe's binary calculators are serialized protobuf; the lite runtime
# resolves message fields reflectively (e.g. Any.typeUrl_ while loading the
# pose graph). R8 full-mode proto optimization renames those fields and the
# landmarker then dies with "Field typeUrl_ for ... not found".
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

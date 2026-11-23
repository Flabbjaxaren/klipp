# Klipp – R8-regler.

# JNI: Rust-motorn (libklipp_bridge) och whisper.cpp (libklipp_whisper) slår upp funktionerna på klass- och
# metodnamn (Java_se_gendut_klipp_engine_Engine_native…). Klasserna och deras native-metoder behålls som de är.
-keep class se.gendut.klipp.engine.Engine { native <methods>; }
-keep class se.gendut.klipp.engine.Engine$Companion { *; }
-keep class se.gendut.klipp.engine.Whisper { native <methods>; *; }

# whisper_jni.cpp anropar lyssnarens onProgress(int) via GetMethodID – namnet och signaturen måste finnas kvar.
-keep interface se.gendut.klipp.engine.Whisper$Listener { *; }
-keep class * implements se.gendut.klipp.engine.Whisper$Listener { void onProgress(int); }

# Krasch-rapporter: behåll rader och filnamn i stackspår (kartfilen sparas av bygget).
-keepattributes SourceFile,LineNumberTable

# ONNX Runtime (exact word edges): its native code finds the Java classes and constructors by name
-keep class ai.onnxruntime.** { *; }

# MediaPipe (face following): its native library finds Java classes, fields and methods by name (packets, the graph's
# callbacks, the tasks' option and result classes), and protobuf lite reads the generated messages' fields by name.
# The usage-statistics transport (com.google.android.datatransport) is left out on purpose (app/build.gradle.kts):
# only MediaPipe's RemoteLoggingClient refers to it, and nothing creates that.
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.android.datatransport.**
# Referenced but not in the app, and never used by face following: LiteRT-LM (an optional dependency of the image
# embedder), AutoValue's annotations, and two framework protos (graph profiler and templates)
-dontwarn com.google.ai.edge.litertlm.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$*
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$*

-keep class se.gendut.klipp.engine.CrawImport { native <methods>; }

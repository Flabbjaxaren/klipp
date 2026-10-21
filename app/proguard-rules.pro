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

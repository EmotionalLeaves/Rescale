# JNI entry points and the callback called from C++.
-keep class app.rescale.engine.nativebridge.NativeEngine { native <methods>; }
-keep interface app.rescale.engine.nativebridge.ProgressListener { *; }
-keep class * implements app.rescale.engine.nativebridge.ProgressListener { void onProgress(int, int); void onPhase(int); }

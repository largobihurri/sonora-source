# JAudioTagger builds its ID3/MP4/FLAC frame bodies and field keys by class name (Class.forName) and through enum
# reflection, so shrinking or renaming any of it would silently drop tags when the library is scanned or edited.
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# ONNX Runtime (core:beatmodel, the DJ beat model): its JNI library finds the Java classes, constructors, fields and
# methods it calls back by name (OnnxTensor, TensorInfo, OrtException, OnnxJavaType...), and the AAR's consumer rules
# keep only its telemetry classes. Renamed members would fail at the first inference; ONNX Runtime's documentation
# asks for the whole package to be kept.
-keep class ai.onnxruntime.** { *; }

# Release log hygiene (odd/tasks/play-release.md S7, P7): R8 removes every Log.v and Log.d call, and the building of
# its message, from the minified builds (benchmark, nightly, and release once R8 runs there), so debug-only detail
# (Kora audit lines, file names a tag reader could not open, rejected watch commands) never reaches a release logcat.
# Log.i, Log.w and Log.e stay: they carry the operational lines the ColorOS diagnosis relies on (CLAUDE.md, Devices:
# SonoraAudioOut, SonoraAnalysisCache, SonoraDjAnalysis...), and the code keeps URIs, paths and file names out of them
# (core/domain redactForLog). Debug builds are not minified and keep every level.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

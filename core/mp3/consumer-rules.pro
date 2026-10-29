# libsonora_mp3.so binds its functions by JNI name (Java_io_sonora_core_mp3_LameJni_*), so R8 must neither rename nor
# remove LameJni or its native methods in apps that minify; a renamed method fails at the first call with
# UnsatisfiedLinkError.
-keep class io.sonora.core.mp3.LameJni {
    native <methods>;
}

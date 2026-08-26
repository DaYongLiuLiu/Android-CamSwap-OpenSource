#include "native_audio_hook.h"
#include "pcm_bridge.h"
#include <android/log.h>
#include <jni.h>

#define LOG_TAG "CS-NativeHook"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

JavaVM* get_java_vm() {
    return g_jvm;
}

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    LOGI("JNI_OnLoad completed");
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_github_zensu357_camswap_NativeAudioHook_nativeInit(JNIEnv* env, jclass clazz) {
    LOGI("nativeInit called");

    // 在 nativeInit 中初始化 bridge，此时 classloader 上下文正确（Xposed 模块的 classloader）
    pcm_bridge_init(env, clazz);

    install_aaudio_hooks();
    install_opensles_hooks();

    LOGI("nativeInit: all hooks installed");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_zensu357_camswap_NativeAudioHook_nativeRelease(JNIEnv* /*env*/, jclass /*clazz*/) {
    LOGI("nativeRelease called");
    // Dobby hooks persist until process exit; nothing to undo
}


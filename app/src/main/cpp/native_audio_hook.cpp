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

#include "camserver_shm.h"
#include <chrono>

static CameraFramePacket* g_writer_packet = nullptr;

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_github_zensu357_camswap_utils_CameraServerBridge_nativeInitShm(JNIEnv* /*env*/, jclass /*clazz*/) {
    if (!g_writer_packet) {
        g_writer_packet = CsShmManager::openWriter();
    }
    return g_writer_packet != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_io_github_zensu357_camswap_utils_CameraServerBridge_nativePushFrame(
        JNIEnv* env, jclass /*clazz*/,
        jbyteArray frame_bytes, jint width, jint height, jint format,
        jint rotation, jfloat r, jfloat g, jfloat b, jfloat intensity) {

    if (!g_writer_packet) {
        g_writer_packet = CsShmManager::openWriter();
        if (!g_writer_packet) return JNI_FALSE;
    }

    if (!frame_bytes || width <= 0 || height <= 0) return JNI_FALSE;
    jsize len = env->GetArrayLength(frame_bytes);
    if (len <= 0) return JNI_FALSE;

    int copy_size = len > CS_MAX_FRAME_SIZE ? CS_MAX_FRAME_SIZE : len;

    jbyte* p_bytes = env->GetByteArrayElements(frame_bytes, nullptr);
    if (!p_bytes) return JNI_FALSE;

    std::memcpy(g_writer_packet->data, p_bytes, copy_size);
    env->ReleaseByteArrayElements(frame_bytes, p_bytes, JNI_ABORT);

    g_writer_packet->width = width;
    g_writer_packet->height = height;
    g_writer_packet->format = format;
    g_writer_packet->rotation = rotation;
    g_writer_packet->ambient_r_color = r;
    g_writer_packet->ambient_g_color = g;
    g_writer_packet->ambient_b_color = b;
    g_writer_packet->ambient_intensity = intensity;
    g_writer_packet->data_size = copy_size;

    auto now = std::chrono::steady_clock::now().time_since_epoch();
    g_writer_packet->timestamp_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();

    uint64_t next_seq = g_writer_packet->sequence.load(std::memory_order_relaxed) + 1;
    g_writer_packet->sequence.store(next_seq, std::memory_order_release);

    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_io_github_zensu357_camswap_utils_CameraServerBridge_nativeCloseShm(JNIEnv* /*env*/, jclass /*clazz*/) {
    if (g_writer_packet) {
        CsShmManager::closeShm(g_writer_packet);
        g_writer_packet = nullptr;
    }
}


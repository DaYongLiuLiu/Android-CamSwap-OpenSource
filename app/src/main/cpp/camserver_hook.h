#pragma once

#include <android/log.h>

#define CS_TAG "【CS】【CameraServerHook】"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, CS_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CS_TAG, __VA_ARGS__)

extern "C" {
    void init_camera_server_hook();
    void release_camera_server_hook();
}

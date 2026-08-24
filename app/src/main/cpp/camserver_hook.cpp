#include "camserver_hook.h"
#include "camserver_shm.h"
#include "graphic_buffer_bridge.h"

#include <dlfcn.h>
#include <unistd.h>
#include <cstring>
#include <vector>
#include <dobby.h>

// Function pointer signature for BufferQueueProducer::queueBuffer
// android::status_t queueBuffer(int slot, const QueueBufferInput& input, QueueBufferOutput* output);
typedef int (*queueBuffer_t)(void* thiz, int slot, const void* input, void* output);
static queueBuffer_t orig_queueBuffer = nullptr;

// GraphicBuffer helper functions
typedef int (*GB_lock_t)(void* gb, uint32_t usage, void** vaddr);
typedef int (*GB_lock64_t)(void* gb, uint64_t usage, void** vaddr);
typedef int (*GB_unlock_t)(void* gb);

static GB_lock_t fn_GB_lock32 = nullptr;
static GB_lock64_t fn_GB_lock64 = nullptr;
static GB_unlock_t fn_GB_unlock = nullptr;

static CameraFramePacket* g_shm_packet = nullptr;
static uint64_t g_last_rendered_seq = 0;
static uint64_t g_intercepted_count = 0;

static void* findGraphicBufferFromProducer(void* thiz, int slot) {
    if (!thiz || slot < 0 || slot >= 64) return nullptr;

    // BufferQueueProducer contains sp<BufferQueueCore> mCore
    // Search offsets in thiz to find mCore pointer
    uintptr_t* producer_ptrs = reinterpret_cast<uintptr_t*>(thiz);
    for (int offset = 1; offset <= 8; ++offset) {
        uintptr_t core_ptr = producer_ptrs[offset];
        if (core_ptr > 0x10000 && (core_ptr & 0x7) == 0) {
            // Check if core_ptr contains valid GraphicBuffer pointers for this slot
            uintptr_t* core_fields = reinterpret_cast<uintptr_t*>(core_ptr);
            for (int slot_offset = 0; slot_offset <= 32; ++slot_offset) {
                // BufferSlot array start candidate
                uintptr_t* candidate_slot_array = core_fields + slot_offset;
                // Each BufferSlot has sp<GraphicBuffer> as first member
                // BufferSlot size is typically between 64 and 256 bytes (8 to 32 pointers)
                for (int slot_stride = 8; slot_stride <= 32; slot_stride += 2) {
                    uintptr_t gb_ptr = candidate_slot_array[slot * slot_stride];
                    if (gb_ptr > 0x10000 && (gb_ptr & 0x7) == 0) {
                        // Validate if gb_ptr looks like GraphicBuffer
                        int32_t* gb_ints = reinterpret_cast<int32_t*>(gb_ptr);
                        for (int i = 0; i < 16; ++i) {
                            int32_t w = gb_ints[i];
                            int32_t h = gb_ints[i + 1];
                            int32_t stride = gb_ints[i + 2];
                            int32_t format = gb_ints[i + 3];
                            if (w >= 160 && w <= 7680 && h >= 120 && h <= 4320 && stride >= w &&
                                (format == CS_HAL_PIXEL_FORMAT_NV21 || format == CS_HAL_PIXEL_FORMAT_YV12 ||
                                 format == CS_HAL_PIXEL_FORMAT_YUV420888 || format == CS_HAL_PIXEL_FORMAT_RGBA_8888 ||
                                 format == CS_HAL_PIXEL_FORMAT_RGBX_8888)) {
                                return reinterpret_cast<void*>(gb_ptr);
                            }
                        }
                    }
                }
            }
        }
    }
    return nullptr;
}

static bool overwriteGraphicBuffer(void* gb) {
    if (!gb || !g_shm_packet) return false;

    // Resolve width, height, stride, format from GraphicBuffer struct
    int32_t* gb_ints = reinterpret_cast<int32_t*>(gb);
    int32_t width = 0, height = 0, stride = 0, format = 0;

    for (int i = 0; i < 16; ++i) {
        int32_t w = gb_ints[i];
        int32_t h = gb_ints[i + 1];
        int32_t s = gb_ints[i + 2];
        int32_t f = gb_ints[i + 3];
        if (w >= 160 && w <= 7680 && h >= 120 && h <= 4320 && s >= w &&
            (f == CS_HAL_PIXEL_FORMAT_NV21 || f == CS_HAL_PIXEL_FORMAT_YV12 ||
             f == CS_HAL_PIXEL_FORMAT_YUV420888 || f == CS_HAL_PIXEL_FORMAT_RGBA_8888 ||
             f == CS_HAL_PIXEL_FORMAT_RGBX_8888)) {
            width = w;
            height = h;
            stride = s;
            format = f;
            break;
        }
    }

    if (width <= 0 || height <= 0 || stride <= 0) {
        return false;
    }

    void* vaddr = nullptr;
    int lock_res = -1;
    uint32_t usage = CS_GRALLOC_USAGE_SW_READ_OFTEN | CS_GRALLOC_USAGE_SW_WRITE_OFTEN;

    if (fn_GB_lock64) {
        lock_res = fn_GB_lock64(gb, static_cast<uint64_t>(usage), &vaddr);
    } else if (fn_GB_lock32) {
        lock_res = fn_GB_lock32(gb, usage, &vaddr);
    }

    if (lock_res == 0 && vaddr != nullptr) {
        GraphicBufferBridge::copyAndConvertFrame(
            g_shm_packet,
            vaddr,
            width,
            height,
            format,
            stride
        );

        if (fn_GB_unlock) {
            fn_GB_unlock(gb);
        }
        return true;
    }

    return false;
}

// Hook signatures for GraphicBuffer::lock and lockYCbCr
typedef int (*orig_GB_lock2_t)(void* gb, uint32_t usage, void** vaddr);
typedef int (*orig_GB_lock2_64_t)(void* gb, uint64_t usage, void** vaddr);
typedef int (*orig_GB_lock4_t)(void* gb, uint32_t usage, void** vaddr, int32_t* outBytesPerPixel, int32_t* outBytesPerStride);
typedef int (*orig_GB_lock4_64_t)(void* gb, uint64_t usage, void** vaddr, int32_t* outBytesPerPixel, int32_t* outBytesPerStride);
typedef int (*orig_GB_lockYCbCr_t)(void* gb, uint32_t usage, void* ycbcr);
typedef int (*orig_GB_lockYCbCr_64_t)(void* gb, uint64_t usage, void* ycbcr);

static orig_GB_lock2_t orig_GB_lock2 = nullptr;
static orig_GB_lock2_64_t orig_GB_lock2_64 = nullptr;
static orig_GB_lock4_t orig_GB_lock4 = nullptr;
static orig_GB_lock4_64_t orig_GB_lock4_64 = nullptr;
static orig_GB_lockYCbCr_t orig_GB_lockYCbCr = nullptr;
static orig_GB_lockYCbCr_64_t orig_GB_lockYCbCr_64 = nullptr;

static bool extractBufferMeta(void* gb, int32_t& out_w, int32_t& out_h, int32_t& out_stride, int32_t& out_format) {
    if (!gb) return false;
    int32_t* gb_ints = reinterpret_cast<int32_t*>(gb);
    for (int i = 0; i < 20; ++i) {
        int32_t w = gb_ints[i];
        int32_t h = gb_ints[i + 1];
        int32_t s = gb_ints[i + 2];
        int32_t f = gb_ints[i + 3];
        if (w >= 160 && w <= 7680 && h >= 120 && h <= 4320 && s >= w &&
            (f == CS_HAL_PIXEL_FORMAT_NV21 || f == CS_HAL_PIXEL_FORMAT_YV12 ||
             f == CS_HAL_PIXEL_FORMAT_YUV420888 || f == CS_HAL_PIXEL_FORMAT_RGBA_8888 ||
             f == CS_HAL_PIXEL_FORMAT_RGBX_8888 || f == 0x23 || f == 0x22)) {
            out_w = w;
            out_h = h;
            out_stride = s;
            out_format = f;
            return true;
        }
    }
    return false;
}

static void applyFrameOverwrite(void* gb, void* vaddr) {
    if (!gb || !vaddr) return;
    if (!g_shm_packet) {
        g_shm_packet = CsShmManager::openReader();
    }
    if (!g_shm_packet || g_shm_packet->magic != CS_SHM_MAGIC) return;

    int32_t w = 0, h = 0, stride = 0, format = 0;
    if (extractBufferMeta(gb, w, h, stride, format)) {
        GraphicBufferBridge::copyAndConvertFrame(g_shm_packet, vaddr, w, h, format, stride);
        g_intercepted_count++;
        if (g_intercepted_count % 60 == 1) {
            LOGI("【CS】【GraphicBufferHook】成功覆写相机显存: TotalFrames=%llu, Format=0x%x, W=%d, H=%d, Stride=%d",
                 static_cast<unsigned long long>(g_intercepted_count), format, w, h, stride);
        }
    }
}

// Hooked GraphicBuffer::lock (2 params 32-bit)
static int my_GB_lock2(void* gb, uint32_t usage, void** vaddr) {
    int res = orig_GB_lock2 ? orig_GB_lock2(gb, usage, vaddr) : -1;
    if (res == 0 && vaddr && *vaddr) {
        applyFrameOverwrite(gb, *vaddr);
    }
    return res;
}

// Hooked GraphicBuffer::lock (2 params 64-bit)
static int my_GB_lock2_64(void* gb, uint64_t usage, void** vaddr) {
    int res = orig_GB_lock2_64 ? orig_GB_lock2_64(gb, usage, vaddr) : -1;
    if (res == 0 && vaddr && *vaddr) {
        applyFrameOverwrite(gb, *vaddr);
    }
    return res;
}

// Hooked GraphicBuffer::lock (4 params 32-bit)
static int my_GB_lock4(void* gb, uint32_t usage, void** vaddr, int32_t* outBytesPerPixel, int32_t* outBytesPerStride) {
    int res = orig_GB_lock4 ? orig_GB_lock4(gb, usage, vaddr, outBytesPerPixel, outBytesPerStride) : -1;
    if (res == 0 && vaddr && *vaddr) {
        applyFrameOverwrite(gb, *vaddr);
    }
    return res;
}

// Hooked GraphicBuffer::lock (4 params 64-bit)
static int my_GB_lock4_64(void* gb, uint64_t usage, void** vaddr, int32_t* outBytesPerPixel, int32_t* outBytesPerStride) {
    int res = orig_GB_lock4_64 ? orig_GB_lock4_64(gb, usage, vaddr, outBytesPerPixel, outBytesPerStride) : -1;
    if (res == 0 && vaddr && *vaddr) {
        applyFrameOverwrite(gb, *vaddr);
    }
    return res;
}

// Hooked GraphicBuffer::lockYCbCr (32-bit)
static int my_GB_lockYCbCr(void* gb, uint32_t usage, void* ycbcr) {
    int res = orig_GB_lockYCbCr ? orig_GB_lockYCbCr(gb, usage, ycbcr) : -1;
    if (res == 0 && ycbcr) {
        void** y_plane = reinterpret_cast<void**>(ycbcr);
        if (*y_plane) {
            applyFrameOverwrite(gb, *y_plane);
        }
    }
    return res;
}

// Hooked GraphicBuffer::lockYCbCr (64-bit)
static int my_GB_lockYCbCr_64(void* gb, uint64_t usage, void* ycbcr) {
    int res = orig_GB_lockYCbCr_64 ? orig_GB_lockYCbCr_64(gb, usage, ycbcr) : -1;
    if (res == 0 && ycbcr) {
        void** y_plane = reinterpret_cast<void**>(ycbcr);
        if (*y_plane) {
            applyFrameOverwrite(gb, *y_plane);
        }
    }
    return res;
}

// Hooked BufferQueueProducer::queueBuffer
static int my_queueBuffer(void* thiz, int slot, const void* input, void* output) {
    if (orig_queueBuffer) {
        return orig_queueBuffer(thiz, slot, input, output);
    }
    return 0;
}

extern "C" {

void init_camera_server_hook() {
    LOGI("Initializing CameraServer Native Hook...");

    const char* libui_paths[] = {
        "libui.so",
        "/odm/lib64/libui.so",
        "/vendor/lib64/libui.so",
        "/system/lib64/libui.so",
        "/odm/lib/libui.so",
        "/vendor/lib/libui.so",
        "/system/lib/libui.so",
        nullptr
    };

    void* libui = nullptr;
    for (int i = 0; libui_paths[i] != nullptr; ++i) {
        libui = dlopen(libui_paths[i], RTLD_NOLOAD);
        if (!libui) libui = dlopen(libui_paths[i], RTLD_NOW);
        if (libui) {
            LOGI("Loaded libui successfully from: %s", libui_paths[i]);
            break;
        }
    }

    if (libui) {
        void* sym_lock2_32 = dlsym(libui, "_ZN7android13GraphicBuffer4lockEjPPv");
        void* sym_lock2_64 = dlsym(libui, "_ZN7android13GraphicBuffer4lockEyPPv");
        void* sym_lock4_32 = dlsym(libui, "_ZN7android13GraphicBuffer4lockEjPPvPiS3_");
        void* sym_lock4_64 = dlsym(libui, "_ZN7android13GraphicBuffer4lockEyPPvPiS3_");
        void* sym_locky_32 = dlsym(libui, "_ZN7android13GraphicBuffer9lockYCbCrEjP13android_ycbcr");
        void* sym_locky_64 = dlsym(libui, "_ZN7android13GraphicBuffer9lockYCbCrEyP13android_ycbcr");

        if (sym_lock2_32) {
            DobbyHook(sym_lock2_32, (dobby_dummy_func_t)my_GB_lock2, (dobby_dummy_func_t*)&orig_GB_lock2);
            LOGI("DobbyHook GraphicBuffer::lock (2-arg 32) installed");
        }
        if (sym_lock2_64) {
            DobbyHook(sym_lock2_64, (dobby_dummy_func_t)my_GB_lock2_64, (dobby_dummy_func_t*)&orig_GB_lock2_64);
            LOGI("DobbyHook GraphicBuffer::lock (2-arg 64) installed");
        }
        if (sym_lock4_32) {
            DobbyHook(sym_lock4_32, (dobby_dummy_func_t)my_GB_lock4, (dobby_dummy_func_t*)&orig_GB_lock4);
            LOGI("DobbyHook GraphicBuffer::lock (4-arg 32) installed");
        }
        if (sym_lock4_64) {
            DobbyHook(sym_lock4_64, (dobby_dummy_func_t)my_GB_lock4_64, (dobby_dummy_func_t*)&orig_GB_lock4_64);
            LOGI("DobbyHook GraphicBuffer::lock (4-arg 64) installed");
        }
        if (sym_locky_32) {
            DobbyHook(sym_locky_32, (dobby_dummy_func_t)my_GB_lockYCbCr, (dobby_dummy_func_t*)&orig_GB_lockYCbCr);
            LOGI("DobbyHook GraphicBuffer::lockYCbCr (32) installed");
        }
        if (sym_locky_64) {
            DobbyHook(sym_locky_64, (dobby_dummy_func_t)my_GB_lockYCbCr_64, (dobby_dummy_func_t*)&orig_GB_lockYCbCr_64);
            LOGI("DobbyHook GraphicBuffer::lockYCbCr (64) installed");
        }
    } else {
        LOGE("Failed to dlopen libui.so from any path");
    }

    const char* libgui_paths[] = {
        "libgui.so",
        "/system/lib64/libgui.so",
        "/odm/lib64/libgui.so",
        "/vendor/lib64/libgui.so",
        "/system/lib/libgui.so",
        "/odm/lib/libgui.so",
        "/vendor/lib/libgui.so",
        nullptr
    };

    void* libgui = nullptr;
    for (int i = 0; libgui_paths[i] != nullptr; ++i) {
        libgui = dlopen(libgui_paths[i], RTLD_NOLOAD);
        if (!libgui) libgui = dlopen(libgui_paths[i], RTLD_NOW);
        if (libgui) {
            LOGI("Loaded libgui successfully from: %s", libgui_paths[i]);
            break;
        }
    }

    if (libgui) {
        const char* syms[] = {
            "_ZN7android19BufferQueueProducer11queueBufferEiRKNS_12IGraphicBufferProducer12QueueBufferInputEPNS2_13QueueBufferOutputE",
            "_ZN7android19BufferQueueProducer11queueBufferEiRKNS_19IGraphicBufferProducer12QueueBufferInputEPNS2_13QueueBufferOutputE",
            "_ZN7android19BufferQueueProducer11queueBufferEiRKNS_13ProducerInputEPNS_14ProducerOutputE",
            nullptr
        };

        void* target = nullptr;
        for (int i = 0; syms[i] != nullptr; ++i) {
            target = dlsym(libgui, syms[i]);
            if (target) {
                LOGI("Found BufferQueueProducer::queueBuffer symbol: %s", syms[i]);
                break;
            }
        }

        if (target) {
            int ret = DobbyHook(target, (dobby_dummy_func_t)my_queueBuffer, (dobby_dummy_func_t*)&orig_queueBuffer);
            LOGI("DobbyHook queueBuffer result: %d", ret);
        }
    }

    // Open shared memory
    g_shm_packet = CsShmManager::openReader();
    if (g_shm_packet) {
        LOGI("Connected to CamSwap shared memory successfully");
    } else {
        LOGI("Shared memory not created yet, will retry on frame capture");
    }
}

void release_camera_server_hook() {
    LOGI("Releasing CameraServer Native Hook...");
    if (g_shm_packet) {
        CsShmManager::closeShm(g_shm_packet);
        g_shm_packet = nullptr;
    }
}

// Auto-run when injected into cameraserver
__attribute__((constructor))
static void on_library_loaded() {
    LOGI("libcs_camserver.so injected and loaded into PID %d", getpid());
    init_camera_server_hook();
}

__attribute__((destructor))
static void on_library_unloaded() {
    LOGI("libcs_camserver.so unloaded from PID %d", getpid());
    release_camera_server_hook();
}

}


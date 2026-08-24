#pragma once

#include <cstdint>
#include <cstddef>
#include "camserver_shm.h"

// Android HAL Pixel Formats
#define CS_HAL_PIXEL_FORMAT_RGBA_8888 1
#define CS_HAL_PIXEL_FORMAT_RGBX_8888 2
#define CS_HAL_PIXEL_FORMAT_RGB_888   3
#define CS_HAL_PIXEL_FORMAT_RGB_565   4
#define CS_HAL_PIXEL_FORMAT_BGRA_8888 5
#define CS_HAL_PIXEL_FORMAT_YV12      0x32315659
#define CS_HAL_PIXEL_FORMAT_NV21      0x11
#define CS_HAL_PIXEL_FORMAT_YUV420888 0x23

// Buffer usage flags
#define CS_GRALLOC_USAGE_SW_READ_OFTEN  0x00000003
#define CS_GRALLOC_USAGE_SW_WRITE_OFTEN 0x00000030

/**
 * GraphicBuffer 内存锁与像素覆写工具类
 */
class GraphicBufferBridge {
public:
    /**
     * 将共享内存中的视频帧复制并转换到目标 GraphicBuffer 显存地址中
     *
     * @param packet     共享内存中的当前视频帧数据包
     * @param dst_vaddr  已映射的目标 GraphicBuffer 起始虚拟地址
     * @param dst_width  目标缓冲区宽度
     * @param dst_height 目标缓冲区高度
     * @param dst_format 目标缓冲区像素格式 (如 NV21, YV12, RGBA)
     * @param dst_stride 目标行跨度 (Stride / Row pitch)
     * @return 成功写入的字节数，<=0 表示失败
     */
    static int copyAndConvertFrame(
        const CameraFramePacket* packet,
        void* dst_vaddr,
        int dst_width,
        int dst_height,
        int dst_format,
        int dst_stride
    );

    /**
     * 对 YUV420P / NV21 像素数据进行环境反光色温混合 (三色活体反光注入)
     */
    static void applyAmbientColorFilter(
        uint8_t* y_plane,
        uint8_t* uv_plane,
        int width,
        int height,
        int stride,
        float r_tint,
        float g_tint,
        float b_tint,
        float intensity
    );
};

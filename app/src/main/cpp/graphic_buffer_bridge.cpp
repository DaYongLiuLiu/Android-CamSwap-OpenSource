#include "graphic_buffer_bridge.h"
#include <cstring>
#include <algorithm>

int GraphicBufferBridge::copyAndConvertFrame(
    const CameraFramePacket* packet,
    void* dst_vaddr,
    int dst_width,
    int dst_height,
    int dst_format,
    int dst_stride
) {
    if (!packet || !dst_vaddr || dst_width <= 0 || dst_height <= 0 || dst_stride <= 0) {
        return -1;
    }

    uint8_t* dst = static_cast<uint8_t*>(dst_vaddr);
    const uint8_t* src = packet->data;
    int src_w = packet->width;
    int src_h = packet->height;

    if (src_w <= 0 || src_h <= 0) {
        return -1;
    }

    int copy_w = std::min(src_w, dst_width);
    int copy_h = std::min(src_h, dst_height);

    // NV21 / YUV420_888 / YV12 格式处理
    if (dst_format == CS_HAL_PIXEL_FORMAT_NV21 ||
        dst_format == CS_HAL_PIXEL_FORMAT_YUV420888 ||
        dst_format == CS_HAL_PIXEL_FORMAT_YV12) {

        // 1. 复制 Y 平面 (逐行处理 stride 对齐)
        for (int r = 0; r < copy_h; ++r) {
            std::memcpy(dst + r * dst_stride, src + r * src_w, copy_w);
            if (dst_stride > copy_w) {
                // 填充行尾多余的 padding
                std::memset(dst + r * dst_stride + copy_w, 0, dst_stride - copy_w);
            }
        }

        // 2. 复制 UV 平面
        int uv_src_offset = src_w * src_h;
        int uv_dst_offset = dst_stride * dst_height;
        int uv_copy_h = copy_h / 2;

        for (int r = 0; r < uv_copy_h; ++r) {
            std::memcpy(dst + uv_dst_offset + r * dst_stride,
                        src + uv_src_offset + r * src_w,
                        copy_w);
        }

        // 3. 如果开启了三色活体反光注入，进行 YUV 色度实时调色
        if (packet->ambient_intensity > 0.001f) {
            applyAmbientColorFilter(
                dst,
                dst + uv_dst_offset,
                copy_w,
                copy_h,
                dst_stride,
                packet->ambient_r_color,
                packet->ambient_g_color,
                packet->ambient_b_color,
                packet->ambient_intensity
            );
        }

        return dst_stride * dst_height * 3 / 2;
    }

    // RGBA 格式回退处理
    if (dst_format == CS_HAL_PIXEL_FORMAT_RGBA_8888 || dst_format == CS_HAL_PIXEL_FORMAT_RGBX_8888) {
        int dst_bytes_per_row = dst_stride * 4;
        int copy_bytes = copy_w * 4;
        for (int r = 0; r < copy_h; ++r) {
            std::memcpy(dst + r * dst_bytes_per_row, src + r * copy_bytes, copy_bytes);
        }
        return dst_bytes_per_row * dst_height;
    }

    return -1;
}

void GraphicBufferBridge::applyAmbientColorFilter(
    uint8_t* y_plane,
    uint8_t* uv_plane,
    int width,
    int height,
    int stride,
    float r_tint,
    float g_tint,
    float b_tint,
    float intensity
) {
    if (!y_plane || !uv_plane || width <= 0 || height <= 0 || intensity <= 0.0f) {
        return;
    }

    // 计算 RGB 反光偏移在 YUV 色彩空间下的分量偏移 (Fixed-Point 优化)
    // dY =  0.299 * dR + 0.587 * dG + 0.114 * dB
    // dU = -0.169 * dR - 0.331 * dG + 0.500 * dB
    // dV =  0.500 * dR - 0.419 * dG - 0.081 * dB
    float dr = (r_tint * 255.0f - 128.0f) * intensity * 0.45f;
    float dg = (g_tint * 255.0f - 128.0f) * intensity * 0.45f;
    float db = (b_tint * 255.0f - 128.0f) * intensity * 0.45f;

    int delta_u = static_cast<int>(-0.169f * dr - 0.331f * dg + 0.500f * db);
    int delta_v = static_cast<int>( 0.500f * dr - 0.419f * dg - 0.081f * db);
    int delta_y = static_cast<int>( 0.299f * dr + 0.587f * dg + 0.114f * db) / 2;

    int uv_h = height / 2;
    int uv_w = width;

    for (int r = 0; r < uv_h; ++r) {
        uint8_t* row = uv_plane + r * stride;
        for (int c = 0; c < uv_w; c += 2) {
            // NV21: [V, U, V, U, ...]
            int v_val = static_cast<int>(row[c]) + delta_v;
            int u_val = static_cast<int>(row[c + 1]) + delta_u;

            row[c]     = static_cast<uint8_t>(std::clamp(v_val, 0, 255));
            row[c + 1] = static_cast<uint8_t>(std::clamp(u_val, 0, 255));
        }
    }

    if (delta_y != 0) {
        for (int r = 0; r < height; ++r) {
            uint8_t* row = y_plane + r * stride;
            for (int c = 0; c < width; ++c) {
                int y_val = static_cast<int>(row[c]) + delta_y;
                row[c] = static_cast<uint8_t>(std::clamp(y_val, 0, 255));
            }
        }
    }
}

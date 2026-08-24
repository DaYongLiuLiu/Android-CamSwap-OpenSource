#pragma once

#include <cstdint>
#include <atomic>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>

// CameraServer 与前端通信的共享内存/映射文件路径定义 (Android 兼容)
#define CS_SHM_NAME "/data/local/tmp/cs_cam_shm"
#define CS_SHM_MAGIC 0x43534341 // "CSCA"
#define CS_MAX_FRAME_SIZE (3840 * 2160 * 2) // 支持最大 4K 分辨率帧

// 共享内存帧头格式
struct CameraFramePacket {
    uint32_t magic;                     // CS_SHM_MAGIC
    uint32_t version;                   // 协议版本 1
    std::atomic<uint64_t> sequence;      // 帧序号及计数
    uint64_t timestamp_ns;              // 纳秒时间戳
    int32_t width;                      // 图像宽度
    int32_t height;                     // 图像高度
    int32_t format;                     // 1=NV21, 2=NV12, 3=YUV420P, 4=RGBA
    int32_t rotation;                   // 0, 90, 180, 270
    float ambient_r_color;              // 环境三色光 - R
    float ambient_g_color;              // 环境三色光 - G
    float ambient_b_color;              // 环境三色光 - B
    float ambient_intensity;            // 环境光强度
    uint32_t data_size;                 // 帧数据实际字节数
    uint8_t padding[64];                // 64字节保留字段
    uint8_t data[CS_MAX_FRAME_SIZE];    // 原始帧数据
};

// 共享内存管理辅助类 (Android 兼容 open/mmap)
class CsShmManager {
public:
    static CameraFramePacket* openReader() {
        int fd = ::open(CS_SHM_NAME, O_RDONLY);
        if (fd < 0) {
            return nullptr;
        }
        void* ptr = ::mmap(nullptr, sizeof(CameraFramePacket), PROT_READ, MAP_SHARED, fd, 0);
        ::close(fd);
        if (ptr == MAP_FAILED) {
            return nullptr;
        }
        return static_cast<CameraFramePacket*>(ptr);
    }

    static CameraFramePacket* openWriter() {
        int fd = ::open(CS_SHM_NAME, O_CREAT | O_RDWR, 0666);
        if (fd < 0) {
            return nullptr;
        }
        if (::ftruncate(fd, sizeof(CameraFramePacket)) != 0) {
            ::close(fd);
            return nullptr;
        }
        void* ptr = ::mmap(nullptr, sizeof(CameraFramePacket), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        ::close(fd);
        if (ptr == MAP_FAILED) {
            return nullptr;
        }
        CameraFramePacket* pkg = static_cast<CameraFramePacket*>(ptr);
        pkg->magic = CS_SHM_MAGIC;
        pkg->version = 1;
        pkg->sequence.store(0, std::memory_order_relaxed);
        return pkg;
    }

    static void closeShm(CameraFramePacket* pkg) {
        if (pkg) {
            ::munmap(pkg, sizeof(CameraFramePacket));
        }
    }
};

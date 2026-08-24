#pragma once

#include <string>
#include <atomic>
#include <thread>
#include "camserver_shm.h"

/**
 * 方案二：独立视频解码与帧供应守护进程 (cs-daemon)
 */
class CsDaemon {
public:
    CsDaemon();
    ~CsDaemon();

    bool initShm();
    void startFramePump(int target_fps = 30);
    void stopFramePump();

    // 控制指令处理
    std::string handleCommand(const std::string& cmd_line);

    void setRotation(int degrees);
    void setAmbientColor(float r, float g, float b, float intensity);
    void setPlaying(bool playing);

    bool isRunning() const { return m_running.load(); }
    uint64_t getProducedFrames() const { return m_frames_produced.load(); }

    // 更新当前帧像素数据并推入共享内存
    void pushFrame(const uint8_t* yuv_data, int width, int height, int format);

private:
    void framePumpLoop(int target_fps);

    CameraFramePacket* m_shm_packet;
    std::atomic<bool> m_running;
    std::atomic<bool> m_playing;
    std::atomic<uint64_t> m_frames_produced;
    std::thread m_pump_thread;

    int m_width;
    int m_height;
    int m_format;
    int m_rotation;

    float m_ambient_r;
    float m_ambient_g;
    float m_ambient_b;
    float m_ambient_intensity;
};

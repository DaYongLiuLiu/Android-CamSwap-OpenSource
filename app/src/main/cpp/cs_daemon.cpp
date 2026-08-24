#include "cs_daemon.h"
#include <iostream>
#include <sstream>
#include <vector>
#include <chrono>
#include <cstring>
#include <algorithm>

CsDaemon::CsDaemon()
    : m_shm_packet(nullptr),
      m_running(false),
      m_playing(false),
      m_frames_produced(0),
      m_width(1280),
      m_height(720),
      m_format(1), // NV21
      m_rotation(0),
      m_ambient_r(0.0f),
      m_ambient_g(0.0f),
      m_ambient_b(0.0f),
      m_ambient_intensity(0.0f) {}

CsDaemon::~CsDaemon() {
    stopFramePump();
    if (m_shm_packet) {
        CsShmManager::closeShm(m_shm_packet);
        m_shm_packet = nullptr;
    }
}

bool CsDaemon::initShm() {
    m_shm_packet = CsShmManager::openWriter();
    return m_shm_packet != nullptr;
}

void CsDaemon::pushFrame(const uint8_t* yuv_data, int width, int height, int format) {
    if (!m_shm_packet || !yuv_data || width <= 0 || height <= 0) {
        return;
    }

    m_width = width;
    m_height = height;
    m_format = format;

    m_shm_packet->width = width;
    m_shm_packet->height = height;
    m_shm_packet->format = format;
    m_shm_packet->rotation = m_rotation;
    m_shm_packet->ambient_r_color = m_ambient_r;
    m_shm_packet->ambient_g_color = m_ambient_g;
    m_shm_packet->ambient_b_color = m_ambient_b;
    m_shm_packet->ambient_intensity = m_ambient_intensity;

    int data_size = width * height * 3 / 2;
    if (data_size > CS_MAX_FRAME_SIZE) {
        data_size = CS_MAX_FRAME_SIZE;
    }
    m_shm_packet->data_size = data_size;

    std::memcpy(m_shm_packet->data, yuv_data, data_size);

    auto now = std::chrono::steady_clock::now().time_since_epoch();
    m_shm_packet->timestamp_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now).count();

    // 原子递增序列号，通知消费者有新帧
    uint64_t seq = m_frames_produced.fetch_add(1) + 1;
    m_shm_packet->sequence.store(seq, std::memory_order_release);
}

void CsDaemon::startFramePump(int target_fps) {
    if (m_running.load()) return;
    if (!m_shm_packet) {
        initShm();
    }
    m_running.store(true);
    m_playing.store(true);
    m_pump_thread = std::thread(&CsDaemon::framePumpLoop, this, target_fps);
}

void CsDaemon::stopFramePump() {
    if (!m_running.load()) return;
    m_running.store(false);
    if (m_pump_thread.joinable()) {
        m_pump_thread.join();
    }
}

void CsDaemon::setRotation(int degrees) {
    m_rotation = ((degrees % 360) + 360) % 360;
    if (m_shm_packet) {
        m_shm_packet->rotation = m_rotation;
    }
}

void CsDaemon::setAmbientColor(float r, float g, float b, float intensity) {
    m_ambient_r = r;
    m_ambient_g = g;
    m_ambient_b = b;
    m_ambient_intensity = intensity;
    if (m_shm_packet) {
        m_shm_packet->ambient_r_color = r;
        m_shm_packet->ambient_g_color = g;
        m_shm_packet->ambient_b_color = b;
        m_shm_packet->ambient_intensity = intensity;
    }
}

void CsDaemon::setPlaying(bool playing) {
    m_playing.store(playing);
}

std::string CsDaemon::handleCommand(const std::string& cmd_line) {
    std::istringstream iss(cmd_line);
    std::string cmd;
    iss >> cmd;

    if (cmd == "PLAY") {
        setPlaying(true);
        return "OK: PLAYING";
    } else if (cmd == "PAUSE") {
        setPlaying(false);
        return "OK: PAUSED";
    } else if (cmd == "ROTATE") {
        int deg = 0;
        if (iss >> deg) {
            setRotation(deg);
            return "OK: ROTATION=" + std::to_string(m_rotation);
        }
        return "ERR: MISSING_PARAM";
    } else if (cmd == "COLOR") {
        float r, g, b, intensity;
        if (iss >> r >> g >> b >> intensity) {
            setAmbientColor(r, g, b, intensity);
            return "OK: COLOR_SET";
        }
        return "ERR: MISSING_PARAM";
    } else if (cmd == "STATUS") {
        std::ostringstream oss;
        oss << "OK: RUNNING=" << m_running.load()
            << " PLAYING=" << m_playing.load()
            << " FRAMES=" << m_frames_produced.load()
            << " ROTATION=" << m_rotation
            << " INTENSITY=" << m_ambient_intensity;
        return oss.str();
    }

    return "ERR: UNKNOWN_CMD";
}

void CsDaemon::framePumpLoop(int target_fps) {
    if (target_fps <= 0) target_fps = 30;
    int frame_interval_ms = 1000 / target_fps;

    int test_w = 640;
    int test_h = 480;
    std::vector<uint8_t> dummy_frame(test_w * test_h * 3 / 2, 128);

    uint8_t pattern = 0;

    while (m_running.load()) {
        auto start = std::chrono::steady_clock::now();

        if (m_playing.load()) {
            pattern = (pattern + 1) % 255;
            std::memset(dummy_frame.data(), pattern, test_w * test_h);
            pushFrame(dummy_frame.data(), test_w, test_h, 1);

            uint64_t produced = m_frames_produced.load();
            if (produced % 60 == 1) {
                printf("【CS】【Daemon】视频帧泵持续工作中: TargetFPS=%d, TotalFramesProduced=%llu, Rot=%d, AmbientIntensity=%.2f\n",
                       target_fps, static_cast<unsigned long long>(produced), m_rotation, m_ambient_intensity);
            }
        }

        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - start).count();

        int sleep_ms = frame_interval_ms - static_cast<int>(elapsed);
        if (sleep_ms > 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(sleep_ms));
        }
    }
}

#ifndef CS_DAEMON_NO_MAIN
int main(int argc, char* argv[]) {
    printf("[CS Daemon] Starting CamSwap video decoding daemon...\n");
    CsDaemon daemon;
    if (!daemon.initShm()) {
        fprintf(stderr, "[CS Daemon] Failed to initialize shared memory\n");
        return 1;
    }
    printf("[CS Daemon] Shared memory initialized, starting frame pump (30 FPS)...\n");
    daemon.startFramePump(30);

    while (daemon.isRunning()) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    return 0;
}
#endif

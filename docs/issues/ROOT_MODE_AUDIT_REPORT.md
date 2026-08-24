# 《Android CamSwap (cs) Root 模式实现深度检查报告》

---

## 一、 架构体系与模块总览

CamSwap 的 **Root 模式（方案二：CameraServer 系统级注入）** 旨在摆脱对 Xposed/LSPosed 框架的依赖，通过在 Android 底层媒体服务 `cameraserver` 进程中实施 Native Inline Hook，实现全局相机视频流劫持。

### 模块全景图
```mermaid
graph TD
    UI[App UI: SettingsScreen / MainViewModel] -->|1. 触发模式切换| CSB[CameraServerBridge.kt]
    CSB -->|2. 复制二进制与授权| DIR[/data/local/tmp/]
    CSB -->|3. 执行 su 命令| INJ[cs-injector / 进程注入器]
    INJ -->|4. PTRACE_ATTACH & dlopen| CS_PROC[cameraserver 进程]
    CS_PROC -->|5. 构造函数加载| CSH[libcs_camserver.so / camserver_hook.cpp]
    CSH -->|6. Dobby Hook| BQP[BufferQueueProducer::queueBuffer]
    CSH -->|7. mmap openReader| SHM[/data/local/tmp/cs_cam_shm 共享内存]
    DAEMON[cs-daemon / 帧供应守护进程] -->|8. mmap openWriter & 推帧| SHM
    GBB[GraphicBufferBridge.cpp] -.->|9. YUV/NV21 转换与活体反光| CSH
```

---

## 二、 核心代码组件审查详情

| 核心组件 | 文件路径 | 当前实现状态 | 审查结论 |
| :--- | :--- | :--- | :--- |
| **Java 控制桥** | `CameraServerBridge.kt` | 已实现 | 支持 Root 权限检测、PID 查询、文件部署（chmod 777/chcon）、触发注入命令及状态监测。 |
| **Root 注入器** | `cs_injector.cpp` | 已实现 | 支持基于 `/proc` 进程扫描、`ptrace` 跨进程附加、多架构寄存器操作（arm64/arm32/x86_64）、`dlopen` 远程调用。 |
| **底层 Hook 模块** | `camserver_hook.cpp` | 骨架已完成，**核心覆写缺失** | 通过 `Dobby` 拦截 `BufferQueueProducer::queueBuffer`，并打开共享内存，但**尚未真正调用像素覆写逻辑**。 |
| **共享内存模型** | `camserver_shm.h` | 已实现 | 基于 POSIX `open/mmap` 实现无锁共享内存通信，包含帧序号原子变量、时间戳及活体色彩元数据。 |
| **图像转换与反光引擎** | `graphic_buffer_bridge.cpp` | 已实现 | 实现了 NV21/YUV420_888/YV12/RGBA 格式内存对齐拷贝与定点数三色活体反光色温混合算法。 |
| **独立视频帧守护进程** | `cs_daemon.cpp` | 独立实现，**未接入 UI 管线** | 实现了独立帧泵线程（30fps 测试帧）与指令解析，但未与前端视频播放器和部署流程形成联动。 |

---

## 三、 检查发现的核心问题与技术缺陷 (Defects & Risks)

### 🔴 1. 【高危·核心功能未闭环】`camserver_hook.cpp` 未调用 `GraphicBufferBridge` 覆写显存
- **现状代码**：
  ```cpp
  if (seq != g_last_rendered_seq) {
      g_last_rendered_seq = seq;
      // Frame replacement logic:
      // The GraphicBuffer inside BufferQueue slot can be acquired and overwritten here
  }
  ```
- **问题分析**：Hook 命中后，当前代码只更新了 `g_last_rendered_seq` 并输出了 Log，**未从 `BufferQueueProducer` 或 slot 中锁定 GraphicBuffer 显存虚拟地址，也未调用 `GraphicBufferBridge::copyAndConvertFrame` 写入共享内存数据**。导致虽然注入成功，但实际输出画面仍为原相机物理画面。

### 🔴 2. 【高危·链路断层】`cs-daemon` 与前端 UI / 视频管线未联动
- **现状**：
  1. `CameraServerBridge.kt` 在执行 `injectCameraServer()` 时，仅部署了 `cs-injector` 和 `libcs_camserver.so`，**没有将 `cs_daemon` 推送至 `/data/local/tmp` 并以后台守护进程方式拉起**。
  2. 前端 Java 层播放视频时（`MediaPlayerManager` / `SurfaceRelay`），尚未集成将解码像素帧推入 `/data/local/tmp/cs_cam_shm` 的 JNI 写入通道。

### 🟡 3. 【中危·系统兼容】SELinux 拦截与 Enforcing 限制
- **问题分析**：
  - `cameraserver` 在 Android 8.0+ 运行在受限域 `u:r:cameraserver:s0` 下。
  - 即使对 `/data/local/tmp/libcs_camserver.so` 执行了 `chcon u:object_r:system_file:s0`，在 SELinux `Enforcing` 模式下，Android 安全策略仍会通过 `neverallow` 规则拦截 `cameraserver` 读取 `/data/local/tmp` 目录。
- **影响**：可能导致 `ptrace` 调用 `dlopen` 时返回 `0x0`（Permission denied 失败）。

### 🟡 4. 【中危·注入兼容】Android Bionic Linker 命名空间隔离
- **问题分析**：从 Android 7.0 (Nougat) 起，Linker 引入了 Namespace 隔离机制。`cameraserver` 进程内的默认 Linker Namespace 默认禁止从 `/data/local/tmp/` 加载共享库。
- **对策**：需要将 SO 部署到被允许的白名单路径（如通过 Magisk 挂载模块至 `/system/lib64/`）或在内存中绕过 Linker Namespace 限制。

### 🟡 5. 【低危·符号重整】BufferQueue C++ 符号版本差异
- **现状**：`camserver_hook.cpp` 写死了 `_ZN7android19BufferQueueProducer11queueBufferEiRKNS_12IGraphicBufferProducer12QueueBufferInputEPNS2_13QueueBufferOutputE`。
- **风险**：在某些定制 ROM 或较新的 Android 版本中，函数签名/符号名称若发生变动，`dlsym` 将失败。

---

## 四、 优化与完善路线建议

1. **补全 `my_queueBuffer` 真实显存锁入与覆写**：
   - 解析 `IGraphicBufferProducer` 槽位中的 `sp<GraphicBuffer>`。
   - 调用 `GraphicBuffer::lock()` 获取映射的虚拟地址 `vaddr`、`stride` 与像素格式。
   - 调用 `GraphicBufferBridge::copyAndConvertFrame(g_shm_packet, vaddr, width, height, format, stride)`。
   - 完成后调用 `GraphicBuffer::unlock()`。
2. **打通 Java 视频帧写入 JNI 通道**：
   - 在 `CameraServerBridge` 中增加 JNI 接口：`nativePushFrame(byte[] yuvData, int width, int height, int format)`，让应用内的 ExoPlayer / MediaCodec 解码后可直接向共享内存推流。
3. **增强 SELinux 与权限容错**：
   - 在 `CameraServerBridge.kt` 注入前检查 `getenforce` 状态，必要时执行 `su -c setenforce 0` 或适配 Magisk 模块路径。

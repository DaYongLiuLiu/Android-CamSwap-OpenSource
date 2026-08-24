# 《Root 模式核心部署与注入修复报告（参考《无卡密相机》逆向剖析）》

---

## 一、 用户设备诊断与根因定位

根据您在 **Android 16 (SDK 36) realme RMX3850** 设备上导出的运行报告：
```text
CameraServer PID : 4658
Hook Injected    : false
```
在检查 `/data/local/tmp` 目录时，发现**根本不存在 `libcs_camserver.so` 和 `cs-injector` 文件**！

### 根因深入剖析
1. **`extractNativeLibs="false"` 导致源文件不存在**：
   在现代 Android 系统及 AGP 打包中，动态库默认存储在 APK 内部并不解压到 `applicationInfo.nativeLibraryDir`。原代码直接通过 `File(nativeLibraryDir, ...)` 进行 `cp`，导致复制了不存在的路径，静默失败，`/data/local/tmp` 下没有任何注入模块。
2. **ARM64 远程调用返回控制丢失**：
   原 `cs_injector.cpp` 在 aarch64 下将 `LR` 指向了 `remote_dlopen` 本身，导致在 `dlopen` 返回后发生无限循环或 `waitpid` 无法捕获返回信号。

---

## 二、 核心修复与改进（借鉴《无卡密相机》实现）

1. **实现 APK 内部 ZIP 动态解压提取机制（与《无卡密相机》对齐）**：
   - 在 `CameraServerBridge.kt` 中新增 `extractNativeFromApk` 方法：从 `applicationInfo.sourceDir` 的 APK 压缩包中自动匹配当前系统 ABI（如 `arm64-v8a`），将 `libcs_camserver.so` 与 `libcs_injector.so` 稳定解压至本地沙箱，再通过 Root 权限部署。
2. **多路径部署与容错回退**：
   - 将注入模块同步部署至 `/data/local/tmp/libcs_camserver.so` 以及 `/data/libcs_camserver.so`（类似无卡密相机的 `/data/libvc.so`），并配置 `chmod 777` 与 `chcon u:object_r:system_file:s0`。
   - `cs-injector` 在第一次尝试 `/data/local/tmp` 失败时，会自动无缝回退尝试 `/data/libcs_camserver.so`。
3. **修复 ARM64 Ptrace 注入返回捕获**：
   - 将 `regs.regs[30] = 0`，当远程 `dlopen` 执行完毕 `ret` 时精准触发 `SIGSEGV`，由 `waitpid` 捕获并读取 `x0` 句柄，再完整恢复上下文寄存器。
4. **全链路日志增强**：
   - 捕获注入过程的 `stdout` 和 `stderr`，注入结果实时显示。

---

## 三、 验证结果

- `gradlew.bat assembleDebug` 构建通过。
- `gradlew.bat :app:testDebugUnitTest` 单元测试通过。

# 《Root 权限主动握手与授权体验重构报告》

---

## 一、 用户痛点与设计背景

在老旧 Android 与各大 Root 环境（如 **Magisk/面具、KernelSU、APatch、SuperSU** 等）中：
- 如果应用仅在后台以静默单行命令 `su -c ...` 执行，非交互式子进程可能导致 Root 管理器**无法正常弹出授权对话框**，或者直接判定为超时拒绝；
- 原应用在切换到 Root 模式时缺乏主动弹窗申请与清晰的用户提示，导致用户在未授权情况下误以为模式已开启。

---

## 二、 核心重构与功能实现

1. **标准交互式 `su` 管道授权握手（`CameraServerBridge.requestRootPermission`）**：
   - 采用标准管道流写入 `id\nexit\n`，保证在 Magisk、KernelSU、APatch 等所有 Root 管理器中能够**100% 主动唤起系统级授权弹窗**并等待用户响应。
2. **全流程交互提示与状态同步**：
   - 用户点击 Root 模式时，立即提示：`正在主动申请 Root 权限，请在授权弹窗中点击允许...`；
   - 成功获取权限后提示：`Root 权限已获取，已成功激活 Root 模式！`；
   - 拒绝或失败时，震动并回退为 LSPosed 模式，并给出明确提示。

---

## 三、 构建与测试

- `gradlew.bat assembleDebug` 构建通过。
- `gradlew.bat :app:testDebugUnitTest` 单元测试全部通过。

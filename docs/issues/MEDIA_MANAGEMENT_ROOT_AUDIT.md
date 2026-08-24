# 《Root 模式与媒体管理页面视频读取全链路审计与增强报告》

---

## 一、 审计结果与发现的隐患

在排查“管理页面选择视频与 Root 模式推流读取”全链路时，发现两个重要逻辑隐患：

1. **宿主 App 内读取文件路径回退为 null 的问题**：
   - 当用户在管理页面选择视频（如 `VID20260702204124.mp4`）时，`ConfigManager` 记录了 `key_selected_video`。
   - 原代码调用 `HookGuards.getCurrentVideoFile()`，其底层在宿主 App 进程判断 `providerAvailable` 为 true，导致将 `current_video_path` 置为 null 并回退到默认的 `Cam.mp4`。如果目录下没有 `Cam.mp4` 而是用户选中的 `VID20260702204124.mp4`，推流器就会因为文件不存在而无法工作！
2. **推流器此前仅提取单帧静态画面**：
   - 原推流逻辑仅调用了 `retriever.frameAtTime`，导致无论视频多长，输出画面永远定格在第 1 帧。

---

## 二、 核心重构与优化

1. **构建独立的 `getEffectiveVideoFile(context)` 解析器**：
   - 优先级 1：**管理页面选中的视频文件**（`ConfigManager.KEY_SELECTED_VIDEO`）；
   - 优先级 2：**管理页面选中的图片文件**（`ConfigManager.KEY_SELECTED_IMAGE`）；
   - 优先级 3：默认文件 `Cam.mp4`；
   - 优先级 4：自动扫描 `DCIM/Camera1/` 目录下的任意有效视频。
2. **全时长动态循环连续推流**：
   - 动态计算播放时间戳 `timeUs = (frameIndex * 33333L) % durationUs`，实现每秒 30 帧的流畅视频循环推流。
3. **支持动态切换与热重载**：
   - 推流引擎每 2 秒检测管理页面选中的视频是否变更。一旦在管理页面点击切换视频，推流引擎自动无缝重载新视频并推流。

---

## 三、 验证

- `gradlew.bat assembleDebug` 构建通过。
- `gradlew.bat :app:testDebugUnitTest` 单元测试全部通过。

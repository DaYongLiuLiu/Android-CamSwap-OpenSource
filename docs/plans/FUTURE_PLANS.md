# CamSwap 未来版本功能规划

> 本文档记录在开发过程中讨论的未来功能想法和对应的开发方案，供参考和跟进。

---

## 🗂 功能一：目标应用列表 UI

### 背景

当前版本支持通过 `KEY_TARGET_PACKAGES` 配置目标应用，但管理方式较为原始（字符串集合直接存储）。用户没有可视化的界面来选择、启用或停用目标应用。

### 目标体验

在「设置」或新增的「目标应用」页面中，展示设备上已安装的所有具有相机权限的应用，用户可以通过开关逐一启用/停用替换效果。

```
[ 微信 ]         📷 相机权限 ✓   [开关: ON]
[ 抖音 ]         📷 相机权限 ✓   [开关: ON]
[ Instagram ]    📷 相机权限 ✓   [开关: OFF]
[ 系统相机 ]     📷 相机权限 ✓   ⚠️ 系统应用
[ 计算器 ]       ✗ 无相机权限    （灰显，不建议选）
```

### 开发方案

**1. AndroidManifest.xml 增加权限声明**
```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

**2. 数据层：获取已安装应用列表并检测权限**
```kotlin
fun getInstalledCameraApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    return pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        .filter { it.packageName != context.packageName }
        .map { pkg ->
            AppInfo(
                packageName = pkg.packageName,
                appName = pkg.applicationInfo.loadLabel(pm).toString(),
                icon = pkg.applicationInfo.loadIcon(pm),
                hasCameraPermission = pm.checkPermission(
                    "android.permission.CAMERA", pkg.packageName
                ) == PackageManager.PERMISSION_GRANTED,
                isSystemApp = (pkg.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        .sortedWith(
            compareByDescending<AppInfo> { it.hasCameraPermission }
                .thenBy { it.isSystemApp }
                .thenBy { it.appName }
        )
}
```

**3. UI 层：新增 `TargetAppsScreen.kt` Composable**
- 使用 `LazyColumn` 展示应用列表
- 每项含应用图标、名称、权限标签、启用开关
- 支持搜索过滤（`TextField` + `remember { derivedStateOf {} }`）

**4. ViewModel 层**
- `loadTargetApps()` 异步加载列表（`Dispatchers.IO`）
- `toggleTargetApp(packageName: String)` 修改 `KEY_TARGET_PACKAGES` 并广播配置更新

### 影响范围

| 文件 | 改动类型 |
|---|---|
| `AndroidManifest.xml` | 新增权限 |
| `ui/TargetAppsScreen.kt` | 新建 |
| `ui/MainViewModel.kt` | 新增数据加载方法 |
| `ui/MainActivity.kt` | 新增导航路由 |

### 难度估计

⭐⭐（中低）— 无架构级改动，主要工作量在 UI 设计与数据绑定。

---

## 🎬 功能二：每个应用独立视频映射

### 背景

当前所有目标应用共用同一个 `KEY_SELECTED_VIDEO`（全局视频）。用户无法针对不同应用分配不同的替换视频（例如：微信用 video_a.mp4，抖音用 video_b.mp4）。

### 目标体验

在「目标应用」列表中，每个启用的应用旁边可展开选择一个专属视频；未设置的应用回退到全局默认视频。

### 开发方案

**1. 配置层：新增 `app_video_map` 字段**

`ConfigManager.java` 新增：
```java
public static final String KEY_APP_VIDEO_MAP = "app_video_map";

public @Nullable String getVideoForPackage(String packageName) {
    JSONObject map = configData.optJSONObject(KEY_APP_VIDEO_MAP);
    if (map != null && map.has(packageName)) {
        return map.optString(packageName, null);
    }
    return null; // 回退到全局 KEY_SELECTED_VIDEO
}

public void setVideoForPackage(String packageName, String videoName) {
    try {
        JSONObject map = configData.optJSONObject(KEY_APP_VIDEO_MAP);
        if (map == null) map = new JSONObject();
        map.put(packageName, videoName);
        configData.put(KEY_APP_VIDEO_MAP, map);
        save();
    } catch (JSONException e) { e.printStackTrace(); }
}
```

**2. Hook 层：`VideoManager` 增加按包名查询路径**

```java
public static String getVideoPathForPackage(String packageName) {
    ConfigManager config = getConfig();
    String name = config.getVideoForPackage(packageName);
    if (name == null || name.isEmpty()) {
        name = config.getString(ConfigManager.KEY_SELECTED_VIDEO, "Cam.mp4");
    }
    return ConfigManager.DEFAULT_CONFIG_DIR + name;
}
```

在 `Camera1Handler`、`Camera2Handler`、`HookMain` 中所有读取视频路径的地方，将 `VideoManager.getCurrentVideoPath()` 替换为 `VideoManager.getVideoPathForPackage(lpparam.packageName)`。

> ⚠️ 注意：`lpparam.packageName` 在 `handleLoadPackage` 作用域内可用，需在匿名内部类中捕获为 `final`。

**3. UI 层：在目标应用列表中嵌入视频选择器**

每个应用行可展开（Expandable Row）显示：
- 当前已绑定的视频名
- 「选择视频」按钮（复用现有视频选择逻辑）
- 「使用全局默认」选项

### 影响范围

| 文件 | 改动类型 |
|---|---|
| `ConfigManager.java` | 新增 2 个方法 |
| `VideoManager.java` | 新增 `getVideoPathForPackage()` |
| `Camera1Handler.java` | 修改约 3 处调用点 |
| `Camera2Handler.java` | 修改约 5 处调用点 |
| `HookMain.java` | 修改约 5 处调用点 |
| `ui/TargetAppsScreen.kt` | 视频选择 UI |
| `ui/MainViewModel.kt` | 新增绑定/解绑方法 |

### 难度估计

⭐⭐⭐（中）— 配置层和 Hook 层改动偏机械，主要需确保 `packageName` 在各 Hook 回调中正确传递；UI 部分需设计展开式交互。

---

## 📸 功能三：全面支持应用内严格拍照（动态防御机制）

### 背景

当前版本主要是"纯视频流替换"，对于严格遵守 Google 官方规范、使用 Camera1 `PictureCallback` 或 Camera2 `ImageReader` 进行单次静态拍照的 App，会出现以下问题：
- **Camera1**：由于移除了 `takePicture` hook，App 会拍到真实的物理环境照片。
- **Camera2**：直接将视频播放用的 `VirtualSurface` 丢给照片采集的 `ImageReader` 时，会导致底层由于格式错误报错、或者 App 死锁在等待照片回调上。

### 目标体验与核心架构（狸猫换太子）

不应局限于"解决报错"，而是让框架具备**动态分析与介入能力**，将防御分为两层：

1. **基础防御（应对所有人）**：在相机初始化阶段，无条件拦截并替换 `SurfaceTexture` / `Surface` 的渲染资源为虚拟视频流（已实现）。保证预览、人脸识别等基础场景的安全。

2. **动态防御（专抓苛求照片的 App）**：在 `Camera1.takePicture` 和 `ImageReader.newInstance`（特别是 JPEG 格式）处设下埋伏。平时零开销；App 一旦伸手要高清照片，立刻触发拦截，转而从视频管线提取当前帧伪造返回——即**狸猫换太子**。

**优势**：模块无需手动配置，无需猜测 App 类型，基础防御托底 + 动态防御兜底，实现自适应。

### 开发方案

**Phase 1: Camera1 `takePicture` 伏击**
1. 在 `Camera1Handler.java` 中重新 Hook `android.hardware.Camera.takePicture()`。
2. 阻止原方法执行，阻断物理相机拍照。
3. 通过 `GLVideoRenderer` 截取当前渲染帧，编码为 JPEG `byte[]`。
4. 主动调用 App 传入的 `PictureCallback.onPictureTaken(byte[], Camera)`。

**Phase 2: Camera2 `ImageReader` 伏击（ImageWriter 数据泵）**
1. Hook `ImageReader.newInstance`，保存创建出来的拍照专属 `Surface`（格式为 `JPEG` 或 `YUV_420_888`）。
2. 在 `CaptureRequest.Builder.addTarget()` 中，如果目标是受监控的 `Surface`，激活动态防御，阻止数据流到真实硬件。
3. 通过 Android 6.0+ 的 `ImageWriter.newInstance` 连接该 `Surface`，转码当前视频帧，经 `queueInputImage()` 骗进 BufferQueue，触发 App 端 `onImageAvailable`。

### 影响范围

| 文件 | 改动类型 |
|---|---|
| `HookMain.java` / `ConfigManager` | 新增「启用拍照适配」开关和状态位 |
| `Camera1Handler.java` | 恢复并重写 `takePicture` 拦截逻辑 |
| `Camera2Handler.java` | 修改 `addTarget` 的分流策略 |
| `Camera2SessionHook.java` | 集成 `ImageWriter` 数据注入泵 |
| `GLVideoRenderer.java` | 增加提取单帧（NV21 / JPEG）的方法 |

### 难度估计

⭐⭐⭐⭐⭐（极高）— 架构从静态替换升级为动态监听与图像流注入，需深入操作 Android 底层 BufferQueue 及像素级格式转换，极易遇到兼容性和多线程死锁问题。建议作为独立的大版本（如 2.0.0）进行攻坚。

---

## 📋 开发优先级建议

| 优先级 | 功能 | 原因 |
|---|---|---|
| P1 | 目标应用列表 UI | 独立功能，不依赖其他改动，用户体验提升明显 |
| P2 | 每应用独立视频 | 依赖 P1 列表 UI，可在 P1 完成后继续扩展 |
| P3 | 动态防御拍照适配 | 架构最复杂，但是对严格拍照场景的决定性突破 |

---

> 最后更新：2026-02-24

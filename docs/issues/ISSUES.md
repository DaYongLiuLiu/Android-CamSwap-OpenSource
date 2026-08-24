# CamSwap 代码问题清单

> 生成日期：2026-04-09
> 基于对全部 Java/Kotlin 源码的静态分析

---

## 目录

1. [线程安全问题](#1-线程安全问题-高严重性)
2. [资源泄漏](#2-资源泄漏-高严重性)
3. [NullPointerException 风险](#3-nullpointerexception-风险-中严重性)
4. [逻辑问题](#4-逻辑问题-中严重性)
5. [安全问题](#5-安全问题-中严重性)
6. [代码质量问题](#6-代码质量问题-低严重性)
7. [性能问题](#7-性能问题-低严重性)
8. [兼容性问题](#8-兼容性问题-低严重性)
9. [优先修复矩阵](#9-优先修复矩阵)

---

## 1. 线程安全问题（高严重性）

### 1.1 HookMain 静态字段无同步保护

**文件：** `HookMain.java:44-71`

`HookMain` 中几乎所有 Camera1/Camera2 共享状态都是裸 `public static` 字段，无任何同步保护：

```java
public static Surface mSurface;
public static SurfaceTexture mSurfacetexture;
public static Camera origin_preview_camera;
public static volatile byte[] data_buffer = { 0 };
public static int mhight;
public static int mwidth;
// ... 共计约 20 个字段
```

多个 Hook 回调运行在不同线程（Camera 框架线程、GL 渲染线程、ContentObserver 线程），并发读写这些字段会导致难以复现的竞态条件。

**建议修复：** 将相关状态封装为带 `synchronized` 访问器的状态对象，或改用 `AtomicReference` / `AtomicInteger`。

---

### 1.2 ConfigManager.configData 非线程安全

**文件：** `ConfigManager.java:73`，`:104-119`

`JSONObject configData` 被多个线程并发读写：
- Hook 回调线程调用 `getBoolean()`、`getString()` 等进行读取
- ContentObserver 线程调用 `forceReload()` / `updateConfigFromJSON()` 进行写入

`JSONObject` 本身不是线程安全类，并发操作会导致数据损坏或 `ConcurrentModificationException`。

此外，防抖逻辑中的 `lastReloadTime` 读写也存在 TOCTOU 竞态：

```java
public void reload() {
    long now = System.currentTimeMillis();
    if (now - lastReloadTime < MIN_RELOAD_INTERVAL_MS) { // 读
        return;
    }
    lastReloadTime = now; // 写（无原子性保证）
    ...
}
```

**建议修复：** 用 `AtomicReference<JSONObject>` 替换 `configData`，或对所有读写方法加 `synchronized`；防抖计数器改用 `AtomicLong` + CAS 操作。

---

### 1.3 Camera1Handler 预览帧忙等待阻塞回调线程

**文件：** `Camera1Handler.java:635-643`

```java
private static void awaitPreviewFrameBuffer() {
    for (int i = 0; i < 100 && HookMain.data_buffer == null; i++) {
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
            break;
        }
    }
}
```

此方法在 Camera 预览回调线程上执行，最长阻塞 1 秒（100 次 × 10ms）。Camera 框架对回调有时间限制，长时间阻塞会导致预览卡顿、帧丢失，甚至触发系统 ANR。

**建议修复：** 改用 `CountDownLatch` 或 `SynchronousQueue`，同时设置合理超时（如 100ms）。

---

### 1.4 MicrophoneHandler 静态字段并发访问

**文件：** `MicrophoneHandler.java:45-49`

```java
private static volatile boolean durationWarningShown = false;
private static volatile int audioPathNullLogCount = 0;
private static volatile long lastKnownPlaybackPositionMs = 0;
private static final AtomicBoolean asyncLoadingInProgress = new AtomicBoolean(false);
```

`durationWarningShown` 和 `audioPathNullLogCount` 虽然是 `volatile`，但 "检查-更新" 操作并非原子的（例如 `audioPathNullLogCount < 3` 判断后 `audioPathNullLogCount++`），在多线程音频回调中可能导致计数超出预期。

**建议修复：** 改用 `AtomicBoolean` / `AtomicInteger`。

---

## 2. 资源泄漏（高严重性）

### 2.1 ConfigManager.save() 中 FileOutputStream 未关闭

**文件：** `ConfigManager.java:393-396`

```java
private void save() {
    ...
    FileOutputStream fos = new FileOutputStream(configFile);
    fos.write(configData.toString(4).getBytes());
    fos.close(); // 若 write() 抛异常，fos 永不关闭
    ...
}
```

`write()` 发生 `IOException` 时，`fos.close()` 不会被执行，文件描述符泄漏。

**建议修复：** 改用 try-with-resources：

```java
try (FileOutputStream fos = new FileOutputStream(configFile)) {
    fos.write(configData.toString(4).getBytes(StandardCharsets.UTF_8));
}
```

---

### 2.2 VideoManager.copyToPrivateDir() 流未在 finally 中关闭

**文件：** `VideoManager.java:138-148`

```java
java.io.FileInputStream fis = new java.io.FileInputStream(pfd.getFileDescriptor());
java.io.FileOutputStream fos = new java.io.FileOutputStream(privateFile);
// 读写循环...
fos.close();
fis.close(); // 若读写中途异常，两个流都不会关闭
```

**建议修复：** 同上，使用 try-with-resources。

---

### 2.3 GLVideoRenderer.release() 存在 EGL 资源泄漏竞态

**文件：** `GLVideoRenderer.java:415-429`

```java
public void release() {
    if (mReleased) return;
    mReleased = true;
    if (mGLHandler != null) {
        mGLHandler.post(this::releaseInternal); // ← post 异步任务
    }
    if (mGLThread != null) {
        mGLThread.quitSafely(); // ← 立即退出线程
        try {
            mGLThread.join(1000);
        } catch (InterruptedException ignored) {}
    }
}
```

`quitSafely()` 会丢弃队列中尚未执行的消息，导致 `releaseInternal()` 可能从未执行，EGL Surface/Context/Display 无法释放。

**建议修复：** 改为先 post `releaseInternal`，然后用同步机制（如 `CountDownLatch`）等待其完成后再退出线程。

---

### 2.4 ExoPlayerBackend.release() 相同竞态

**文件：** `ExoPlayerBackend.java:224-233`

与 2.3 同样的问题：post `releasePlayerInternal` 后立即 `quitSafely()`，ExoPlayer 实例可能未被正确 release。

---

### 2.5 MediaPlayer 在 setupMediaPlayer 中顺序错误

**文件：** `MediaPlayerManager.java:439-449`

```java
player.prepare();                          // ← prepare 在 setSurface 之前完成
if (rendererRef[0] != null) {
    player.setSurface(rendererRef[0].getInputSurface()); // ← prepare 后才设 Surface
    ...
}
player.start();
```

`prepare()` 在 `setSurface()` 之前调用，MediaPlayer 会将解码帧输出到 null Surface，导致首帧或前几帧丢失（黑屏）。正确顺序应为：`setSurface → setDataSource → prepare → start`。

---

## 3. NullPointerException 风险（中严重性）

### 3.1 ConfigManager.configData 可能为 null

**文件：** `ConfigManager.java:295`

```java
public boolean getBoolean(String key, boolean defValue) {
    return configData.optBoolean(key, defValue); // configData 可能为 null
}
```

若 Provider 不可用、文件不存在，且 `ConfigManager(false)` 构造时跳过了 reload，`configData` 始终为 null。任何调用 `getBoolean()`/`getString()` 等的地方都会 NPE。

**建议修复：** 在类初始化时将 `configData` 初始化为 `new JSONObject()`，或在每个 getter 中加 null 检查。

---

### 3.2 Camera1Handler 的预览回调中 getParameters() 无 null 检查

**文件：** `Camera1Handler.java:592-593`

```java
HookMain.mwidth = HookMain.camera_onPreviewFrame.getParameters().getPreviewSize().width;
HookMain.mhight = HookMain.camera_onPreviewFrame.getParameters().getPreviewSize().height;
```

`getParameters()` 和 `getPreviewSize()` 均可能返回 null（相机已释放或硬件异常时），链式调用会 NPE。

---

### 3.3 Camera1Handler.buildPhotoFakeJpeg 中 data_buffer 判断逻辑

**文件：** `Camera1Handler.java:333`

```java
if (nv21 != null && nv21.length > 1) {
```

`data_buffer` 初始值为 `{ 0 }`（长度 1），此判断实际是 `> 1`，初始值不会触发 YUV→JPEG 转换。这是有意为之还是逻辑 bug（应为 `>= 1`）需确认。

---

## 4. 逻辑问题（中严重性）

### 4.1 isLinePackage 匹配过于宽泛

**文件：** `Camera2SessionHook.java:195-202`

```java
private boolean isLinePackage(String packageName) {
    String normalized = packageName.toLowerCase(Locale.ROOT);
    return normalized.equals("jp.naver.line.android")
            || normalized.startsWith("jp.naver.line.android:")
            || normalized.contains("line"); // ← 过于宽泛
}
```

`contains("line")` 会误匹配大量无关应用，例如：
- `com.airline.manager`
- `com.outline.vpn`
- `com.google.android.timeline`
- `com.cloudflare.app`（包含 "line" 吗？不含，但 "line" 的覆盖面已足够宽泛造成误伤）

这会导致这些应用意外进入 WhatsApp/LINE 的 YUV 兼容路径，造成预期外的行为。

**建议修复：** 仅保留精确包名匹配：

```java
return "jp.naver.line.android".equals(normalized)
    || normalized.startsWith("jp.naver.line.android:");
```

---

### 4.2 acquireFakeWhatsAppYuvImage 中的无效重试

**文件：** `HookMain.java:478-489`

```java
private Object acquireFakeWhatsAppYuvImage(...) throws Throwable {
    try {
        Object result = camera2Hook.acquireFakeWhatsAppYuvImage(imageReader, surface);
        drainOriginImage(originInvoker, imageReader, args);
        return result;
    } catch (Throwable ignored) {
        // 失败后再次调用相同方法
        Object fallback = camera2Hook.acquireFakeWhatsAppYuvImage(imageReader, surface);
        drainOriginImage(originInvoker, imageReader, args);
        return fallback;
    }
}
```

`catch` 块执行的操作与 `try` 块完全一致，没有任何修复手段，第二次调用大概率同样失败并抛出异常（此异常会向上传播）。这是死代码，制造了虚假的"重试"假象。

**建议修复：** 删除重试逻辑，直接 `throw` 或记录异常后返回 `null`。

---

### 4.3 updateVideoPath 中 Provider 可用性检查产生副作用

**文件：** `VideoManager.java:298-304`

```java
checkProviderAvailability();
if (providerAvailable) {
    current_video_path = "/proc/self/cmdline"; // ← 路径被设为无效值
    return;
}
```

当 Provider 可用时，`current_video_path` 被赋值为 `/proc/self/cmdline`（一个系统文件路径），用于标记"通过 Provider 获取"。若 Provider 调用失败但 `providerAvailable` 标志未及时更新，此路径会被传递给 MediaPlayer，导致播放失败。

**建议修复：** 使用专用枚举或 `null` 标记 Provider 模式，不要复用 `current_video_path` 字段。

---

### 4.4 NotificationService 缺失"上一个视频"按钮

**文件：** `NotificationService.java:110-117`

README 中明确列出通知栏支持"切换到上一个视频"功能，但 `buildNotification()` 中只有"下一个"、"旋转"、"退出"三个按钮，"上一个"按钮缺失。

---

### 4.5 Provider 可用性检查每次打开并丢弃 PFD

**文件：** `VideoManager.java:228-240`

```java
public static void checkProviderAvailability() {
    ParcelFileDescriptor pfd = getVideoPFD(); // ← 打开视频文件
    if (pfd != null) {
        providerAvailable = true;
        pfd.close(); // ← 立即关闭，文件内容被丢弃
    }
}
```

此方法被 `updateVideoPath()` 内部调用，而 `updateVideoPath()` 又在多个热路径中调用。每次检查都会产生完整的 IPC 调用（跨进程 ContentProvider），性能代价高昂。

**建议修复：** 将可用性检查结果缓存，仅在连接失败事件触发时重置缓存。

---

## 5. 安全问题（中严重性）

### 5.1 VideoProvider / ConfigReceiver 访问控制

**文件：** `app/src/main/AndroidManifest.xml:37-39`，`:41-45`

`exported="true"` 且 Manifest 中无 `android:permission` 声明，任意第三方应用均可直接访问 ContentProvider 或发送控制广播。

> **架构说明（已采用替代方案）：**
> 直接在 Manifest 添加 `signature` 级权限会导致被 hook 的第三方目标进程（相机、微信、抖音等）无法跨进程访问 Provider，破坏模块核心功能。
>
> **已实施修复：** 改为在运行时通过 **调用方 UID / 包名 + `target_packages` 白名单** 收口，而非 Manifest 声明式权限。此方案兼容当前 Xposed 跨进程架构，AndroidManifest.xml 权限声明未做修改。
>
> 遗留风险：白名单之外的应用若恶意发送广播，仍可触发配置响应。建议后续在 ConfigReceiver.onReceive() 中对非白名单 UID 的请求静默丢弃。

---

### 5.2 配置文件被设为 world-readable

**文件：** `ConfigManager.java:400-408`

```java
configFile.setReadable(true, false);
Runtime.getRuntime().exec(new String[]{"chmod", "644", configFile.getAbsolutePath()});
```

`chmod 644` 使配置文件对所有应用可读（在 root 或特殊环境下）。配置中包含流媒体地址（RTSP/RTMP URL）、视频文件路径等敏感信息。

**建议修复：** 将配置的跨进程传输完全迁移到 ContentProvider IPC，不依赖文件系统权限共享。

---

### 5.3 ConfigWatcher 注册的广播接收器无权限过滤

**文件：** `ConfigWatcher.java:119-123`

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
} else {
    context.registerReceiver(receiver, filter);
}
```

`RECEIVER_EXPORTED` 使接收器对所有应用可见，任意应用可发送 `ACTION_UPDATE_CONFIG`（带伪造的 JSON 配置）或 `ACTION_NEXT` / `ACTION_ROTATE` 广播，强制 hook 进程切换视频或更新配置。

> **注意：** 此接收器运行在**被 hook 的目标进程内**（Xposed 注入），改为 `RECEIVER_NOT_EXPORTED` 会阻止来自 CamSwap 主进程的合法广播。与 5.1 同理，需在 `onReceive()` 内通过 UID 校验发送方是否为 CamSwap 自身（`context.getPackageManager().getPackagesForUid(uid)` 验证），而非依赖 Manifest 权限。

---

## 6. 代码质量问题（低严重性）

### 6.1 变量拼写错误（typo）

**文件：** `HookMain.java:52`，`Camera1Handler.java:593` 等多处

```java
public static int mhight; // 应为 mHeight
```

`mhight` 这一拼写错误在项目中被广泛引用（搜索结果约 30 处），影响代码可读性。

---

### 6.2 resolveMethod / toArgs 大量重复

以下文件各自包含几乎完全相同的 `resolveMethod()` 和 `toArgs()` 私有方法：

- `HookMain.java:628-650`
- `Camera1Handler.java:547-565`
- `Camera2Handler.java:261-278`
- `MicrophoneHandler.java:659-686`

约 100 行重复代码，违反 DRY 原则，且修改时需同步四处。

**建议修复：** 抽取到 `HookUtils` 工具类或公共基类。

---

### 6.3 Camera2SessionHook 单文件过大

**文件：** `Camera2SessionHook.java`（~750 行）

该文件承担了 Session Hook、YUV 帧桥接、Photo Fake、虚拟 Surface 管理等多个职责，建议拆分为独立类。

---

### 6.4 字符串编码未指定

**文件：** `ConfigManager.java:395`

```java
fos.write(configData.toString(4).getBytes()); // 使用平台默认编码
```

应显式指定 `StandardCharsets.UTF_8`，避免在不同平台/设备上编码不一致导致配置解析失败。

---

## 7. 性能问题（低严重性）

### 7.1 BytePool 全局同步锁

**文件：** `BytePool.java:12,20`

```java
public static synchronized byte[] acquire(int size) { ... }
public static synchronized void release(byte[] buffer) { ... }
```

`BytePool` 在 YUV 帧处理路径上被频繁调用（约 30fps），全局 `synchronized` 会造成线程争用，在多摄像头或多路流场景下尤为明显。

**建议修复：** 改用 `ConcurrentHashMap<Integer, ConcurrentLinkedDeque<byte[]>>`，消除全局锁。

---

### 7.2 VideoManager.log() 通过反射调用 LogUtil

**文件：** `VideoManager.java:249-257`

```java
private static void log(String msg) {
    try {
        Class<?> logUtilClass = Class.forName("io.github.zensu357.camswap.utils.LogUtil");
        logUtilClass.getMethod("log", String.class).invoke(null, msg);
    } catch (Throwable e) {
        android.util.Log.i("LSPosed-Bridge", msg);
    }
}
```

每次日志调用都执行 `Class.forName` + `getMethod` + `invoke`，在热路径（如 `getVideoPFD` 每隔 5 秒一次的日志）上造成不必要的反射开销。`VideoManager` 与 `LogUtil` 在同一包中，直接调用即可。

**建议修复：**

```java
import io.github.zensu357.camswap.utils.LogUtil;
// ...
private static void log(String msg) {
    LogUtil.log(msg);
}
```

---

### 7.3 每次 getConfig() 后不缓存结果

多个调用方在热路径中连续调用 `VideoManager.getConfig()` 多次：

```java
// MicrophoneHandler.java 中同一方法内多次调用
VideoManager.getConfig().getBoolean(...)
VideoManager.getConfig().getString(...)
VideoManager.getConfig().getBoolean(...)
```

虽然 `getConfig()` 本身开销不大，但养成缓存局部变量的习惯可以避免潜在问题：

```java
ConfigManager config = VideoManager.getConfig();
config.getBoolean(...);
config.getString(...);
```

---

## 8. 兼容性问题（低严重性）

### 8.1 Runtime.exec chmod 在非 root 设备上静默失败

**文件：** `ConfigManager.java:407`

```java
Runtime.getRuntime().exec(new String[]{"chmod", "644", configFile.getAbsolutePath()});
```

此调用在非 root 设备上会失败（无输出，异常被 ignore），且返回的 `Process` 对象未被关闭，导致系统资源泄漏。

**建议修复：** 完全依赖 `configFile.setReadable(true, false)` 的 Java API，或关闭 `Process` 对象：

```java
Process p = Runtime.getRuntime().exec(...);
p.waitFor();
p.destroy();
```

---

### 8.2 ExoPlayer @UnstableApi 使用未锁版本

**文件：** `ExoPlayerBackend.java:133`

```java
@SuppressWarnings("UnstableApi")
private MediaSource buildMediaSource(MediaSourceDescriptor source) {
    ...
    RtspMediaSource.Factory factory = new RtspMediaSource.Factory();
    factory.setForceUseRtpTcp(true);
    factory.setTimeoutMs(source.timeoutMs);
    ...
}
```

`@UnstableApi` 标记的 API 在 Media3 版本更新时可能发生签名变更，当前 `media3-exoplayer:1.6.0` 与未来版本之间可能存在破坏性变更。

**建议修复：** 在 `build.gradle` 中固定 Media3 版本，或在升级前运行回归测试。

---

### 8.3 Notification.Builder 构造方式已在高版本废弃

**文件：** `NotificationService.java:103`

```java
Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
```

代码使用的 `Notification.Action.Builder(null, label, intent)` 中第一个参数（icon）传 `null`，在 Android 13+ 上部分厂商 ROM 会过滤掉没有图标的通知操作按钮，导致通知栏控制按钮不可见。

---

## 9. 优先修复矩阵

| 优先级 | 编号 | 问题描述 | 涉及文件 |
|--------|------|----------|----------|
| **P0** | 2.1 | FileOutputStream 未关闭导致 FD 泄漏 | `ConfigManager.java:393` |
| **P0** | 1.2 | ConfigManager.configData 并发读写 NPE/数据损坏 | `ConfigManager.java:73` |
| **P0** | 3.1 | configData 可能为 null 导致全局 NPE | `ConfigManager.java:295` |
| **P1** | 2.3 | GLVideoRenderer EGL 资源可能泄漏 | `GLVideoRenderer.java:415` |
| **P1** | 2.5 | MediaPlayer setSurface 顺序错误导致黑屏 | `MediaPlayerManager.java:439` |
| **P1** | 5.1 | VideoProvider/ConfigReceiver：Manifest 权限声明不适用（与 Xposed 跨进程冲突），已改为运行时 UID + 白名单收口；ConfigReceiver onReceive() 内仍需补充 UID 校验 | `ConfigReceiver.java`, `ConfigWatcher.java` |
| **P1** | 4.1 | isLinePackage 误匹配无关应用 | `Camera2SessionHook.java:195` |
| **P2** | 1.3 | 预览回调线程忙等待最长 1 秒 | `Camera1Handler.java:635` |
| **P2** | 4.2 | acquireFakeWhatsAppYuvImage 无效重试 | `HookMain.java:478` |
| **P2** | 4.3 | current_video_path 被赋值为无效路径 | `VideoManager.java:302` |
| **P2** | 5.4 | ConfigWatcher 广播接收器可被外部应用触发 | `ConfigWatcher.java:119` |
| **P3** | 6.1 | `mhight` 拼写错误 | 全局约 30 处 |
| **P3** | 6.2 | resolveMethod/toArgs 代码重复 4 份 | 多文件 |
| **P3** | 7.2 | VideoManager.log 每次通过反射调用 | `VideoManager.java:249` |
| **P3** | 8.1 | Runtime.exec Process 未关闭 | `ConfigManager.java:407` |

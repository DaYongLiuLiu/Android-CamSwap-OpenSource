package io.github.zensu357.camswap.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.zensu357.camswap.ConfigManager
import io.github.zensu357.camswap.HookGuards
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraServer 底层 Native Hook 的 Java/Kotlin 控制桥接类。
 * 负责管理底层 cameraserver / camera.provider 进程的注入、状态监测、SELinux 容错与无锁共享内存帧推流。
 * 
 * 性能优化：
 * 1. 废除 MediaMetadataRetriever 慢速截帧，采用 MediaCodec 硬件异步解码直出 YUV；
 * 2. 静态图片单次解码转码与内存复用，实现推流阶段 0 GC 抖动与 60/30 FPS 满帧推流；
 * 3. 针对 Android 12+ 提供 /data/ 与 /data/local/tmp/ 多路径部署与 SELinux 状态诊断。
 */
object CameraServerBridge {

    private const val TAG = "【CS】【CameraServerBridge】"
    private const val CAMSERVER_SO_NAME = "libcs_camserver.so"
    private const val INJECTOR_BIN_NAME = "cs-injector"
    private const val TARGET_TMP_DIR = "/data/local/tmp"

    private val isEngineRunning = AtomicBoolean(false)
    private var feederExecutor: ScheduledExecutorService? = null
    private val isFeederActive = AtomicBoolean(false)

    // 硬件解码器工作线程与状态
    private var videoDecoderThread: Thread? = null
    private val isDecoderRunning = AtomicBoolean(false)
    private val latestDecodedFrame = AtomicReference<DecodedFrame?>(null)

    // 静态图片缓存
    private var cachedStaticImageYuv: ByteArray? = null
    private var cachedStaticWidth: Int = 1280
    private var cachedStaticHeight: Int = 720
    private var cachedMediaPath: String? = null

    data class DecodedFrame(
        val yuvBytes: ByteArray,
        val width: Int,
        val height: Int,
        val format: Int = 1, // 1 = NV21
        val timestampNs: Long = System.nanoTime()
    )

    init {
        try {
            System.loadLibrary("camswap-native-hook")
        } catch (e: Throwable) {
            LogUtil.log("$TAG 加载 camswap-native-hook 动态库: ${e.message}")
        }
    }

    @JvmStatic
    private external fun nativeInitShm(): Boolean

    @JvmStatic
    private external fun nativePushFrame(
        frameBytes: ByteArray,
        width: Int,
        height: Int,
        format: Int,
        rotation: Int,
        r: Float,
        g: Float,
        b: Float,
        intensity: Float
    ): Boolean

    @JvmStatic
    private external fun nativeCloseShm()

    private var cachedRootGranted: Boolean? = null
    private val isInjecting = AtomicBoolean(false)

    /**
     * 检查并尝试主动申请系统 Root 权限（支持 Magisk、KernelSU、APatch、SuperSU 等）
     * 支持进程级缓存，避免每次切换或页面重绘重复弹窗授权。
     */
    fun requestRootPermission(forceCheck: Boolean = false): Boolean {
        if (!forceCheck && cachedRootGranted == true) {
            return true
        }
        var process: Process? = null
        return try {
            LogUtil.log("$TAG 正在向系统 Root 管理器主动发起授权握手 (su)...")
            process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes("id\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = process.inputStream.bufferedReader()
            val output = reader.readText().trim()
            val exitCode = process.waitFor()
            val granted = exitCode == 0 && (output.contains("uid=0") || output.contains("root"))
            cachedRootGranted = granted
            LogUtil.log("$TAG Root 授权响应结果: $granted (ExitCode: $exitCode, 输出: $output)")
            granted
        } catch (e: Exception) {
            LogUtil.log("$TAG 请求 Root 权限异常: ${e.message}")
            cachedRootGranted = false
            false
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {}
        }
    }

    /**
     * 获取系统 cameraserver 进程的 PID
     */
    fun getCameraServerPid(): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pidof cameraserver"))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.split(" ").firstOrNull()?.toIntOrNull() ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 检查 CameraServer / Camera Provider Native Hook 模块是否已经注入
     * 一次性批量检索所有进程的 maps，避免执行多次 su 请求
     */
    fun isHookInjected(): Boolean {
        return try {
            val checkCmd = "grep -l '$CAMSERVER_SO_NAME' /proc/[0-9]*/maps 2>/dev/null"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", checkCmd))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 快速诊断当前 CameraServer 与 HAL 状态（合并单次 su 执行）
     */
    fun logWorkingStatus(context: Context) {
        try {
            val batchCmd = buildString {
                append("echo '---STATUS_BEGIN---' ; ")
                append("echo 'SELINUX:' `getenforce 2>/dev/null` ; ")
                append("echo 'CS_PID:' `pidof cameraserver 2>/dev/null` ; ")
                append("echo 'HAL_PID:' `pidof camerahalserver 2>/dev/null || pidof vendor.qti.camera.provider-service_64 2>/dev/null || pidof android.hardware.camera.provider@2.4-service_64 2>/dev/null` ; ")
                append("echo 'INJECTED_MAPS:' `grep -l '$CAMSERVER_SO_NAME' /proc/[0-9]*/maps 2>/dev/null` ; ")
                append("echo '---STATUS_END---'")
            }
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", batchCmd))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()

            var selinux = "Unknown"
            var csPid = "未检测到"
            var halPid = "未检测到"
            var injected = false

            output.lines().forEach { line ->
                when {
                    line.startsWith("SELINUX:") -> selinux = line.removePrefix("SELINUX:").trim()
                    line.startsWith("CS_PID:") -> csPid = line.removePrefix("CS_PID:").trim().ifEmpty { "未检测到" }
                    line.startsWith("HAL_PID:") -> halPid = line.removePrefix("HAL_PID:").trim().ifEmpty { "未检测到" }
                    line.startsWith("INJECTED_MAPS:") -> injected = line.removePrefix("INJECTED_MAPS:").trim().isNotEmpty()
                }
            }

            LogUtil.log("$TAG ==================== CameraServer 工作状态 ====================")
            LogUtil.log("$TAG [进程检查] cameraserver PID: $csPid")
            LogUtil.log("$TAG [HAL 检查] camerahalserver/Provider PID: $halPid")
            LogUtil.log("$TAG [SELinux] 当前模式: $selinux (若为 Enforcing 且未注入成功，建议先在终端执行 setenforce 0)")
            LogUtil.log("$TAG [注入状态] libcs_camserver.so 注入: " + (if (injected) "已注入并生效 (HOOK ACTIVE)" else "未注入 (INACTIVE)"))
            LogUtil.log("$TAG [引擎状态] Engine Running: ${isEngineRunning.get()}, Feeder: ${isFeederActive.get()}")
            LogUtil.log("$TAG [共享内存] 路径: /data/local/tmp/cs_cam_shm")
            LogUtil.log("$TAG ==============================================================")
        } catch (e: Exception) {
            LogUtil.log("$TAG 状态获取异常: ${e.message}")
        }
    }

    /**
     * 从当前 APK 中提取指定 ABI 的 Native 共享库或二进制文件到目标路径
     */
    private fun extractNativeFromApk(context: Context, fileName: String, destFile: File): Boolean {
        val directLib = File(context.applicationInfo.nativeLibraryDir, fileName)
        if (directLib.exists() && directLib.length() > 0) {
            try {
                directLib.copyTo(destFile, overwrite = true)
                LogUtil.log("$TAG [部署] 从 nativeLibraryDir 提取成功: ${destFile.absolutePath} (${destFile.length()} 字节)")
                return true
            } catch (e: Exception) {
                LogUtil.log("$TAG [部署] 复制 nativeLibraryDir 文件失败: ${e.message}")
            }
        }

        try {
            val apkFile = File(context.applicationInfo.sourceDir)
            if (!apkFile.exists()) return false

            val zip = java.util.zip.ZipFile(apkFile)
            val supportedAbis = android.os.Build.SUPPORTED_ABIS
            var targetEntry: java.util.zip.ZipEntry? = null

            for (abi in supportedAbis) {
                val entryPath = "lib/$abi/$fileName"
                val entry = zip.getEntry(entryPath)
                if (entry != null) {
                    targetEntry = entry
                    LogUtil.log("$TAG [部署] 锁定 APK 内部库: $entryPath")
                    break
                }
            }

            if (targetEntry == null) {
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.endsWith(fileName)) {
                        targetEntry = entry
                        break
                    }
                }
            }

            if (targetEntry != null) {
                destFile.parentFile?.mkdirs()
                zip.getInputStream(targetEntry).use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                zip.close()
                LogUtil.log("$TAG [部署] 从 APK Zip 成功提取 $fileName 到 ${destFile.absolutePath} (${destFile.length()} 字节)")
                return true
            }
            zip.close()
        } catch (e: Exception) {
            LogUtil.log("$TAG [部署] 从 APK 提取 $fileName 异常: ${e.message}")
        }
        return false
    }

    /**
     * 执行底层 Native Hook 注入与推流引擎启动（单次 su 批处理执行，杜绝重复弹窗）
     */
    fun injectCameraServer(context: Context): Boolean {
        if (isInjecting.getAndSet(true)) {
            LogUtil.log("$TAG 注入任务已在执行中，忽略并发调用")
            return isEngineRunning.get()
        }

        try {
            LogUtil.log("$TAG 收到注入请求，开始执行 CameraServer 系统级注入流程...")

            val alreadyInjected = isHookInjected()
            if (alreadyInjected) {
                LogUtil.log("$TAG [提示] 系统相机进程已存在注入模块，无需重复注入")
                isEngineRunning.set(true)
                ensureShmPermissions()
                startFrameFeeder(context)
                logWorkingStatus(context)
                return true
            }

            val privateDir = File(context.filesDir, "native_bin")
            privateDir.mkdirs()

            val tempSo = File(privateDir, CAMSERVER_SO_NAME)
            val tempInjector = File(privateDir, "libcs_injector.so")

            val soExtracted = extractNativeFromApk(context, CAMSERVER_SO_NAME, tempSo)
            val injectorExtracted = extractNativeFromApk(context, "libcs_injector.so", tempInjector)

            if (!soExtracted || !injectorExtracted) {
                LogUtil.log("$TAG [错误] 无法从 APK 提取核心注入文件！soExtracted=$soExtracted, injectorExtracted=$injectorExtracted")
            }

            val targetTmpSoPath = "$TARGET_TMP_DIR/$CAMSERVER_SO_NAME"
            val targetRootSoPath = "/data/$CAMSERVER_SO_NAME"
            val targetInjectorPath = "$TARGET_TMP_DIR/$INJECTOR_BIN_NAME"

            LogUtil.log("$TAG 正在以单次 Root 会话批处理下发部署与目标进程注入...")

            // 将部署、赋权、SELinux 宽松化、9大目标进程注入合并在【单个 su 会话】中一次性执行完
            val batchScript = buildString {
                append("setenforce 0 2>/dev/null || true ; ")
                append("mkdir -p $TARGET_TMP_DIR ; ")
                append("cp -f '${tempInjector.absolutePath}' '$targetInjectorPath' ; ")
                append("cp -f '${tempSo.absolutePath}' '$targetTmpSoPath' ; ")
                append("cp -f '${tempSo.absolutePath}' '$targetRootSoPath' 2>/dev/null || true ; ")
                append("chmod 777 '$targetInjectorPath' '$targetTmpSoPath' '$targetRootSoPath' 2>/dev/null || true ; ")
                append("chcon u:object_r:system_file:s0 '$targetTmpSoPath' 2>/dev/null || true ; ")
                append("chcon u:object_r:system_file:s0 '$targetRootSoPath' 2>/dev/null || true ; ")
                append("chcon u:object_r:system_file:s0 '$targetInjectorPath' 2>/dev/null || true ; ")
                append("for tgt in cameraserver camerahalserver camerahalserver_64 vendor.mediatek.hardware.camera.isphal@1.0-service vendor.qti.camera.provider-service_64 android.hardware.camera.provider@2.4-service_64 android.hardware.camera.provider@2.5-service_64 camera.provider cammidasservice; do ")
                append("    $targetInjectorPath \$tgt $targetRootSoPath 2>&1 ; ")
                append("done ; ")
                append("chmod 777 /data/local/tmp/cs_cam_shm 2>/dev/null || true")
            }

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", batchScript))
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            LogUtil.log("$TAG [批处理注入执行完成] (ExitCode: $exitCode)\n$output")

            Thread.sleep(400)
            val success = isHookInjected()
            isEngineRunning.set(success)
            LogUtil.log("$TAG ==============================================================")
            LogUtil.log("$TAG CameraServer / Provider 最终注入结果: " + (if (success) "【成功 SUCCESS】" else "【失败 FAILED】"))
            LogUtil.log("$TAG ==============================================================")

            if (success) {
                ensureShmPermissions()
                startFrameFeeder(context)
            }
            return success
        } catch (e: Exception) {
            LogUtil.log("$TAG [异常] 注入过程发生异常: ${e.message}")
            return false
        } finally {
            isInjecting.set(false)
        }
    }

    private fun ensureShmPermissions() {
        try {
            val preFixCmd = "touch /data/local/tmp/cs_cam_shm 2>/dev/null || true ; chmod 777 /data/local/tmp/cs_cam_shm 2>/dev/null || true ; chcon u:object_r:system_file:s0 /data/local/tmp/cs_cam_shm 2>/dev/null || true"
            Runtime.getRuntime().exec(arrayOf("su", "-c", preFixCmd)).waitFor()
            nativeInitShm()
            val fixCmd = "chmod 777 /data/local/tmp/cs_cam_shm 2>/dev/null || true"
            Runtime.getRuntime().exec(arrayOf("su", "-c", fixCmd)).waitFor()
        } catch (_: Exception) {
        }
    }

    /**
     * 获取当前生效的视频/媒体文件（优先使用管理界面选中的文件）
     */
    fun getEffectiveVideoFile(context: Context): File? {
        try {
            val config = ConfigManager()
            config.setContext(context)
            config.reload()

            val dir = File(android.os.Environment.getExternalStorageDirectory(), "DCIM/Camera1")
            if (!dir.exists()) {
                dir.mkdirs()
            }

            val selectedVideo = config.getString(ConfigManager.KEY_SELECTED_VIDEO, null)
            if (!selectedVideo.isNullOrEmpty()) {
                val selectedFile = File(dir, selectedVideo)
                if (selectedFile.exists() && selectedFile.length() > 0) {
                    return selectedFile
                }
                val directFile = File(selectedVideo)
                if (directFile.exists() && directFile.length() > 0) {
                    return directFile
                }
            }

            val selectedImage = config.getString(ConfigManager.KEY_SELECTED_IMAGE, null)
            if (!selectedImage.isNullOrEmpty()) {
                val imgFile = File(dir, selectedImage)
                if (imgFile.exists() && imgFile.length() > 0) {
                    return imgFile
                }
            }

            val defaultCam = File(dir, "Cam.mp4")
            if (defaultCam.exists() && defaultCam.length() > 0) {
                return defaultCam
            }

            val files = dir.listFiles { file ->
                val name = file.name.lowercase(java.util.Locale.getDefault())
                name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".avi") || name.endsWith(".mkv")
            }
            if (!files.isNullOrEmpty()) {
                return files[0]
            }
        } catch (e: Exception) {
            LogUtil.log("$TAG 解析生效视频文件异常: ${e.message}")
        }
        return null
    }

    /**
     * 启动向 /data/local/tmp/cs_cam_shm 连续推送视频帧的后台推流器（零 GC、硬件加速）
     */
    @Synchronized
    fun startFrameFeeder(context: Context) {
        if (isFeederActive.get()) return
        isFeederActive.set(true)

        ensureShmPermissions()

        feederExecutor?.shutdownNow()
        feederExecutor = Executors.newSingleThreadScheduledExecutor()

        LogUtil.log("$TAG 启动 Root 高性能推流引擎 (30 FPS 硬件直出)...")

        feederExecutor?.scheduleAtFixedRate(object : Runnable {
            private var frameCount = 0L

            override fun run() {
                try {
                    // 每 60 帧检查一次选中的媒体文件是否发生变动
                    if (frameCount % 60 == 0L || (cachedStaticImageYuv == null && latestDecodedFrame.get() == null)) {
                        checkAndReloadMedia(context)
                    }

                    val config = ConfigManager()
                    config.setContext(context)
                    val rotation = config.getInt(ConfigManager.KEY_VIDEO_ROTATION_OFFSET, 0)

                    // 1. 如果当前是静态图片：直接推入常驻 byte[] 缓存（0 GC，0 CPU 开销）
                    val staticYuv = cachedStaticImageYuv
                    if (staticYuv != null) {
                        nativePushFrame(
                            staticYuv,
                            cachedStaticWidth,
                            cachedStaticHeight,
                            1, // NV21
                            rotation,
                            0.0f,
                            0.0f,
                            0.0f,
                            0.0f
                        )
                        frameCount++
                        return
                    }

                    // 2. 如果当前是视频：读取 MediaCodec 异步硬件解码器的最新帧
                    val videoFrame = latestDecodedFrame.get()
                    if (videoFrame != null) {
                        nativePushFrame(
                            videoFrame.yuvBytes,
                            videoFrame.width,
                            videoFrame.height,
                            videoFrame.format,
                            rotation,
                            0.0f,
                            0.0f,
                            0.0f,
                            0.0f
                        )
                    }
                    frameCount++
                } catch (_: Throwable) {
                }
            }
        }, 0, 33, TimeUnit.MILLISECONDS)
    }

    private fun checkAndReloadMedia(context: Context) {
        try {
            val targetFile = getEffectiveVideoFile(context) ?: return
            val newPath = targetFile.absolutePath
            if (newPath == cachedMediaPath) {
                return
            }
            cachedMediaPath = newPath

            val lowerName = targetFile.name.lowercase(java.util.Locale.getDefault())
            val isImg = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".bmp")

            if (isImg) {
                stopVideoDecoder()
                latestDecodedFrame.set(null)

                // 静态图片：单次解码与转码常驻
                val bmp = BitmapFactory.decodeFile(newPath)
                if (bmp != null) {
                    val w = (bmp.width / 2) * 2
                    val h = (bmp.height / 2) * 2
                    val nv21 = ByteArray(w * h * 3 / 2)
                    bitmapToNV21(bmp, w, h, nv21)
                    bmp.recycle()

                    cachedStaticWidth = w
                    cachedStaticHeight = h
                    cachedStaticImageYuv = nv21
                    LogUtil.log("$TAG [推流引擎] 静态图片加载就绪 (零 GC 缓存): ${w}x$h, 路径: $newPath")
                }
            } else {
                cachedStaticImageYuv = null
                startVideoDecoder(newPath)
            }
        } catch (e: Exception) {
            LogUtil.log("$TAG [推流引擎] 切换媒体异常: ${e.message}")
        }
    }

    /**
     * 启动基于 MediaCodec 的硬件直出 YUV 异步视频解码器
     */
    private fun startVideoDecoder(videoPath: String) {
        stopVideoDecoder()
        isDecoderRunning.set(true)

        videoDecoderThread = Thread({
            LogUtil.log("$TAG [硬解引擎] 启动 MediaCodec 异步解码流水线: $videoPath")
            while (isDecoderRunning.get()) {
                val extractor = MediaExtractor()
                var decoder: MediaCodec? = null

                try {
                    extractor.setDataSource(videoPath)
                    var trackIndex = -1
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME)
                        if (mime != null && mime.startsWith("video/")) {
                            trackIndex = i
                            break
                        }
                    }

                    if (trackIndex < 0) {
                        LogUtil.log("$TAG [硬解引擎] 未找到有效视频轨道")
                        Thread.sleep(1000)
                        continue
                    }

                    extractor.selectTrack(trackIndex)
                    val format = extractor.getTrackFormat(trackIndex)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
                    val width = format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = format.getInteger(MediaFormat.KEY_HEIGHT)

                    format.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )

                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(format, null, null, 0)
                    decoder.start()

                    val bufferInfo = MediaCodec.BufferInfo()
                    var sawInputEOS = false
                    val startTimeMs = System.currentTimeMillis()

                    val yuvCapacity = width * height * 3 / 2
                    val reusableYuvBuffer = ByteArray(yuvCapacity)

                    while (isDecoderRunning.get()) {
                        // 1. 输入数据喂送
                        if (!sawInputEOS) {
                            val inIdx = decoder.dequeueInputBuffer(10_000L)
                            if (inIdx >= 0) {
                                val inputBuf = decoder.getInputBuffer(inIdx)
                                if (inputBuf != null) {
                                    val sampleSize = extractor.readSampleData(inputBuf, 0)
                                    if (sampleSize < 0) {
                                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        sawInputEOS = true
                                    } else {
                                        val pts = extractor.sampleTime
                                        decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                        extractor.advance()
                                    }
                                }
                            }
                        }

                        // 2. 输出数据提取
                        val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                        if (outIdx >= 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decoder.releaseOutputBuffer(outIdx, false)
                                // 循环播放：重新从头开始
                                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                                decoder.flush()
                                sawInputEOS = false
                                continue
                            }

                            if (bufferInfo.size > 0) {
                                val image = decoder.getOutputImage(outIdx)
                                if (image != null) {
                                    try {
                                        val frameW = image.width
                                        val frameH = image.height
                                        val frameCapacity = frameW * frameH * 3 / 2
                                        val targetBuffer = if (reusableYuvBuffer.size == frameCapacity) {
                                            reusableYuvBuffer
                                        } else {
                                            ByteArray(frameCapacity)
                                        }

                                        imageToNv21(image, frameW, frameH, targetBuffer)

                                        // 投递解码帧（单例复用）
                                        latestDecodedFrame.set(
                                            DecodedFrame(
                                                yuvBytes = targetBuffer,
                                                width = frameW,
                                                height = frameH,
                                                format = 1,
                                                timestampNs = System.nanoTime()
                                            )
                                        )
                                    } finally {
                                        image.close()
                                    }
                                }

                                // 节奏同步（Pacing）
                                val targetTimeMs = bufferInfo.presentationTimeUs / 1000
                                val elapsed = System.currentTimeMillis() - startTimeMs
                                val sleepMs = targetTimeMs - elapsed
                                if (sleepMs > 2) {
                                    Thread.sleep(sleepMs.coerceAtMost(33))
                                }
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                        }
                    }
                } catch (e: Exception) {
                    if (isDecoderRunning.get()) {
                        LogUtil.log("$TAG [硬解引擎] 解码循环异常: ${e.message}")
                        Thread.sleep(500)
                    }
                } finally {
                    try { decoder?.stop() } catch (_: Exception) {}
                    try { decoder?.release() } catch (_: Exception) {}
                    try { extractor.release() } catch (_: Exception) {}
                }
            }
            LogUtil.log("$TAG [硬解引擎] 解码流水线已退出")
        }, "CS-NativeHWDecoder")

        videoDecoderThread?.start()
    }

    private fun stopVideoDecoder() {
        isDecoderRunning.set(false)
        videoDecoderThread?.interrupt()
        try {
            videoDecoderThread?.join(500)
        } catch (_: Exception) {}
        videoDecoderThread = null
    }

    /**
     * 将 MediaCodec 输出的 YUV_420_888 Image 转换为连续的 NV21 格式 byte[]
     */
    private fun imageToNv21(image: Image, width: Int, height: Int, outNv21: ByteArray) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer

        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride

        var pos = 0
        if (yPixelStride == 1 && yRowStride == width) {
            yBuf.get(outNv21, 0, width * height)
            pos = width * height
        } else {
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                if (yPixelStride == 1) {
                    yBuf.get(outNv21, pos, width)
                    pos += width
                } else {
                    for (col in 0 until width) {
                        outNv21[pos++] = yBuf.get(row * yRowStride + col * yPixelStride)
                    }
                }
            }
        }

        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride

        val chromaW = width / 2
        val chromaH = height / 2

        for (row in 0 until chromaH) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uRowStride
            for (col in 0 until chromaW) {
                outNv21[pos++] = vBuf.get(vRowStart + col * vPixelStride)
                outNv21[pos++] = uBuf.get(uRowStart + col * uPixelStride)
            }
        }
    }

    /**
     * 静态图片单次快速转码为 NV21 格式
     */
    private fun bitmapToNV21(bitmap: Bitmap, width: Int, height: Int, outNv21: ByteArray) {
        val scaled = if (bitmap.width != width || bitmap.height != height) {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }

        val argb = IntArray(width * height)
        scaled.getPixels(argb, 0, width, 0, 0, width, height)

        var yIndex = 0
        var uvIndex = width * height

        for (j in 0 until height) {
            val rowOffset = j * width
            for (i in 0 until width) {
                val c = argb[rowOffset + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff

                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                outNv21[yIndex++] = (if (y > 255) 255 else if (y < 0) 0 else y).toByte()

                if (j % 2 == 0 && i % 2 == 0) {
                    val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                    val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128
                    outNv21[uvIndex++] = (if (v > 255) 255 else if (v < 0) 0 else v).toByte()
                    outNv21[uvIndex++] = (if (u > 255) 255 else if (u < 0) 0 else u).toByte()
                }
            }
        }

        if (scaled != bitmap) {
            scaled.recycle()
        }
    }

    @Synchronized
    fun stopFrameFeeder() {
        if (!isFeederActive.get()) return
        isFeederActive.set(false)
        feederExecutor?.shutdownNow()
        feederExecutor = null

        stopVideoDecoder()
        latestDecodedFrame.set(null)
        cachedStaticImageYuv = null
        cachedMediaPath = null

        nativeCloseShm()
        LogUtil.log("$TAG Root 视频帧推流引擎已停止")
    }

    /**
     * 停止/重启 cameraserver 进程以重置相机状态
     */
    fun restartCameraServer(): Boolean {
        LogUtil.log("$TAG 收到重置请求，正在重启 cameraserver 进程...")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "killall cameraserver"))
            val exitCode = process.waitFor()
            val success = exitCode == 0
            LogUtil.log("$TAG 重启 cameraserver " + (if (success) "已执行 (ExitCode: $exitCode)" else "执行失败"))
            success
        } catch (e: Exception) {
            LogUtil.log("$TAG 重启 cameraserver 异常: ${e.message}")
            false
        }
    }

    fun isRunning(): Boolean = isEngineRunning.get()
}

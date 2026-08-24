package io.github.zensu357.camswap.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import io.github.zensu357.camswap.BuildConfig
import io.github.zensu357.camswap.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 稳健型诊断日志打包与导出工具
 * 架构特性：
 * 1. 模块化独立采集：单项命令失败绝不影响整体，兼容各种定制 ROM 与 Root 环境；
 * 2. 全量敏感信息脱敏：自动过滤 MAC、IP、IMEI、Android ID、密钥、WiFi 等敏感标识；
 * 3. 结构化 ZIP 分类归档：提供 summary、config、status、logs 目录与 CamSwap 核心日志快速筛选；
 * 4. SELinux 严格/宽容模式全兼容与免 Root 优雅降级全兜底。
 */
object LogExporter {

    private const val TAG = "【CS】【LogExporter】"

    // 筛选 CamSwap 关键调试日志的关键词
    private val FILTER_KEYWORDS = arrayOf(
        "【CS】", "CamSwap", "LSPosed-Bridge", "cs_camserver", "cs-injector",
        "cs_cam_shm", "virtual.mp4", "cs_config.json", "CameraServerBridge"
    )

    data class ExportResult(
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * 收集全量诊断信息并以结构化分类 ZIP 格式导出到指定 Uri
     */
    suspend fun exportLogsToUri(context: Context, targetUri: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            LogUtil.log("$TAG 开始收集与打包系统诊断日志...")
            val outputStream = context.contentResolver.openOutputStream(targetUri)
                ?: return@withContext ExportResult(false, "无法打开文件写入流 (URI无效或未获得写入授权)")

            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val configManager = ConfigManager()

            // 1. 探测 Root 权限可用性
            val hasRoot = try {
                tryExec("echo 1", useRoot = true).trim() == "1"
            } catch (_: Throwable) {
                false
            }
            LogUtil.log("$TAG Root 权限状态: $hasRoot")

            // 2. 收集 process & injection 状态
            val procContent = if (hasRoot) {
                val sb = StringBuilder()
                val selinux = tryExec("getenforce 2>/dev/null", useRoot = true).trim()
                val csPid = tryExec("pidof cameraserver 2>/dev/null", useRoot = true).trim()
                val halPid = tryExec("pidof camerahalserver 2>/dev/null", useRoot = true).trim()
                val mapsInjected = tryExec("grep -l 'libcs_camserver.so' /proc/[0-9]*/maps 2>/dev/null", useRoot = true).trim()
                val psOutput = tryExec("ps -A | grep -E 'cam|cameraserver|camerahal|zygote'", useRoot = true)

                sb.append("SELinux Status   : ").append(if (selinux.isNotEmpty()) selinux else "Unknown").append("\n")
                sb.append("CameraServer PID : ").append(if (csPid.isNotEmpty()) csPid else "-1").append("\n")
                sb.append("CameraHAL PID    : ").append(if (halPid.isNotEmpty()) halPid else "-1").append("\n")
                sb.append("Injected (maps)  : ").append(if (mapsInjected.isNotEmpty()) mapsInjected else "(none)").append("\n")
                sb.append("\n--- Process List ---\n").append(psOutput).append("\n")
                LogSanitizer.sanitize(sb.toString()) ?: ""
            } else {
                "Root permission was not granted or not available.\nProcess inspection skipped.\n"
            }

            // 3. 收集 cameraserver memory maps
            val mapsContent = if (hasRoot) {
                val cmd = "CSPID=\$(pidof cameraserver); if [ -n \"\$CSPID\" ]; then cat /proc/\$CSPID/maps 2>/dev/null | grep -E 'cs|cam|shadow|dobby|gui|mapper' ; else echo 'cameraserver not running' ; fi"
                val maps = tryExec(cmd, useRoot = true)
                LogSanitizer.sanitize(maps) ?: ""
            } else {
                "Root permission required for cameraserver memory mapping inspection.\n"
            }

            // 4. 收集 storage & residuals
            val residualScanResults = ResidualCleaner.scanResiduals(useRoot = hasRoot)
            val storageContent = buildString {
                append("--- Virtual Camera & Risk Residual Scan ---\n")
                for (item in residualScanResults) {
                    append("- [${if (item.exists) "FOUND" else "CLEAN"}] ${item.path} (${item.description})\n")
                }
                append("\n--- /data/local/tmp Directory ---\n")
                append(if (hasRoot) tryExec("ls -la /data/local/tmp 2>/dev/null", useRoot = true) else "(Root required to inspect /data/local/tmp)\n")
                append("\n--- /sdcard/DCIM/Camera1 Directory ---\n")
                append(tryExec("ls -la /sdcard/DCIM/Camera1 2>/dev/null", useRoot = hasRoot))
            }.let { LogSanitizer.sanitize(it) ?: "" }

            // 5. 收集 logcat 并过滤 CamSwap 关键日志
            val logcatRaw = if (hasRoot) {
                tryExec("logcat -d -v time -t 5000 2>/dev/null", useRoot = true)
            } else {
                tryExec("logcat -d -v time -t 2000 2>/dev/null", useRoot = false)
            }

            val sanitizedLogcatSb = StringBuilder()
            val filteredLogcatSb = StringBuilder()
            val lines = logcatRaw.lines()

            for (line in lines) {
                if (line.isEmpty()) continue
                val sanitized = LogSanitizer.sanitize(line) ?: ""
                sanitizedLogcatSb.append(sanitized).append("\n")
                if (FILTER_KEYWORDS.any { line.contains(it, ignoreCase = true) }) {
                    filteredLogcatSb.append(sanitized).append("\n")
                }
            }

            val systemLogcatContent = sanitizedLogcatSb.toString()
            val filteredLogcatContent = if (filteredLogcatSb.isNotEmpty()) {
                filteredLogcatSb.toString()
            } else {
                "(No CamSwap specific logs found in current logcat buffer)\n"
            }

            // 6. 收集 kernel dmesg
            val dmesgContent = if (hasRoot) {
                val dmesg = tryExec("dmesg -T 2>/dev/null | tail -n 200", useRoot = true)
                LogSanitizer.sanitize(dmesg) ?: ""
            } else {
                "Kernel dmesg ringbuffer requires root permission.\n"
            }

            // 7. 生成 summary.txt
            val summaryContent = buildString {
                append("======================================================================\n")
                append("Android CamSwap Runtime & Diagnostic Report (Structured ZIP)\n")
                append("Generated Time : $timeStamp\n")
                append("App Version    : ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TIME})\n")
                append("Device Model   : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
                append("CPU ABI        : ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
                append("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
                append("Fingerprint    : ${LogSanitizer.maskFingerprint(Build.FINGERPRINT)}\n")
                append("Injection Mode : ${configManager.getString(ConfigManager.KEY_INJECTION_MODE, ConfigManager.INJECTION_MODE_LSPOSED)}\n")
                append("Root Granted   : ${if (hasRoot) "true" else "false (Non-Root Diagnostics Mode)"}\n")
                append("======================================================================\n\n")
                append("Archive Manifest:\n")
                append("├── summary.txt                    (Diagnostic overview and device specs)\n")
                append("├── app_config.json                (Sanitized CamSwap active configuration)\n")
                append("├── status/\n")
                append("│   ├── process_and_injection.txt  (CameraServer PID & process injection stats)\n")
                append("│   ├── cameraserver_maps.txt      (CameraServer memory mappings)\n")
                append("│   └── storage_and_residuals.txt  (DCIM/tmp directories and risk residual scan)\n")
                append("└── logs/\n")
                append("    ├── camswap_filtered.log       (Key CamSwap runtime logs for fast debugging)\n")
                append("    ├── system_logcat.log          (Sanitized system Logcat buffer)\n")
                append("    └── kernel_dmesg.log           (Sanitized kernel dmesg ringbuffer)\n")
            }

            // 8. 导出 app_config.json
            val configJson = try {
                LogSanitizer.sanitize(configManager.exportConfig()) ?: "{}"
            } catch (e: Throwable) {
                "{ \"error\": \"Failed to export config: ${e.message}\" }"
            }

            // 9. 稳健写入 ZIP 归档（每个条目独立安全写入）
            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                writeZipEntry(zipOut, "summary.txt", summaryContent)
                writeZipEntry(zipOut, "app_config.json", configJson)
                writeZipEntry(zipOut, "status/process_and_injection.txt", procContent)
                writeZipEntry(zipOut, "status/cameraserver_maps.txt", mapsContent)
                writeZipEntry(zipOut, "status/storage_and_residuals.txt", storageContent)
                writeZipEntry(zipOut, "logs/camswap_filtered.log", filteredLogcatContent)
                writeZipEntry(zipOut, "logs/system_logcat.log", systemLogcatContent)
                writeZipEntry(zipOut, "logs/kernel_dmesg.log", dmesgContent)
            }

            LogUtil.log("$TAG 诊断日志 ZIP 包已成功导出: $targetUri (hasRoot: $hasRoot)")
            ExportResult(true)
        } catch (t: Throwable) {
            LogUtil.log("$TAG 导出日志发生异常: ${t.message}")
            ExportResult(false, t.message ?: "未知IO异常")
        }
    }

    /**
     * 安全执行 Shell 命令（非阻塞读取，自动释放资源）
     */
    private fun tryExec(command: String, useRoot: Boolean): String {
        var process: Process? = null
        return try {
            val cmdArray = if (useRoot) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
            process = Runtime.getRuntime().exec(cmdArray)
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            process.waitFor()
            output
        } catch (_: Throwable) {
            ""
        } finally {
            try { process?.destroy() } catch (_: Throwable) {}
        }
    }

    /**
     * 安全写入单个 ZIP Entry（干净写入 byte 数组并闭合 entry）
     */
    private fun writeZipEntry(zip: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zip.putNextEntry(entry)
        val bytes = content.toByteArray(Charsets.UTF_8)
        zip.write(bytes, 0, bytes.size)
        zip.closeEntry()
    }

    /**
     * 清空系统 Logcat 缓存
     */
    suspend fun clearLogs(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            LogUtil.log("$TAG 正在清空系统 Logcat 日志缓存...")
            process = try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -c"))
            } catch (_: Throwable) {
                Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
            }
            val exitCode = process.waitFor()
            val success = exitCode == 0
            LogUtil.log("$TAG 清空日志缓存: " + if (success) "【成功 SUCCESS】" else "【失败 FAILED】")
            success
        } catch (t: Throwable) {
            LogUtil.log("$TAG 清空日志发生异常: ${t.message}")
            false
        } finally {
            try { process?.destroy() } catch (_: Throwable) {}
        }
    }
}

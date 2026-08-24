package io.github.zensu357.camswap.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import io.github.zensu357.camswap.BuildConfig
import io.github.zensu357.camswap.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 通过 Root 权限收集、分类打包与导出系统及应用诊断日志 (ZIP 结构化归档)
 */
object LogExporter {

    private const val TAG = "【CS】【LogExporter】"

    // 筛选 CamSwap 关键日志的关键字
    private val FILTER_KEYWORDS = arrayOf(
        "【CS】", "CamSwap", "LSPosed-Bridge", "cs_camserver", "cs-injector",
        "cs_cam_shm", "virtual.mp4", "cs_config.json", "CameraServerBridge"
    )

    private const val MARKER_START_PROC = "===SECTION_START:PROC_INJECT==="
    private const val MARKER_END_PROC = "===SECTION_END:PROC_INJECT==="
    private const val MARKER_START_MAPS = "===SECTION_START:CSPID_MAPS==="
    private const val MARKER_END_MAPS = "===SECTION_END:CSPID_MAPS==="
    private const val MARKER_START_STORAGE = "===SECTION_START:STORAGE_TMP==="
    private const val MARKER_END_STORAGE = "===SECTION_END:STORAGE_TMP==="
    private const val MARKER_START_LOGCAT = "===SECTION_START:LOGCAT==="
    private const val MARKER_END_LOGCAT = "===SECTION_END:LOGCAT==="
    private const val MARKER_START_DMESG = "===SECTION_START:DMESG==="
    private const val MARKER_END_DMESG = "===SECTION_END:DMESG==="

    /**
     * 通过 Root 收集全量诊断信息并以分类 ZIP 格式流式导出到指定 Uri
     */
    suspend fun exportLogsToUri(context: Context, targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
        var suProcess: Process? = null
        try {
            LogUtil.log("$TAG 开始通过 Root 单次流水线会话打包分类导出诊断日志 (ZIP)...")
            val outputStream = context.contentResolver.openOutputStream(targetUri) ?: return@withContext false

            ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val configManager = ConfigManager()

                // 1. 生成 summary.txt 概况报告
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
                    append("Root Granted   : true\n")
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
                writeZipEntry(zipOut, "summary.txt", summaryContent)

                // 2. 导出脱敏后的运行时配置 app_config.json
                val configJson = try {
                    LogSanitizer.sanitize(configManager.exportConfig()) ?: "{}"
                } catch (e: Exception) {
                    "{ \"error\": \"Failed to export config: ${e.message}\" }"
                }
                writeZipEntry(zipOut, "app_config.json", configJson)

                // 3. 收集风控与目录残留扫描
                val residualScanResult = buildString {
                    append("--- Virtual Camera & Risk Residual Scan ---\n")
                    val scanResults = ResidualCleaner.scanResiduals()
                    for (item in scanResults) {
                        append("- [${if (item.exists) "FOUND" else "CLEAN"}] ${item.path} (${item.description})\n")
                    }
                    append("\n")
                }

                // 4. 启动单个 su 进程，一次性执行诊断提取流水线
                suProcess = Runtime.getRuntime().exec("su")
                val os = DataOutputStream(suProcess.outputStream)

                val script = buildString {
                    append("echo '$MARKER_START_PROC'\n")
                    append("echo -n 'CameraServer PID : '; pidof cameraserver 2>/dev/null || echo '-1'\n")
                    append("echo -n 'CameraHAL PID    : '; pidof camerahalserver 2>/dev/null || echo '-1'\n")
                    append("echo -n 'Injected (maps)  : '; grep -l 'libcs_camserver.so' /proc/[0-9]*/maps 2>/dev/null || echo '(none)'\n")
                    append("echo '--- Process List ---'\n")
                    append("ps -A | grep -E 'cam|cameraserver|camerahal|zygote'\n")
                    append("echo '$MARKER_END_PROC'\n")

                    append("echo '$MARKER_START_MAPS'\n")
                    append("CSPID=\$(pidof cameraserver)\n")
                    append("if [ -n \"\$CSPID\" ]; then cat /proc/\$CSPID/maps 2>/dev/null | grep -E 'cs|cam|shadow|dobby|gui|mapper' ; else echo 'cameraserver not running' ; fi\n")
                    append("echo '$MARKER_END_MAPS'\n")

                    append("echo '$MARKER_START_STORAGE'\n")
                    append("echo '--- /data/local/tmp directory ---'\n")
                    append("ls -la /data/local/tmp 2>/dev/null\n")
                    append("echo '--- /sdcard/DCIM/Camera1 directory ---'\n")
                    append("ls -la /sdcard/DCIM/Camera1 2>/dev/null\n")
                    append("echo '$MARKER_END_STORAGE'\n")

                    append("echo '$MARKER_START_LOGCAT'\n")
                    append("logcat -d -v time -t 5000\n")
                    append("echo '$MARKER_END_LOGCAT'\n")

                    append("echo '$MARKER_START_DMESG'\n")
                    append("dmesg -T 2>/dev/null | tail -n 200\n")
                    append("echo '$MARKER_END_DMESG'\n")
                    append("exit\n")
                }

                os.writeBytes(script)
                os.flush()

                // 流式读取并根据标记分类归档
                val reader = BufferedReader(InputStreamReader(suProcess.inputStream, Charsets.UTF_8))
                var currentSection: String? = null
                val procBuilder = StringBuilder()
                val mapsBuilder = StringBuilder()
                val storageBuilder = StringBuilder(residualScanResult)
                val logcatBuilder = StringBuilder()
                val filteredLogcatBuilder = StringBuilder()
                val dmesgBuilder = StringBuilder()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val rawLine = line ?: continue
                    when (rawLine) {
                        MARKER_START_PROC -> { currentSection = "PROC"; continue }
                        MARKER_END_PROC -> { currentSection = null; continue }
                        MARKER_START_MAPS -> { currentSection = "MAPS"; continue }
                        MARKER_END_MAPS -> { currentSection = null; continue }
                        MARKER_START_STORAGE -> { currentSection = "STORAGE"; continue }
                        MARKER_END_STORAGE -> { currentSection = null; continue }
                        MARKER_START_LOGCAT -> { currentSection = "LOGCAT"; continue }
                        MARKER_END_LOGCAT -> { currentSection = null; continue }
                        MARKER_START_DMESG -> { currentSection = "DMESG"; continue }
                        MARKER_END_DMESG -> { currentSection = null; continue }
                    }

                    val sanitized = LogSanitizer.sanitize(rawLine) ?: ""

                    when (currentSection) {
                        "PROC" -> procBuilder.append(sanitized).append("\n")
                        "MAPS" -> mapsBuilder.append(sanitized).append("\n")
                        "STORAGE" -> storageBuilder.append(sanitized).append("\n")
                        "LOGCAT" -> {
                            logcatBuilder.append(sanitized).append("\n")
                            // 检查是否包含 CamSwap 关键日志
                            if (FILTER_KEYWORDS.any { rawLine.contains(it, ignoreCase = true) }) {
                                filteredLogcatBuilder.append(sanitized).append("\n")
                            }
                        }
                        "DMESG" -> dmesgBuilder.append(sanitized).append("\n")
                    }
                }

                suProcess.waitFor()

                // 写入 status 分类文件
                writeZipEntry(zipOut, "status/process_and_injection.txt", procBuilder.toString())
                writeZipEntry(zipOut, "status/cameraserver_maps.txt", mapsBuilder.toString())
                writeZipEntry(zipOut, "status/storage_and_residuals.txt", storageBuilder.toString())

                // 写入 logs 分类文件
                if (filteredLogcatBuilder.isEmpty()) {
                    filteredLogcatBuilder.append("(No CamSwap specific logs found in recent logcat buffer)\n")
                }
                writeZipEntry(zipOut, "logs/camswap_filtered.log", filteredLogcatBuilder.toString())
                writeZipEntry(zipOut, "logs/system_logcat.log", logcatBuilder.toString())
                writeZipEntry(zipOut, "logs/kernel_dmesg.log", dmesgBuilder.toString())

                zipOut.finish()
                zipOut.flush()
            }

            LogUtil.log("$TAG 诊断日志 ZIP 包已成功导出并写入: $targetUri")
            true
        } catch (e: Exception) {
            LogUtil.log("$TAG 导出日志异常: ${e.message}")
            false
        } finally {
            try {
                suProcess?.destroy()
            } catch (_: Exception) {}
        }
    }

    /**
     * 写入一个 ZIP 压缩条目
     */
    private fun writeZipEntry(zip: ZipOutputStream, entryName: String, content: String) {
        val entry = ZipEntry(entryName)
        zip.putNextEntry(entry)
        val bytes = content.toByteArray(Charsets.UTF_8)
        zip.write(bytes, 0, bytes.size)
        zip.closeEntry()
    }

    /**
     * 通过 Root 清空系统 Logcat 缓存
     */
    suspend fun clearLogs(): Boolean = withContext(Dispatchers.IO) {
        try {
            LogUtil.log("$TAG 正在通过 Root 清空系统 Logcat 日志缓存...")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -c"))
            val exitCode = process.waitFor()
            val success = exitCode == 0
            LogUtil.log("$TAG 清空日志缓存: " + if (success) "【成功 SUCCESS】" else "【失败 FAILED】")
            success
        } catch (e: Exception) {
            LogUtil.log("$TAG 清空日志发生异常: ${e.message}")
            false
        }
    }
}

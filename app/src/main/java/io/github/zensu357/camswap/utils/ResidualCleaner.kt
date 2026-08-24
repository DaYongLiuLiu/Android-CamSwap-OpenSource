package io.github.zensu357.camswap.utils

import java.io.File

/**
 * 虚拟相机与风控残留文件扫描与清理工具类。
 * 针对市面常见虚拟相机、旧版本残留文件以及公共目录敏感配置进行扫描与一键清理。
 */
object ResidualCleaner {

    data class ScanResult(
        val path: String,
        val description: String,
        val exists: Boolean,
        val isDirectory: Boolean = false
    )

    // 常见虚拟相机及敏感路径列表
    private val KNOWN_RISK_PATHS = listOf(
        "/sdcard/DCIM/Camera1" to "CamSwap 默认配置及媒体目录",
        "/sdcard/DCIM/Camera1/cs_config.json" to "CamSwap 主配置文件",
        "/sdcard/DCIM/Camera1/virtual.mp4" to "CamSwap 默认替换视频",
        "/sdcard/DCIM/Camera/virtual.mp4" to "VCam 传统替换视频文件",
        "/sdcard/DCIM/Camera/disable.jpg" to "VCam 禁用标记文件",
        "/sdcard/DCIM/Camera/no-silent.jpg" to "VCam 静音标记文件",
        "/data/local/tmp/libcs_camserver.so" to "CameraServer 注入模块缓存",
        "/data/local/tmp/cs-injector" to "CameraServer 注入器可执行文件",
        "/data/local/tmp/cs_cam_shm" to "CamSwap 共享内存临时句柄"
    )

    /**
     * 扫描系统中的残留路径与敏感配置
     */
    fun scanResiduals(): List<ScanResult> {
        val results = mutableListOf<ScanResult>()

        for ((path, desc) in KNOWN_RISK_PATHS) {
            var exists = false
            var isDir = false

            try {
                val file = File(path)
                if (file.exists()) {
                    exists = true
                    isDir = file.isDirectory
                } else {
                    exists = checkExistsViaSu(path)
                }
            } catch (e: Exception) {
                LogUtil.log("【CS】【ResidualCleaner】扫描路径异常 $path: ${e.message}")
            }

            if (exists) {
                results.add(ScanResult(path, desc, exists = true, isDirectory = isDir))
            }
        }

        return results
    }

    /**
     * 清理单个指定路径的文件或目录
     */
    fun cleanItem(path: String): Boolean {
        return try {
            val file = File(path)
            var success = false
            if (file.exists()) {
                success = if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
            if (!success) {
                success = deleteViaSu(path)
            }
            success
        } catch (e: Exception) {
            LogUtil.log("【CS】【ResidualCleaner】清理残留失败 $path: ${e.message}")
            false
        }
    }

    /**
     * 一键清理所有已扫描出的残留
     */
    fun cleanAll(items: List<ScanResult>): Int {
        var cleanedCount = 0
        for (item in items) {
            if (cleanItem(item.path)) {
                cleanedCount++
            }
        }
        return cleanedCount
    }

    private fun checkExistsViaSu(path: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "ls '$path' >/dev/null 2>&1; echo $?"))
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output == "0"
        } catch (ignored: Exception) {
            false
        }
    }

    private fun deleteViaSu(path: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -rf '$path'"))
            process.waitFor() == 0
        } catch (ignored: Exception) {
            false
        }
    }
}

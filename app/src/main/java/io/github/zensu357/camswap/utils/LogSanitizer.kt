package io.github.zensu357.camswap.utils

import java.util.regex.Pattern

/**
 * 运行时日志脱敏器（防御式设计，严防类加载失败与任何正则异常）
 */
object LogSanitizer {

    private fun safeCompile(regex: String): Pattern? {
        return try {
            Pattern.compile(regex)
        } catch (_: Throwable) {
            null
        }
    }

    // 1. MAC 地址匹配 (短横线明确转义)
    private val MAC_PATTERN = safeCompile("(?i)\\b([0-9a-fA-F]{2}[:\\-]){5}[0-9a-fA-F]{2}\\b")

    // 2. IPv4 地址匹配 (排除 127.0.0.1 和 0.0.0.0 等通用本地地址)
    private val IPV4_PATTERN = safeCompile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b")

    // 3. IPv6 地址匹配
    private val IPV6_PATTERN = safeCompile("(?i)\\b(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}\\b")

    // 4. 显式 IMEI / MEID 匹配
    private val EXPLICIT_IMEI_PATTERN = safeCompile("(?i)\\b(imei|meid)([:=\\s]+)[0-9]{14,16}\\b")

    // 独立 15 位数字序列 (以 86 开头的常见中国移动设备 IMEI)
    private val STANDALONE_IMEI_PATTERN = safeCompile("(?<!\\d)(86\\d{2})(\\d{7})(\\d{4})(?!\\d)")

    // 5. 显式 Android ID 匹配
    private val EXPLICIT_ANDROID_ID_PATTERN = safeCompile("(?i)\\b(android_id|androidid)([:=\\s]+)[0-9a-fA-F]{16}\\b")

    // 6. 显式设备序列号匹配 (短横线明确转义)
    private val SERIAL_PATTERN = safeCompile("(?i)\\b(ro\\.boot\\.serialno|ro\\.serialno|serialno|serial_number|device_serial)([:=\\s]+)[a-zA-Z0-9_\\-]{6,32}\\b")

    // 7. WiFi SSID 和 BSSID
    private val WIFI_SSID_PATTERN = safeCompile("(?i)\\b(ssid[:=\\s]*\")[^\"]+(\")")
    private val WIFI_BSSID_PATTERN = safeCompile("(?i)\\b(bssid[:=\\s]*)([0-9a-fA-F:]{17})\\b")

    // 8. 手机号码 (中国大陆 11 位号码，前3后4保留，中间打码)
    private val PHONE_PATTERN = safeCompile("(?<!\\d)(?:(?:\\+86)|(?:86))?(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)")

    // 9. 电子邮箱 (短横线明确转义)
    private val EMAIL_PATTERN = safeCompile("\\b([A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]*@([A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})\\b")

    // 10. 鉴权 Header、Bearer Token、密码、Secret 等关键词掩码
    private val AUTH_HEADER_PATTERN = safeCompile("(?i)\\b(authorization[:=\\s]+(?:bearer\\s+)?)[^\\s,;&\"']{6,}")
    private val SECRET_PATTERN = safeCompile("(?i)\\b(password|passwd|pwd|token|secret|apikey|access_token|refresh_token)([:=\\s]+)([\"']?[^\\s,;&\"']{3,}[\"']?)")

    // 11. URL 鉴权账号密码掩码 (如 rtsp://user:password@ip:port)
    private val URL_AUTH_PATTERN = safeCompile("(?i)(https?|rtsp|rtmp|ftp)://([^:/\\s]+):([^\r\n/\\s]+)@")

    /**
     * 对单行日志或文本进行全量规则脱敏处理（全异常免疫设计）
     */
    fun sanitize(input: String?): String? {
        if (input == null) return null
        if (input.isEmpty()) return ""
        return try {
            var result: String = input

            // 1. URL 账号密码脱敏
            URL_AUTH_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1://[MASKED_USER]:[MASKED_PASS]@")
            }

            // 2. 敏感凭证及 Key 脱敏
            AUTH_HEADER_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1[MASKED_SECRET]")
            }
            SECRET_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1$2[MASKED_SECRET]")
            }

            // 3. WiFi SSID / BSSID
            WIFI_SSID_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1[MASKED_SSID]$2")
            }
            WIFI_BSSID_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1[MASKED_BSSID]")
            }

            // 4. 显式序列号与 Android ID
            SERIAL_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1$2[MASKED_SERIAL]")
            }
            EXPLICIT_ANDROID_ID_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1$2[MASKED_ANDROID_ID]")
            }

            // 5. 显式与独立 IMEI
            EXPLICIT_IMEI_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1$2[MASKED_IMEI]")
            }
            STANDALONE_IMEI_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1*******$3")
            }

            // 6. MAC 地址脱敏 (保留前2位与后2位，例如 12:34:**:**:**:ab)
            MAC_PATTERN?.let { pattern ->
                val macMatcher = pattern.matcher(result)
                if (macMatcher.find()) {
                    val sb = StringBuffer()
                    do {
                        val mac = macMatcher.group(0) ?: ""
                        val maskedMac = if (mac.length >= 17) {
                            val sep = mac[2]
                            "${mac.substring(0, 5)}${sep}**${sep}**${sep}**${sep}${mac.substring(15)}"
                        } else {
                            "[MASKED_MAC]"
                        }
                        macMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(maskedMac))
                    } while (macMatcher.find())
                    macMatcher.appendTail(sb)
                    result = sb.toString()
                }
            }

            // 7. IPv4 地址脱敏 (保留 127.0.0.1, 0.0.0.0 以及常见子网掩码 255.255.255.0)
            IPV4_PATTERN?.let { pattern ->
                val ipMatcher = pattern.matcher(result)
                if (ipMatcher.find()) {
                    val sb = StringBuffer()
                    do {
                        val ip = ipMatcher.group(0) ?: ""
                        if (ip == "127.0.0.1" || ip == "0.0.0.0" || ip == "255.255.255.255" || ip == "255.255.255.0") {
                            ipMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(ip))
                        } else {
                            val parts = ip.split(".")
                            if (parts.size == 4) {
                                val maskedIp = "${parts[0]}.${parts[1]}.*.*"
                                ipMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(maskedIp))
                            } else {
                                ipMatcher.appendReplacement(sb, "[MASKED_IP]")
                            }
                        }
                    } while (ipMatcher.find())
                    ipMatcher.appendTail(sb)
                    result = sb.toString()
                }
            }

            // 8. IPv6 地址脱敏 (保留 ::1 等)
            IPV6_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("[MASKED_IPV6]")
            }

            // 9. 手机号脱敏 (前3后4保留，如 138****5678)
            PHONE_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1****$3")
            }

            // 10. 邮箱脱敏 (如 a***@domain.com)
            EMAIL_PATTERN?.let { pattern ->
                result = pattern.matcher(result).replaceAll("$1***@$2")
            }

            result
        } catch (_: Throwable) {
            input
        }
    }

    /**
     * 对 Fingerprint 进行掩码处理（保留品牌型号与系统版本，对编译哈希标识打码）
     */
    fun maskFingerprint(fingerprint: String?): String {
        if (fingerprint.isNullOrEmpty()) return "Unknown"
        return try {
            val parts = fingerprint.split("/")
            if (parts.size >= 4) {
                val brandProductDevice = parts.subList(0, 3).joinToString("/")
                val lastTag = parts.last()
                "$brandProductDevice/[MASKED_BUILD]/$lastTag"
            } else {
                sanitize(fingerprint) ?: "Unknown"
            }
        } catch (_: Throwable) {
            fingerprint ?: "Unknown"
        }
    }
}

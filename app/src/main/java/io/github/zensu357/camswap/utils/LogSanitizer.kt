package io.github.zensu357.camswap.utils

import java.util.regex.Pattern

/**
 * 运行时日志脱敏器。
 * 用于在日志收集和导出时自动识别并模糊化/掩码设备硬件标识、网络私密地址、用户个人信息与认证凭据。
 */
object LogSanitizer {

    // 1. MAC 地址匹配 (支持冒号或短横线)
    private val MAC_PATTERN = Pattern.compile("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")

    // 2. IPv4 地址匹配 (排除 127.0.0.1 和 0.0.0.0 等通用本地地址)
    private val IPV4_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\b")

    // 3. IPv6 地址匹配
    private val IPV6_PATTERN = Pattern.compile("(?i)\\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b")

    // 4. 显式 IMEI / MEID 匹配
    private val EXPLICIT_IMEI_PATTERN = Pattern.compile("(?i)\\b(imei|meid)([:=\\s]+)[0-9]{14,16}\\b")

    // 独立 15 位数字序列 (以 86 开头的常见中国移动设备 IMEI)
    private val STANDALONE_IMEI_PATTERN = Pattern.compile("(?<!\\d)(86\\d{2})(\\d{7})(\\d{4})(?!\\d)")

    // 5. 显式 Android ID 匹配
    private val EXPLICIT_ANDROID_ID_PATTERN = Pattern.compile("(?i)\\b(android_id|androidid)([:=\\s]+)[0-9a-f]{16}\\b")

    // 6. 显式设备序列号匹配 (ro.serialno / ro.boot.serialno / serialno / serial)
    private val SERIAL_PATTERN = Pattern.compile("(?i)\\b(ro\\.boot\\.serialno|ro\\.serialno|serialno|serial_number|device_serial)([:=\\s]+)[a-zA-Z0-9_-]{6,32}\\b")

    // 7. WiFi SSID 和 BSSID
    private val WIFI_SSID_PATTERN = Pattern.compile("(?i)\\b(ssid[:=\\s]*\")[^\"]+(\")")
    private val WIFI_BSSID_PATTERN = Pattern.compile("(?i)\\b(bssid[:=\\s]*)([0-9a-fA-F:]{17})\\b")

    // 8. 手机号码 (中国大陆 11 位号码，前3后4保留，中间打码)
    private val PHONE_PATTERN = Pattern.compile("(?<!\\d)(?:(?:\\+86)|(?:86))?(1[3-9]\\d)(\\d{4})(\\d{4})(?!\\d)")

    // 9. 电子邮箱
    private val EMAIL_PATTERN = Pattern.compile("\\b([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b")

    // 10. 鉴权 Header、Bearer Token、密码、Secret 等关键词掩码
    private val AUTH_HEADER_PATTERN = Pattern.compile("(?i)\\b(authorization[:=\\s]+(?:bearer\\s+)?)[^\\s,;&\"']{6,}")
    private val SECRET_PATTERN = Pattern.compile("(?i)\\b(password|passwd|pwd|token|secret|apikey|access_token|refresh_token)([:=\\s]+)([\"']?[^\\s,;&\"']{3,}[\"']?)")

    // 11. URL 鉴权账号密码掩码 (如 rtsp://user:password@ip:port)
    private val URL_AUTH_PATTERN = Pattern.compile("(?i)(https?|rtsp|rtmp|ftp)://([^:/\\s]+):([^\r\n/\\s]+)@")

    /**
     * 对单行日志或文本进行全量规则脱敏处理
     */
    fun sanitize(input: String?): String? {
        if (input == null) return null
        if (input.isEmpty()) return ""
        var result = input

        // 1. URL 账号密码脱敏
        result = URL_AUTH_PATTERN.matcher(result).replaceAll("$1://[MASKED_USER]:[MASKED_PASS]@")

        // 2. 敏感凭证及 Key 脱敏
        result = AUTH_HEADER_PATTERN.matcher(result).replaceAll("$1[MASKED_SECRET]")
        result = SECRET_PATTERN.matcher(result).replaceAll("$1$2[MASKED_SECRET]")

        // 3. WiFi SSID / BSSID
        result = WIFI_SSID_PATTERN.matcher(result).replaceAll("$1[MASKED_SSID]$2")
        result = WIFI_BSSID_PATTERN.matcher(result).replaceAll("$1[MASKED_BSSID]")

        // 4. 显式序列号与 Android ID
        result = SERIAL_PATTERN.matcher(result).replaceAll("$1$2[MASKED_SERIAL]")
        result = EXPLICIT_ANDROID_ID_PATTERN.matcher(result).replaceAll("$1$2[MASKED_ANDROID_ID]")

        // 5. 显式与独立 IMEI
        result = EXPLICIT_IMEI_PATTERN.matcher(result).replaceAll("$1$2[MASKED_IMEI]")
        result = STANDALONE_IMEI_PATTERN.matcher(result).replaceAll("$1*******$3")

        // 6. MAC 地址脱敏 (保留前2位与后2位，例如 12:34:**:**:**:ab)
        val macMatcher = MAC_PATTERN.matcher(result)
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

        // 7. IPv4 地址脱敏 (保留 127.0.0.1, 0.0.0.0 以及常见子网掩码 255.255.255.0)
        val ipMatcher = IPV4_PATTERN.matcher(result)
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

        // 8. IPv6 地址脱敏 (保留 ::1 等)
        val ipv6Matcher = IPV6_PATTERN.matcher(result)
        if (ipv6Matcher.find()) {
            result = ipv6Matcher.replaceAll("[MASKED_IPV6]")
        }

        // 9. 手机号脱敏 (前3后4保留，如 138****5678)
        result = PHONE_PATTERN.matcher(result).replaceAll("$1****$3")

        // 10. 邮箱脱敏 (如 a***@domain.com)
        result = EMAIL_PATTERN.matcher(result).replaceAll("$1***@$2")

        return result
    }

    /**
     * 对 Fingerprint 进行掩码处理（保留品牌型号与系统版本，对编译哈希标识打码）
     */
    fun maskFingerprint(fingerprint: String?): String {
        if (fingerprint.isNullOrEmpty()) return "Unknown"
        // 例: google/taimen/taimen:11/RP1A.201005.004.A1/6763487:user/release-keys
        val parts = fingerprint.split("/")
        if (parts.size >= 4) {
            val brandProductDevice = parts.subList(0, 3).joinToString("/")
            val lastTag = parts.last()
            return "$brandProductDevice/[MASKED_BUILD]/$lastTag"
        }
        return sanitize(fingerprint) ?: "Unknown"
    }
}

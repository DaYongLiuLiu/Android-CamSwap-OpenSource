package io.github.zensu357.camswap.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun testNullAndEmptyInput() {
        assertEquals("", LogSanitizer.sanitize(""))
        assertNull(LogSanitizer.sanitize(null))
        assertEquals("normal message with no secrets", LogSanitizer.sanitize("normal message with no secrets"))
    }

    @Test
    fun testMacAddressSanitization() {
        val input1 = "Device connected with MAC: 00:1A:2B:3C:4D:5E on wlan0"
        val sanitized1 = LogSanitizer.sanitize(input1)!!
        assertFalse(sanitized1.contains("00:1A:2B:3C:4D:5E"))
        assertTrue(sanitized1.contains("00:1A:**:**:**:5E"))

        val input2 = "Bluetooth addr 12-34-56-78-9a-bc registered"
        val sanitized2 = LogSanitizer.sanitize(input2)!!
        assertFalse(sanitized2.contains("12-34-56-78-9a-bc"))
        assertTrue(sanitized2.contains("12-34-**-**-**-bc"))
    }

    @Test
    fun testIpAddressSanitization() {
        val inputLocal = "Connecting to 127.0.0.1:8080 and bind 0.0.0.0"
        val sanitizedLocal = LogSanitizer.sanitize(inputLocal)!!
        assertEquals(inputLocal, sanitizedLocal)

        val inputLan = "Host IP: 192.168.1.105, Gateway: 10.0.2.15"
        val sanitizedLan = LogSanitizer.sanitize(inputLan)!!
        assertFalse(sanitizedLan.contains("192.168.1.105"))
        assertFalse(sanitizedLan.contains("10.0.2.15"))
        assertTrue(sanitizedLan.contains("192.168.*.*"))
        assertTrue(sanitizedLan.contains("10.0.*.*"))

        val inputWan = "Remote server: 123.45.67.89 response 200"
        val sanitizedWan = LogSanitizer.sanitize(inputWan)!!
        assertTrue(sanitizedWan.contains("123.45.*.*"))
    }

    @Test
    fun testImeiSanitization() {
        val explicitImei = "Phone status: imei=867543021984562, meid: 99000123456789"
        val sanitized1 = LogSanitizer.sanitize(explicitImei)!!
        assertFalse(sanitized1.contains("867543021984562"))
        assertFalse(sanitized1.contains("99000123456789"))
        assertTrue(sanitized1.contains("imei=[MASKED_IMEI]"))
        assertTrue(sanitized1.contains("meid: [MASKED_IMEI]"))

        val standaloneImei = "Device serial_num 861234567890123 initialized"
        val sanitized2 = LogSanitizer.sanitize(standaloneImei)!!
        assertFalse(sanitized2.contains("861234567890123"))
        assertTrue(sanitized2.contains("8612*******0123"))
    }

    @Test
    fun testAndroidIdAndSerialSanitization() {
        val input = "System ro.serialno=ZY2234ABCD, ro.boot.serialno: 9876543210, android_id=9774d56d682e549c"
        val sanitized = LogSanitizer.sanitize(input)!!
        assertFalse(sanitized.contains("ZY2234ABCD"))
        assertFalse(sanitized.contains("9876543210"))
        assertFalse(sanitized.contains("9774d56d682e549c"))
        assertTrue(sanitized.contains("ro.serialno=[MASKED_SERIAL]"))
        assertTrue(sanitized.contains("ro.boot.serialno: [MASKED_SERIAL]"))
        assertTrue(sanitized.contains("android_id=[MASKED_ANDROID_ID]"))
    }

    @Test
    fun testWifiCredentialsSanitization() {
        val input = "WifiConfig: ssid=\"Home_Office_5G\", bssid=00:11:22:33:44:55, rssi=-45"
        val sanitized = LogSanitizer.sanitize(input)!!
        assertFalse(sanitized.contains("Home_Office_5G"))
        assertTrue(sanitized.contains("ssid=\"[MASKED_SSID]\""))
        assertTrue(sanitized.contains("bssid=[MASKED_BSSID]"))
    }

    @Test
    fun testPhoneAndEmailSanitization() {
        val inputPhone = "User phone number: +8613812345678 or 18900001122"
        val sanitizedPhone = LogSanitizer.sanitize(inputPhone)!!
        assertFalse(sanitizedPhone.contains("13812345678"))
        assertFalse(sanitizedPhone.contains("18900001122"))
        assertTrue(sanitizedPhone.contains("138****5678"))
        assertTrue(sanitizedPhone.contains("189****1122"))

        val inputEmail = "Contact developer at support.service@example.com or admin@test.org"
        val sanitizedEmail = LogSanitizer.sanitize(inputEmail)!!
        assertFalse(sanitizedEmail.contains("support.service@example.com"))
        assertFalse(sanitizedEmail.contains("admin@test.org"))
        assertTrue(sanitizedEmail.contains("s***@example.com"))
        assertTrue(sanitizedEmail.contains("a***@test.org"))
    }

    @Test
    fun testSecretAndTokensSanitization() {
        val input = "HTTP Header: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9, password: mySecretPassword123, token=987abc123xyz"
        val sanitized = LogSanitizer.sanitize(input)!!
        assertFalse(sanitized.contains("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"))
        assertFalse(sanitized.contains("mySecretPassword123"))
        assertFalse(sanitized.contains("987abc123xyz"))
        assertTrue(sanitized.contains("Authorization: Bearer [MASKED_SECRET]"))
        assertTrue(sanitized.contains("password: [MASKED_SECRET]"))
        assertTrue(sanitized.contains("token=[MASKED_SECRET]"))
    }

    @Test
    fun testStreamUrlAuthenticationSanitization() {
        val input = "Streaming from rtsp://admin:P@ssw0rd123@192.168.1.50:554/live and http://user1:pass2@example.com/stream.m3u8"
        val sanitized = LogSanitizer.sanitize(input)!!
        assertFalse(sanitized.contains("admin:P@ssw0rd123"))
        assertFalse(sanitized.contains("user1:pass2"))
        assertTrue(sanitized.contains("rtsp://[MASKED_USER]:[MASKED_PASS]@192.168.*.*:554/live"))
        assertTrue(sanitized.contains("http://[MASKED_USER]:[MASKED_PASS]@example.com/stream.m3u8"))
    }

    @Test
    fun testMaskFingerprint() {
        val raw = "google/taimen/taimen:11/RP1A.201005.004.A1/6763487:user/release-keys"
        val masked = LogSanitizer.maskFingerprint(raw)
        assertEquals("google/taimen/taimen:11/[MASKED_BUILD]/release-keys", masked)
    }

    @Test
    fun testZipArchiveStructure() {
        val byteOut = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(byteOut).use { zip ->
            val entries = listOf(
                "summary.txt" to "Diagnostics Summary",
                "app_config.json" to "{\"test\": true}",
                "status/process_and_injection.txt" to "cameraserver PID: 1234",
                "status/cameraserver_maps.txt" to "libcs_camserver.so",
                "status/storage_and_residuals.txt" to "/sdcard/DCIM/Camera1",
                "logs/camswap_filtered.log" to "【CS】Module Hooked",
                "logs/system_logcat.log" to "System Logcat Content",
                "logs/kernel_dmesg.log" to "Kernel Boot Log"
            )
            for ((name, content) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }

        val entriesFound = mutableMapOf<String, String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(byteOut.toByteArray())).use { zipIn ->
            var entry: java.util.zip.ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                val content = zipIn.bufferedReader(Charsets.UTF_8).readText()
                entriesFound[entry.name] = content
                entry = zipIn.nextEntry
            }
        }

        assertTrue(entriesFound.containsKey("summary.txt"))
        assertTrue(entriesFound.containsKey("app_config.json"))
        assertTrue(entriesFound.containsKey("status/process_and_injection.txt"))
        assertTrue(entriesFound.containsKey("status/cameraserver_maps.txt"))
        assertTrue(entriesFound.containsKey("status/storage_and_residuals.txt"))
        assertTrue(entriesFound.containsKey("logs/camswap_filtered.log"))
        assertTrue(entriesFound.containsKey("logs/system_logcat.log"))
        assertTrue(entriesFound.containsKey("logs/kernel_dmesg.log"))
        assertEquals("【CS】Module Hooked", entriesFound["logs/camswap_filtered.log"])
    }
}

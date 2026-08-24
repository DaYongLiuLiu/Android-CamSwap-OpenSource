package io.github.zensu357.camswap.utils

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕反光与色温检测及注入管理类。
 * 通过检测即时环境光与屏幕闪烁色温（红、绿、蓝、黄等），
 * 并通过 GLVideoRenderer 在渲染流中注入动态环境光，用于联动活体检测防穿帮。
 */
object ScreenColorDetector {

    private val isEnabled = AtomicBoolean(false)

    // 当前环境光注入 RGB 与强度 (0.0~1.0)
    @Volatile private var currentRed = 0.0f
    @Volatile private var currentGreen = 0.0f
    @Volatile private var currentBlue = 0.0f
    @Volatile private var currentIntensity = 0.0f

    // 画面扫描模式: 0-Shader动态三色注入, 1-KeyFrame定帧
    @Volatile var mode: Int = 0

    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        if (!enabled) {
            reset()
        }
    }

    fun isEnabled(): Boolean = isEnabled.get()

    fun reset() {
        currentRed = 0.0f
        currentGreen = 0.0f
        currentBlue = 0.0f
        currentIntensity = 0.0f
    }

    /**
     * 更新指定的环境光颜色与强度
     */
    fun updateAmbientColor(red: Float, green: Float, blue: Float, intensity: Float) {
        if (!isEnabled.get()) return
        currentRed = red.coerceIn(0.0f, 1.0f)
        currentGreen = green.coerceIn(0.0f, 1.0f)
        currentBlue = blue.coerceIn(0.0f, 1.0f)
        currentIntensity = intensity.coerceIn(0.0f, 1.0f)
    }

    /**
     * 触发指定预设的色光闪烁
     */
    fun triggerFlashColor(type: Int) {
        // type: 1 = 红, 2 = 绿, 3 = 蓝, 4 = 黄
        when (type) {
            1 -> updateAmbientColor(1.0f, 0.1f, 0.1f, 0.85f)
            2 -> updateAmbientColor(0.1f, 1.0f, 0.1f, 0.85f)
            3 -> updateAmbientColor(0.1f, 0.1f, 1.0f, 0.85f)
            4 -> updateAmbientColor(1.0f, 0.9f, 0.1f, 0.85f)
            else -> reset()
        }
    }

    /**
     * 获取当前的环境光 RGB 数组
     */
    fun getAmbientColor(): FloatArray = floatArrayOf(currentRed, currentGreen, currentBlue)

    /**
     * 获取当前环境光注入强度
     */
    fun getAmbientIntensity(): Float = if (isEnabled.get()) currentIntensity else 0.0f
}

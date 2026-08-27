package io.github.zensu357.camswap.utils;

import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.util.Log;

import io.github.zensu357.camswap.api101.Api101Runtime;

public class LogUtil {
    private static final String TAG = "CamSwap";

    public static void log(String message) {
        if (message == null) {
            message = "null";
        }
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {}

        try {
            if (Api101Runtime.getModule() != null) {
                Api101Runtime.getModule().log(Log.INFO, TAG, message);
            } else {
                Log.i("LSPosed-Bridge", message);
            }
        } catch (Throwable ignored) {}
    }

    public static void w(String message) {
        if (message == null) {
            message = "null";
        }
        try {
            Log.w(TAG, message);
        } catch (Throwable ignored) {}

        try {
            if (Api101Runtime.getModule() != null) {
                Api101Runtime.getModule().log(Log.WARN, TAG, message);
            } else {
                Log.w("LSPosed-Bridge", message);
            }
        } catch (Throwable ignored) {}
    }

    public static void e(String message, Throwable t) {
        if (message == null) {
            message = "null";
        }
        String fullMsg = t != null ? message + "\n堆栈信息:\n" + Log.getStackTraceString(t) : message;
        try {
            Log.e(TAG, fullMsg);
        } catch (Throwable ignored) {}

        try {
            if (Api101Runtime.getModule() != null) {
                Api101Runtime.getModule().log(Log.ERROR, TAG, fullMsg);
            } else {
                Log.e("LSPosed-Bridge", fullMsg);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 将 MediaPlayer what/extra 转换为人类可读的中文解释
     */
    public static String explainMediaPlayerError(int what, int extra) {
        String whatStr;
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                whatStr = "未知错误(1)";
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                whatStr = "媒体服务崩溃/挂死(100)";
                break;
            case MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:
                whatStr = "无法渐进式播放(200)";
                break;
            default:
                whatStr = "代码(" + what + ")";
                break;
        }

        String extraStr;
        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO:
                extraStr = "文件IO读取错误(-1004，视频文件不存在或无读权限)";
                break;
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                extraStr = "文件格式不规范/损坏(-1007)";
                break;
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                extraStr = "编解码器不支持该视频编码规格(-1010，建议转换为H.264 MP4)";
                break;
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                extraStr = "媒体操作超时(-110)";
                break;
            case -2147483648:
                extraStr = "底层底层框架未知错误(0x80000000)";
                break;
            default:
                extraStr = "附加码(" + extra + ")";
                break;
        }

        return "主要错误: " + whatStr + " | 详细原因: " + extraStr;
    }

    /**
     * 将 EGL 错误码转换为人类可读的中文解释
     */
    public static String explainEglError(int err) {
        switch (err) {
            case EGL14.EGL_SUCCESS:
                return "EGL_SUCCESS (0x3000: 操作成功)";
            case EGL14.EGL_NOT_INITIALIZED:
                return "EGL_NOT_INITIALIZED (0x3001: EGL未初始化)";
            case EGL14.EGL_BAD_ACCESS:
                return "EGL_BAD_ACCESS (0x3002: 访问权限非法)";
            case EGL14.EGL_BAD_ALLOC:
                return "EGL_BAD_ALLOC (0x3003: 显存/内存不足)";
            case EGL14.EGL_BAD_ATTRIBUTE:
                return "EGL_BAD_ATTRIBUTE (0x3004: 属性参数无效)";
            case EGL14.EGL_BAD_CONFIG:
                return "EGL_BAD_CONFIG (0x3005: EGLConfig配置无效)";
            case EGL14.EGL_BAD_CONTEXT:
                return "EGL_BAD_CONTEXT (0x3006: EGLContext上下文无效)";
            case EGL14.EGL_BAD_CURRENT_SURFACE:
                return "EGL_BAD_CURRENT_SURFACE (0x3007: 当前Surface异常)";
            case EGL14.EGL_BAD_DISPLAY:
                return "EGL_BAD_DISPLAY (0x3008: EGLDisplay显示设备无效)";
            case EGL14.EGL_BAD_MATCH:
                return "EGL_BAD_MATCH (0x3009: 参数不匹配)";
            case EGL14.EGL_BAD_NATIVE_PIXMAP:
                return "EGL_BAD_NATIVE_PIXMAP (0x300A)";
            case EGL14.EGL_BAD_NATIVE_WINDOW:
                return "EGL_BAD_NATIVE_WINDOW (0x300B: Native窗口/Surface已销毁)";
            case EGL14.EGL_BAD_PARAMETER:
                return "EGL_BAD_PARAMETER (0x300C: 参数错误)";
            case EGL14.EGL_BAD_SURFACE:
                return "EGL_BAD_SURFACE (0x300D: 目标Surface无效或已释放)";
            default:
                return "EGL错误码(0x" + Integer.toHexString(err) + ")";
        }
    }
}

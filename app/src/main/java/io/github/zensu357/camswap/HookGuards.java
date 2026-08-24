package io.github.zensu357.camswap;

import java.io.File;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

public final class HookGuards {
    private HookGuards() {
    }

    public static File resolveVideoFile(boolean forceRandom) {
        VideoManager.updateVideoPath(forceRandom);
        return getCurrentVideoFile();
    }

    public static File getCurrentVideoFile() {
        String currentPath = VideoManager.getCurrentVideoPath();
        if (currentPath == null || currentPath.isEmpty()) {
            return new File(VideoManager.video_path, VideoManager.CAM_VIDEO_NAME);
        }
        return new File(currentPath);
    }

    // ---- Legacy overload (File-based) — kept for compatibility ----

    public static boolean shouldBypass(String packageName, File videoFile) {
        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
            return true;
        }
        String injectionMode = VideoManager.getConfig().getString(ConfigManager.KEY_INJECTION_MODE, ConfigManager.INJECTION_MODE_LSPOSED);
        if (ConfigManager.INJECTION_MODE_CAMSERVER.equals(injectionMode)) {
            return true;
        }
        // Stream mode: delegate to MediaSourceDescriptor-based check
        if (VideoManager.isStreamMode()) {
            return shouldBypass(packageName, VideoManager.getCurrentMediaSource());
        }
        return shouldBypassMissingVideo(packageName, videoFile);
    }

    // ---- New overload (MediaSourceDescriptor-based) ----

    public static boolean shouldBypass(String packageName, MediaSourceDescriptor source) {
        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
            return true;
        }
        String injectionMode = VideoManager.getConfig().getString(ConfigManager.KEY_INJECTION_MODE, ConfigManager.INJECTION_MODE_LSPOSED);
        if (ConfigManager.INJECTION_MODE_CAMSERVER.equals(injectionMode)) {
            return true;
        }
        HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);

        if (source != null && source.isStream()) {
            if (source.isValid()) {
                return false;
            }
            logMissingMediaSource(packageName);
            return true;
        }

        return shouldBypassMissingVideo(packageName, (source != null && source.localPath != null) ? new File(source.localPath) : null);
    }

    public static boolean shouldBypassMissingVideo(String packageName, File videoFile) {
        if (VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_MODULE, false)) {
            return true;
        }

        // 1. Check if provider backed / available
        if (VideoManager.isUsingProviderBackedVideo() || VideoManager.isProviderAvailable()) {
            return false;
        }

        // 2. Check if private cached video exists
        if (HookMain.toast_content != null) {
            try {
                File privateVideo = new File(HookMain.toast_content.getFilesDir(), "vcam_private.mp4");
                if (privateVideo.exists() && privateVideo.isFile() && privateVideo.length() > 0) {
                    return false;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Check if local video file exists and is readable
        if (videoFile != null && videoFile.exists() && videoFile.canRead()) {
            return false;
        }

        // 4. Try querying VideoProvider PFD
        try {
            android.os.ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
            if (pfd != null) {
                pfd.close();
                return false;
            }
        } catch (Exception ignored) {
        }

        HookMain.need_to_show_toast = !VideoManager.getConfig().getBoolean(ConfigManager.KEY_DISABLE_TOAST, false);
        logMissingVideo(packageName, videoFile);
        return true;
    }

    public static void logMissingVideo(String packageName, File videoFile) {
        if (HookMain.toast_content == null || !HookMain.need_to_show_toast) {
            return;
        }

        String resolvedPackageName = packageName;
        if (resolvedPackageName == null || resolvedPackageName.isEmpty()) {
            resolvedPackageName = HookMain.toast_content.getPackageName();
        }

        try {
            LogUtil.log("【CS】不存在替换视频: " + resolvedPackageName + " 当前路径：" + getDisplayPath(videoFile));
        } catch (Exception e) {
            LogUtil.log("【CS】[toast]" + e);
        }
    }

    private static void logMissingMediaSource(String packageName) {
        if (HookMain.toast_content == null || !HookMain.need_to_show_toast) {
            return;
        }
        String resolvedPackageName = packageName;
        if (resolvedPackageName == null || resolvedPackageName.isEmpty()) {
            resolvedPackageName = HookMain.toast_content.getPackageName();
        }
        try {
            LogUtil.log("【CS】无可用媒体源: " + resolvedPackageName);
        } catch (Exception e) {
            LogUtil.log("【CS】[toast]" + e);
        }
    }

    private static String getDisplayPath(File videoFile) {
        if (videoFile != null) {
            return videoFile.getAbsolutePath();
        }
        return VideoManager.video_path;
    }
}

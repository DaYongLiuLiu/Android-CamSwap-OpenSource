package io.github.zensu357.camswap;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.github.zensu357.camswap.utils.VideoManager;

public class VideoProvider extends ContentProvider {
    private ConfigManager configManager;

    private boolean isCallerAllowed() {
        android.content.Context context = getContext();
        if (context == null) {
            return false;
        }

        int callingUid = Binder.getCallingUid();
        if (callingUid == android.os.Process.myUid()) {
            return true;
        }

        String[] packages = context.getPackageManager().getPackagesForUid(callingUid);
        if (packages == null || packages.length == 0) {
            Log.w("VideoProvider", "Rejecting call with empty package list for uid=" + callingUid);
            return false;
        }

        Set<String> targetPackages = configManager.getTargetPackages();
        if (targetPackages.isEmpty()) {
            return true;
        }

        Set<String> allowedPackages = new HashSet<>(targetPackages);
        allowedPackages.add(context.getPackageName());
        for (String pkg : packages) {
            if (allowedPackages.contains(pkg)) {
                return true;
            }
        }

        Log.w("VideoProvider", "Rejecting caller packages=" + java.util.Arrays.toString(packages));
        return false;
    }

    @Override
    public boolean onCreate() {
        // Init VideoManager config
        VideoManager.setContext(getContext());
        // Use constructor with false to avoid immediate reload via provider
        // (recursion/not ready)
        configManager = new ConfigManager(false);
        configManager.setSkipProviderReload(true);
        if (getContext() != null) {
            configManager.setContext(getContext());
        }
        // Manually reload from file now that config is set up
        configManager.reload();

        // Sync VideoManager's internal config manager too if needed,
        // but VideoManager.getConfig() creates its own instance.
        // Important: Set VideoManager context for path operations
        VideoManager.setContext(getContext());
        // Also set the provider-side ConfigManager instance into VideoManager to avoid
        // double loading
        VideoManager.setConfigManager(configManager);

        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!isCallerAllowed()) {
            return null;
        }
        String lastPathSegment = uri.getLastPathSegment();
        if (IpcContract.PATH_CONFIG.equals(lastPathSegment)) {
            configManager.reload();
            // If configData is still empty after reload, try force reload once more
            org.json.JSONObject data = configManager.getConfigData();
            if (data == null || data.length() == 0) {
                configManager.forceReload();
                data = configManager.getConfigData();
            }
            android.database.MatrixCursor cursor = new android.database.MatrixCursor(
                    new String[] { "key", "value", "type" });
            if (data != null) {
                java.util.Iterator<String> keys = data.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object value = data.opt(key);
                    String type = "string";
                    if (value instanceof Boolean)
                        type = "boolean";
                    else if (value instanceof Integer)
                        type = "int";
                    else if (value instanceof Long)
                        type = "long";
                    else if (value instanceof org.json.JSONArray)
                        type = "json_array";

                    cursor.addRow(new Object[] { key, String.valueOf(value), type });
                }
            }
            Log.d("VideoProvider", "query /config: returning " + cursor.getCount() + " rows");
            return cursor;
        }
        io.github.zensu357.camswap.utils.LogUtil
                .log("【CS】VideoProvider.query 返回 null, URI: " + uri.toString() + ", Seg: " + lastPathSegment);
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "video/mp4";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!isCallerAllowed()) {
            throw new FileNotFoundException("Caller not allowed");
        }
        configManager.reload();

        String lastSeg = uri.getLastPathSegment();

        // Handle audio file request
        if (IpcContract.PATH_AUDIO.equals(lastSeg)) {
            return openAudioFile();
        }

        // Try to start service if enabled (Lazy load when video is accessed)
        if (configManager.getBoolean(ConfigManager.KEY_NOTIFICATION_CONTROL_ENABLED, false)) {
            try {
                android.content.Context context = getContext();
                if (context != null) {
                    android.content.Intent intent = new android.content.Intent(context, NotificationService.class);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent);
                    } else {
                        context.startService(intent);
                    }
                }
            } catch (Exception e) {
                Log.w("VideoProvider", "Failed to start NotificationService: " + e.getMessage());
            }
        }

        // Random play is handled ONLY via call("random"), not on every openFile access.
        // This prevents the video from constantly switching during playback.

        // 1. Try to get the selected video name
        String videoName = configManager.getString(ConfigManager.KEY_SELECTED_VIDEO, null);

        File videoDir = new File(ConfigManager.DEFAULT_CONFIG_DIR);
        File videoFile = null;

        Log.d("VideoProvider", "openFile: selectedVideo=" + videoName + ", videoDir=" + videoDir.getAbsolutePath()
                + " exists=" + videoDir.exists());

        if (videoName != null && !videoName.isEmpty()) {
            videoFile = new File(videoDir, videoName);
            Log.d("VideoProvider", "openFile: trying " + videoFile.getAbsolutePath() + " exists=" + videoFile.exists()
                    + " canRead=" + videoFile.canRead());
        }

        // 2. If not found or invalid, fallback to cam.mp4
        if (videoFile == null || !videoFile.exists() || videoFile.isDirectory()) {
            videoFile = new File(videoDir, "Cam.mp4");
            Log.d("VideoProvider", "openFile: fallback to Cam.mp4 exists=" + videoFile.exists());
        }

        // 3. If still not found, try to find *any* mp4
        if (!videoFile.exists() || videoFile.isDirectory()) {
            File[] files = videoDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mp4"));
            Log.d("VideoProvider", "openFile: listFiles returned " + (files == null ? "null" : files.length));
            if (files != null && files.length > 0) {
                videoFile = files[0];
            }
        }

        if (videoFile == null || !videoFile.exists()) {
            Log.e("VideoProvider", "No video file found in " + videoDir.getAbsolutePath());
            throw new FileNotFoundException("No video file found in " + videoDir.getAbsolutePath());
        }

        Log.d("VideoProvider", "openFile: opening " + videoFile.getAbsolutePath()
                + " size=" + videoFile.length()
                + " canRead=" + videoFile.canRead());
        try {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(videoFile, ParcelFileDescriptor.MODE_READ_ONLY);
            Log.d("VideoProvider", "openFile: PFD opened successfully");
            return pfd;
        } catch (Exception e) {
            Log.e("VideoProvider", "openFile: PFD open FAILED: " + e.getMessage());
            throw new FileNotFoundException(
                    "Cannot open video file: " + videoFile.getAbsolutePath() + " - " + e.getMessage());
        }
    }

    /**
     * 打开音频文件并返回 PFD。
     * 查找逻辑：selected_audio → Mic.mp3 → 目录中任意音频文件。
     */
    private ParcelFileDescriptor openAudioFile() throws FileNotFoundException {
        File audioDir = new File(ConfigManager.DEFAULT_CONFIG_DIR);
        String selectedAudio = configManager.getString(ConfigManager.KEY_SELECTED_AUDIO, null);

        File audioFile = null;

        // 1. 使用配置中选中的音频
        if (selectedAudio != null && !selectedAudio.isEmpty()) {
            audioFile = new File(audioDir, selectedAudio);
            Log.d("VideoProvider", "openAudioFile: trying selected=" + audioFile.getAbsolutePath()
                    + " exists=" + audioFile.exists());
        }

        // 2. 降级到 Mic.mp3
        if (audioFile == null || !audioFile.exists()) {
            audioFile = new File(audioDir, "Mic.mp3");
            Log.d("VideoProvider", "openAudioFile: fallback to Mic.mp3 exists=" + audioFile.exists());
        }

        // 3. 扫描目录中任意音频文件
        if (!audioFile.exists()) {
            File[] files = audioDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".mp3") || lower.endsWith(".wav")
                        || lower.endsWith(".aac") || lower.endsWith(".m4a")
                        || lower.endsWith(".ogg") || lower.endsWith(".flac");
            });
            if (files != null && files.length > 0) {
                audioFile = files[0];
                Log.d("VideoProvider", "openAudioFile: found audio file=" + audioFile.getName());
            }
        }

        if (audioFile == null || !audioFile.exists()) {
            Log.e("VideoProvider", "No audio file found in " + audioDir.getAbsolutePath());
            throw new FileNotFoundException("No audio file found in " + audioDir.getAbsolutePath());
        }

        Log.d("VideoProvider", "openAudioFile: opening " + audioFile.getAbsolutePath());
        try {
            return ParcelFileDescriptor.open(audioFile, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            throw new FileNotFoundException(
                    "Cannot open audio file: " + audioFile.getAbsolutePath() + " - " + e.getMessage());
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!isCallerAllowed()) {
            Bundle denied = new Bundle();
            denied.putBoolean(IpcContract.EXTRA_CHANGED, false);
            return denied;
        }
        configManager.reload();
        boolean changed = false;

        try {
            if (IpcContract.METHOD_NEXT.equals(method)) {
                changed = switchVideo(true);
            } else if (IpcContract.METHOD_PREV.equals(method)) {
                changed = switchVideo(false);
            } else if (IpcContract.METHOD_RANDOM.equals(method)) {
                changed = pickRandomVideo();
            }

            if (changed) {
                // Notify both video and config URIs to ensure listeners are updated
                getContext().getContentResolver().notifyChange(IpcContract.URI_VIDEO, null);
                getContext().getContentResolver().notifyChange(IpcContract.URI_CONFIG, null);
            }
        } catch (Exception e) {
            Log.e("VideoProvider", "Error in call method: " + method, e);
        }

        Bundle result = new Bundle();
        result.putBoolean(IpcContract.EXTRA_CHANGED, changed);
        return result;
    }

    private boolean switchVideo(boolean next) {
        if (configManager.getBoolean(ConfigManager.KEY_ENABLE_RANDOM_PLAY, false)) {
            return pickRandomVideo();
        }

        File dir = new File(ConfigManager.DEFAULT_CONFIG_DIR);
        File[] files = VideoManager.listVideoFiles(dir);

        if (files == null || files.length == 0)
            return false;

        String selectedVideo = configManager.getString(ConfigManager.KEY_SELECTED_VIDEO, null);
        int currentIndex = -1;
        if (selectedVideo != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].getName().equals(selectedVideo)) {
                    currentIndex = i;
                    break;
                }
            }
        }

        int newIndex = (currentIndex == -1) ? 0
                : (next ? (currentIndex + 1) % files.length : (currentIndex - 1 + files.length) % files.length);

        String newVideoName = files[newIndex].getName();
        configManager.setString(ConfigManager.KEY_SELECTED_VIDEO, newVideoName);
        return true;
    }

    private boolean pickRandomVideo() {
        // Directly pick a random video and store in config.
        // Do NOT call VideoManager.updateVideoPath() to avoid IPC recursion.
        File dir = new File(ConfigManager.DEFAULT_CONFIG_DIR);
        if (!dir.exists() || !dir.isDirectory())
            return false;

        File[] files = VideoManager.listVideoFiles(dir);

        if (files == null || files.length == 0)
            return false;

        int index = java.util.concurrent.ThreadLocalRandom.current().nextInt(files.length);
        configManager.setString(ConfigManager.KEY_SELECTED_VIDEO, files[index].getName());
        return true;
    }
}

package io.github.zensu357.camswap;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.Image;
import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;

import io.github.zensu357.camswap.utils.LogUtil;
import io.github.zensu357.camswap.utils.VideoManager;

/**
 * Lightweight MediaCodec-based video decoder that outputs YUV_420_888 frames
 * directly, avoiding the expensive GL→Bitmap→RGB→YUV conversion pipeline.
 * <p>
 * Runs on its own decode thread. The latest decoded YUV frame is cached and
 * can be read lock-free from the pump thread via {@link #acquireLatestFrame}.
 */
final class MediaCodecYuvDecoder {

    private static final long TIMEOUT_US = 10_000L;

    private volatile boolean running;
    private Thread decodeThread;

    // Latest decoded frame — written by decode thread, read by pump thread.
    // Access is guarded by volatile reference swap (single writer, single reader).
    private volatile YuvFrame latestFrame;

    // Video metadata
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile int videoRotation;
    private volatile int videoFrameRate;

    /** Immutable snapshot of one decoded YUV frame. */
    static final class YuvFrame {
        final int width;
        final int height;
        final byte[] yPlane;
        final byte[] uPlane;
        final byte[] vPlane;
        final long timestampNs;

        YuvFrame(int width, int height, byte[] y, byte[] u, byte[] v, long tsNs) {
            this.width = width;
            this.height = height;
            this.yPlane = y;
            this.uPlane = u;
            this.vPlane = v;
            this.timestampNs = tsNs;
        }
    }

    /** Start decoding the current video source on a background thread. */
    void start() {
        if (running) return;
        running = true;
        latestFrame = null;
        decodeThread = new Thread(this::decodeLoop, "CS-YuvDecode");
        decodeThread.start();
    }

    /** Stop decoding and release resources. Safe to call from any thread. */
    void stop() {
        running = false;
        Thread t = decodeThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        decodeThread = null;
        latestFrame = null;
    }

    boolean isRunning() {
        return running;
    }

    /** Returns the latest decoded YUV frame, or null if none available yet. */
    YuvFrame acquireLatestFrame() {
        return latestFrame;
    }

    /** 将当前解码出的 YUV 帧合成为标准的 NV21 (Y...VU...) 字节流 */
    public byte[] acquireLatestNv21Frame() {
        YuvFrame frame = latestFrame;
        if (frame == null || frame.yPlane == null || frame.uPlane == null || frame.vPlane == null) {
            return null;
        }
        int yLen = frame.yPlane.length;
        int uLen = frame.uPlane.length;
        int vLen = frame.vPlane.length;
        byte[] nv21 = new byte[yLen + uLen + vLen];
        System.arraycopy(frame.yPlane, 0, nv21, 0, yLen);
        int uvIndex = yLen;
        for (int i = 0; i < vLen; i++) {
            nv21[uvIndex++] = frame.vPlane[i];
            if (i < uLen) {
                nv21[uvIndex++] = frame.uPlane[i];
            }
        }
        return nv21;
    }

    int getVideoWidth() { return videoWidth; }
    int getVideoHeight() { return videoHeight; }
    int getVideoRotation() { return videoRotation; }

    // -----------------------------------------------------------------------

    private void decodeLoop() {
        while (running) {
            try {
                decodeOnePass();
            } catch (Exception e) {
                if (running) {
                    LogUtil.log("【CS】YuvDecoder 异常: " + e);
                    // brief pause before retry
                    try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                }
            }
            // Loop: seek back to beginning for looped playback
        }
    }

    private void decodeOnePass() throws IOException {
        String videoPath = VideoManager.getCurrentVideoPath();
        ParcelFileDescriptor pfd = VideoManager.getVideoPFD();
        FileDescriptor fd = pfd != null ? pfd.getFileDescriptor() : null;

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            if (fd != null) {
                extractor.setDataSource(fd);
            } else if (videoPath != null) {
                extractor.setDataSource(videoPath);
            } else {
                LogUtil.log("【CS】YuvDecoder: 无可用视频源");
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                return;
            }

            int trackIndex = selectVideoTrack(extractor);
            if (trackIndex < 0) {
                LogUtil.log("【CS】YuvDecoder: 未发现视频轨道");
                return;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFrameRate = format.getInteger(MediaFormat.KEY_FRAME_RATE);
            }
            if (videoFrameRate <= 0) videoFrameRate = 30;

            // Detect video rotation
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                videoRotation = format.getInteger(MediaFormat.KEY_ROTATION);
            } else {
                videoRotation = 0;
            }

            // Configure decoder for raw buffer output (no Surface) → YUV
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            LogUtil.log("【CS】【YUV解码器】硬件解码器启动成功！(名称: " + decoder.getName()
                    + ", MIME: " + mime + ", 视频规格: " + videoWidth + "x" + videoHeight
                    + " @" + videoFrameRate + "fps, 旋转角: " + videoRotation + "°)");

            decodeFrames(decoder, extractor);

        } finally {
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            extractor.release();
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void decodeFrames(MediaCodec decoder, MediaExtractor extractor) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false;
        long startTimeMs = System.currentTimeMillis();

        // Reusable plane buffers — avoid allocation per frame
        byte[] yBuf = null, uBuf = null, vBuf = null;

        while (running) {
            // Feed input
            if (!sawInputEOS) {
                int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer inputBuf = decoder.getInputBuffer(inIdx);
                    int sampleSize = extractor.readSampleData(inputBuf, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sawInputEOS = true;
                    } else {
                        long pts = extractor.getSampleTime();
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                        extractor.advance();
                    }
                }
            }

            // Read output
            int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    decoder.releaseOutputBuffer(outIdx, false);
                    // End of video — seek back for loop
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    decoder.flush();
                    sawInputEOS = false;
                    startTimeMs = System.currentTimeMillis();
                    continue;
                }

                if (info.size > 0) {
                    Image image = decoder.getOutputImage(outIdx);
                    if (image != null) {
                        try {
                            int w = image.getWidth();
                            int h = image.getHeight();
                            int yLen = w * h;
                            int cLen = (w / 2) * (h / 2);

                            // Allocate/reuse buffers
                            if (yBuf == null || yBuf.length != yLen) {
                                yBuf = new byte[yLen];
                                uBuf = new byte[cLen];
                                vBuf = new byte[cLen];
                            }

                            extractYuvPlanes(image, w, h, yBuf, uBuf, vBuf);

                            // Publish frame (copy for immutability)
                            byte[] yOut = new byte[yLen];
                            byte[] uOut = new byte[cLen];
                            byte[] vOut = new byte[cLen];
                            System.arraycopy(yBuf, 0, yOut, 0, yLen);
                            System.arraycopy(uBuf, 0, uOut, 0, cLen);
                            System.arraycopy(vBuf, 0, vOut, 0, cLen);
                            latestFrame = new YuvFrame(w, h, yOut, uOut, vOut, System.nanoTime());
                        } finally {
                            image.close();
                        }
                    }

                    // Pace output to video frame rate
                    long targetTimeMs = info.presentationTimeUs / 1000;
                    long elapsed = System.currentTimeMillis() - startTimeMs;
                    long sleepMs = targetTimeMs - elapsed;
                    if (sleepMs > 2) {
                        try { Thread.sleep(sleepMs); } catch (InterruptedException e) { break; }
                    }
                }
                decoder.releaseOutputBuffer(outIdx, false);
            }
        }
    }

    /**
     * Extract Y, U, V planes from a decoder output Image into flat byte arrays.
     * Handles varying row strides and pixel strides.
     */
    private static void extractYuvPlanes(Image image, int w, int h,
                                          byte[] yOut, byte[] uOut, byte[] vOut) {
        Image.Plane[] planes = image.getPlanes();
        // Y plane
        ByteBuffer yBuf = planes[0].getBuffer();
        int yRowStride = planes[0].getRowStride();
        int yPixelStride = planes[0].getPixelStride();

        if (yPixelStride == 1 && yRowStride == w) {
            yBuf.get(yOut, 0, w * h);
        } else {
            for (int row = 0; row < h; row++) {
                yBuf.position(row * yRowStride);
                if (yPixelStride == 1) {
                    yBuf.get(yOut, row * w, w);
                } else {
                    int base = row * w;
                    for (int col = 0; col < w; col++) {
                        yOut[base + col] = yBuf.get(row * yRowStride + col * yPixelStride);
                    }
                }
            }
        }

        // U and V planes
        int cw = w / 2;
        int ch = h / 2;
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int uRowStride = planes[1].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vRowStride = planes[2].getRowStride();
        int vPixelStride = planes[2].getPixelStride();

        if (uPixelStride == 1 && uRowStride == cw
                && vPixelStride == 1 && vRowStride == cw) {
            // Ideal: packed planar
            uBuf.get(uOut, 0, cw * ch);
            vBuf.get(vOut, 0, cw * ch);
        } else if (uPixelStride == 2 && vPixelStride == 2) {
            // Common NV12/NV21 interleaved UV: pixel stride 2
            for (int row = 0; row < ch; row++) {
                int srcBase = row * uRowStride;
                int dstBase = row * cw;
                for (int col = 0; col < cw; col++) {
                    uOut[dstBase + col] = uBuf.get(srcBase + col * 2);
                    vOut[dstBase + col] = vBuf.get(srcBase + col * 2);
                }
            }
        } else {
            // General case
            for (int row = 0; row < ch; row++) {
                int dstBase = row * cw;
                for (int col = 0; col < cw; col++) {
                    uOut[dstBase + col] = uBuf.get(row * uRowStride + col * uPixelStride);
                    vOut[dstBase + col] = vBuf.get(row * vRowStride + col * vPixelStride);
                }
            }
        }
    }

    private static int selectVideoTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }
}

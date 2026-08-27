package io.github.zensu357.camswap;

import android.graphics.SurfaceTexture;
import android.hardware.Camera;

import java.lang.reflect.Method;
import java.util.Locale;

import io.github.zensu357.camswap.api101.Api101Runtime;
import io.github.zensu357.camswap.utils.HookUtils;
import io.github.zensu357.camswap.utils.LogUtil;

/**
 * LINE 客户端 (jp.naver.line.android) 专有 Elsa AR / Yuki 相机引擎与 CardScanner 拦截处理器。
 * 支持在保留 LINE 自身 3D 萌趣贴纸、美颜滤镜的同时注入虚拟画面 (方案 B)，
 * 并对向下兼容的 Camera1 模式和银行卡扫描提供双重 NV21 帧覆写兜底。
 */
public class LineElsaHandler implements ICameraHandler {

    @Override
    public void init(final Api101PackageContext packageContext) {
        final String packageName = packageContext.hostPackageName;
        if (!isLinePackage(packageName)) {
            return;
        }

        final ClassLoader classLoader = packageContext.classLoader;
        LogUtil.log("【CS】检测到 LINE 客户端，正在挂载 Elsa / CardScanner 专有拦截器...");

        hookElsaSurfaceTextureBinding(classLoader);
        hookElsaCamera1Callback(classLoader);
        hookCardScannerCallback(classLoader);
        hookElsaImageToNv21Converter(classLoader);
        hookElsaCameraXImageAnalysis(classLoader);
        hookElsaCameraXSurfaceProvider(classLoader);
    }

    public static boolean isLinePackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        String normalized = packageName.toLowerCase(Locale.ROOT);
        return normalized.equals("jp.naver.line.android")
                || normalized.startsWith("jp.naver.line.android:");
    }

    /**
     * 方案 B：拦截 Elsa 引擎的 SurfaceTexture 绑定入口，将虚拟视频纹理挂载至 Elsa 渲染管线。
     */
    private void hookElsaSurfaceTextureBinding(ClassLoader classLoader) {
        try {
            Class<?> kClass = Class.forName("com.linecorp.elsa.camera.k", false, classLoader);
            for (Method method : kClass.getDeclaredMethods()) {
                if (method.getName().equals("h") && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0] == SurfaceTexture.class) {
                    method.setAccessible(true);
                    Api101Runtime.requireModule().hook(method).intercept(chain -> {
                        Object[] args = HookUtils.toArgs(chain.getArgs());
                        try {
                            if (args.length > 0 && args[0] instanceof SurfaceTexture) {
                                SurfaceTexture st = (SurfaceTexture) args[0];
                                LogUtil.log("【CS】【LINE Elsa】SurfaceTexture 注入拦截: " + st);
                            }
                        } catch (Throwable t) {
                            LogUtil.e("【CS】【LINE Elsa】Elsa.h hook 异常: " + t.getMessage(), t);
                        }
                        return chain.proceed(args);
                    });
                    LogUtil.log("【CS】【LINE Elsa】已成功挂载 Elsa.h (SurfaceTexture) Hook");
                    break;
                }
            }
        } catch (Throwable t) {
            LogUtil.log("【CS】【LINE Elsa】挂载 Elsa.k 失败 (可能类名混淆或非活跃类): " + t);
        }
    }

    /**
     * 方案 C 兜底：拦截 Elsa Camera1 模式帧回调 com.linecorp.elsa.camera.g$e
     */
    private void hookElsaCamera1Callback(ClassLoader classLoader) {
        try {
            Class<?> geClass = Class.forName("com.linecorp.elsa.camera.g$e", false, classLoader);
            for (Method method : geClass.getDeclaredMethods()) {
                if (method.getName().equals("onPreviewFrame")) {
                    method.setAccessible(true);
                    Api101Runtime.requireModule().hook(method).intercept(chain -> {
                        Object[] args = HookUtils.toArgs(chain.getArgs());
                        try {
                            if (args.length >= 2 && args[0] instanceof byte[] && HookMain.data_buffer != null) {
                                byte[] buffer = (byte[]) args[0];
                                System.arraycopy(HookMain.data_buffer, 0, buffer, 0,
                                        Math.min(HookMain.data_buffer.length, buffer.length));
                            }
                        } catch (Throwable t) {
                            LogUtil.e("【CS】【LINE Elsa】g$e onPreviewFrame 异常: " + t.getMessage(), t);
                        }
                        return chain.proceed(args);
                    });
                    LogUtil.log("【CS】【LINE Elsa】已成功挂载 Elsa g$e onPreviewFrame Hook");
                    break;
                }
            }
        } catch (Throwable t) {
            LogUtil.log("【CS】【LINE Elsa】挂载 Elsa g$e 失败: " + t);
        }
    }

    /**
     * 针对 LINE Pay 银行卡识别模块 io.card.payment.CardScanner 的帧覆写
     */
    private void hookCardScannerCallback(ClassLoader classLoader) {
        try {
            Class<?> cardScannerClass = Class.forName("io.card.payment.CardScanner", false, classLoader);
            for (Method method : cardScannerClass.getDeclaredMethods()) {
                if (method.getName().equals("onPreviewFrame")) {
                    method.setAccessible(true);
                    Api101Runtime.requireModule().hook(method).intercept(chain -> {
                        Object[] args = HookUtils.toArgs(chain.getArgs());
                        try {
                            if (args.length >= 2 && args[0] instanceof byte[] && HookMain.data_buffer != null) {
                                byte[] buffer = (byte[]) args[0];
                                System.arraycopy(HookMain.data_buffer, 0, buffer, 0,
                                        Math.min(HookMain.data_buffer.length, buffer.length));
                            }
                        } catch (Throwable t) {
                            LogUtil.e("【CS】【LINE Pay】CardScanner onPreviewFrame 异常: " + t.getMessage(), t);
                        }
                        return chain.proceed(args);
                    });
                    LogUtil.log("【CS】【LINE Pay】已成功挂载 CardScanner onPreviewFrame Hook");
                    break;
                }
            }
        } catch (Throwable t) {
            LogUtil.log("【CS】【LINE Pay】挂载 CardScanner 失败 (可能当前未加载该模块): " + t);
        }
    }

    /**
     * 方案 D：拦截 Camera2 模式下 Elsa 引擎的核心 Image -> byte[] (NV21) 转换器 com.linecorp.elsa.camera.b
     */
    private void hookElsaImageToNv21Converter(ClassLoader classLoader) {
        try {
            Class<?> bClass = Class.forName("com.linecorp.elsa.camera.b", false, classLoader);
            for (Method method : bClass.getDeclaredMethods()) {
                boolean isMatch = method.getReturnType() == byte[].class
                        && ((method.getParameterTypes().length >= 1 && method.getParameterTypes()[0] == android.media.Image.class)
                            || (method.getParameterTypes().length >= 2 && method.getParameterTypes()[1] == android.media.Image.class)
                            || method.getName().equals("v"));
                if (isMatch) {
                    method.setAccessible(true);
                    Api101Runtime.requireModule().hook(method).intercept(chain -> {
                        Object[] args = HookUtils.toArgs(chain.getArgs());
                        byte[] fakeNv21 = getLatestNv21Data();
                        if (fakeNv21 != null && fakeNv21.length > 0) {
                            LogUtil.log("【CS】【LINE Elsa】成功劫持 b.v (Image->NV21) 转换，返回虚拟视频帧 (" + fakeNv21.length + " bytes)");
                            return fakeNv21;
                        }
                        return chain.proceed(args);
                    });
                    LogUtil.log("【CS】【LINE Elsa】已成功挂载 Elsa.b (" + method.getName() + ") Image->NV21 转换器 Hook");
                }
            }
        } catch (Throwable t) {
            LogUtil.log("【CS】【LINE Elsa】挂载 Elsa.b 转换器失败: " + t);
        }
    }

    private byte[] getLatestNv21Data() {
        try {
            if (HookMain.camera2Hook != null && HookMain.camera2Hook.yuvDecoder != null) {
                byte[] nv21 = HookMain.camera2Hook.yuvDecoder.acquireLatestNv21Frame();
                if (nv21 != null && nv21.length > 0) {
                    return nv21;
                }
            }
        } catch (Throwable ignored) {
        }
        return HookMain.data_buffer;
    }

    /**
     * 第一顺位：拦截 CameraX ImageAnalysis.Analyzer (com.linecorp.elsa.camera.c$a 等)
     */
    private void hookElsaCameraXImageAnalysis(ClassLoader classLoader) {
        String[] targetClasses = new String[] {
            "com.linecorp.elsa.camera.c$a",
            "com.linecorp.elsa.camera.i$a",
            "com.linecorp.elsa.camera.c",
            "com.linecorp.elsa.camera.i"
        };
        for (String className : targetClasses) {
            try {
                Class<?> clazz = Class.forName(className, false, classLoader);
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getName().equals("analyze") && method.getParameterTypes().length == 1) {
                        method.setAccessible(true);
                        Api101Runtime.requireModule().hook(method).intercept(chain -> {
                            Object[] args = HookUtils.toArgs(chain.getArgs());
                            try {
                                Object imageProxy = args[0];
                                if (imageProxy != null) {
                                    overrideImageProxyPlanes(imageProxy);
                                }
                            } catch (Throwable t) {
                                LogUtil.log("【CS】【LINE Elsa】覆写 ImageProxy 异常: " + t);
                            }
                            return chain.proceed(args);
                        });
                        LogUtil.log("【CS】【LINE Elsa】已成功挂载 CameraX ImageAnalysis.Analyzer (" + className + "::analyze) Hook");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void overrideImageProxyPlanes(Object imageProxy) {
        if (imageProxy == null) return;
        try {
            MediaCodecYuvDecoder.YuvFrame yuv = null;
            if (HookMain.camera2Hook != null && HookMain.camera2Hook.yuvDecoder != null) {
                yuv = HookMain.camera2Hook.yuvDecoder.acquireLatestFrame();
            }
            if (yuv == null || yuv.yPlane == null || yuv.uPlane == null || yuv.vPlane == null) {
                return;
            }

            Method getPlanesMethod = imageProxy.getClass().getMethod("getPlanes");
            getPlanesMethod.setAccessible(true);
            Object[] planes = (Object[]) getPlanesMethod.invoke(imageProxy);
            if (planes == null || planes.length < 3) return;

            Method getBufferMethod = planes[0].getClass().getMethod("getBuffer");
            getBufferMethod.setAccessible(true);

            java.nio.ByteBuffer yBuf = (java.nio.ByteBuffer) getBufferMethod.invoke(planes[0]);
            java.nio.ByteBuffer uBuf = (java.nio.ByteBuffer) getBufferMethod.invoke(planes[1]);
            java.nio.ByteBuffer vBuf = (java.nio.ByteBuffer) getBufferMethod.invoke(planes[2]);

            if (yBuf != null) {
                yBuf.position(0);
                yBuf.put(yuv.yPlane, 0, Math.min(yBuf.remaining(), yuv.yPlane.length));
                yBuf.position(0);
            }
            if (uBuf != null) {
                uBuf.position(0);
                uBuf.put(yuv.uPlane, 0, Math.min(uBuf.remaining(), yuv.uPlane.length));
                uBuf.position(0);
            }
            if (vBuf != null) {
                vBuf.position(0);
                vBuf.put(yuv.vPlane, 0, Math.min(vBuf.remaining(), yuv.vPlane.length));
                vBuf.position(0);
            }
            LogUtil.log("【CS】【LINE Elsa】成功向 ImageProxy 注入虚拟视频 YUV 帧 (" + yuv.width + "x" + yuv.height + ")");
        } catch (Throwable t) {
            LogUtil.log("【CS】【LINE Elsa】ImageProxy 平面注入失败: " + t);
        }
    }

    /**
     * 第一顺位：拦截 CameraX Preview.SurfaceProvider (h20.b 等)
     */
    private void hookElsaCameraXSurfaceProvider(ClassLoader classLoader) {
        String[] targetClasses = new String[] {
            "h20.b",
            "com.linecorp.elsa.camera.k",
            "com.linecorp.elsa.camera.c"
        };
        for (String className : targetClasses) {
            try {
                Class<?> clazz = Class.forName(className, false, classLoader);
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getName().equals("onSurfaceRequested") && method.getParameterTypes().length == 1) {
                        method.setAccessible(true);
                        Api101Runtime.requireModule().hook(method).intercept(chain -> {
                            Object[] args = HookUtils.toArgs(chain.getArgs());
                            LogUtil.log("【CS】【LINE Elsa】捕获到 SurfaceProvider.onSurfaceRequested: " + args[0]);
                            return chain.proceed(args);
                        });
                        LogUtil.log("【CS】【LINE Elsa】已成功挂载 SurfaceProvider (" + className + "::onSurfaceRequested) Hook");
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }
}

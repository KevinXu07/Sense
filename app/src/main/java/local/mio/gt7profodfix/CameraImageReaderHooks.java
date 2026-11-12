package local.mio.gt7profodfix;

import android.media.Image;
import android.media.ImageReader;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Multi-view front preview is black/garbled while the rear preview is fine.
 * Both HAL streams are 1080x1080 format 0x23, so compare the actual
 * ImageReader buffer layout (plane row/pixel strides and HardwareBuffer
 * format) between the two camera devices.
 */
final class CameraImageReaderHooks {
    private static final Set<String> SEEN_IMAGES = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> SEEN_READERS = ConcurrentHashMap.newKeySet();

    private CameraImageReaderHooks() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> reader = XposedHelpers.findClass("android.media.ImageReader", loader);
            for (String method : new String[]{"acquireNextImage", "acquireLatestImage"}) {
                XposedBridge.hookAllMethods(reader, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (result instanceof Image) {
                            logImage((Image) result, (ImageReader) param.thisObject);
                        }
                    }
                });
            }
            XposedBridge.hookAllMethods(reader, "newInstance", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof ImageReader) {
                        logReader((ImageReader) result);
                    }
                }
            });
            HookLog.info("ImageReader layout diagnostics installed");
        } catch (Throwable error) {
            HookLog.error("ImageReader layout diagnostics failed", error);
        }
    }

    private static void logReader(ImageReader reader) {
        int key = System.identityHashCode(reader);
        if (!SEEN_READERS.add(key)) {
            return;
        }
        StringBuilder sb = new StringBuilder("ImageReader new ")
                .append(reader.getWidth()).append('x').append(reader.getHeight())
                .append(" max=").append(reader.getMaxImages());
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 10); i++) {
            StackTraceElement e = stack[i];
            if (e.getClassName().contains("oplus")) {
                sb.append(" @").append(e.getClassName()).append('#').append(e.getMethodName());
                break;
            }
        }
        HookLog.info(sb.toString());
    }

    private static void logImage(Image image, ImageReader reader) {
        try {
            StringBuilder sb = new StringBuilder("ImageReader image ")
                    .append(image.getWidth()).append('x').append(image.getHeight())
                    .append(" f").append(image.getFormat());
            for (Image.Plane plane : image.getPlanes()) {
                sb.append(" [row=").append(plane.getRowStride())
                        .append(" pix=").append(plane.getPixelStride())
                        .append(" size=").append(plane.getBuffer().remaining()).append(']');
            }
            try {
                Object buffer = XposedHelpers.callMethod(image, "getHardwareBuffer");
                if (buffer != null) {
                    int format = (Integer) XposedHelpers.callMethod(buffer, "getFormat");
                    int width = (Integer) XposedHelpers.callMethod(buffer, "getWidth");
                    int height = (Integer) XposedHelpers.callMethod(buffer, "getHeight");
                    sb.append(" hb=").append(format).append('/')
                            .append(width).append('x').append(height);
                    XposedHelpers.callMethod(buffer, "close");
                }
            } catch (Throwable ignored) {
            }
            String text = sb.toString();
            if (SEEN_IMAGES.add(text)) {
                HookLog.info(text + " reader=" + reader.getWidth() + "x"
                        + reader.getHeight());
            }
        } catch (Throwable error) {
            HookLog.error("ImageReader image log failed", error);
        }
    }
}

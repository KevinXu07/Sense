package local.mio.gt7profodfix;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;

import java.io.File;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Multi-view video is saved by the 201 camera through the Xiaomi
 * vendor.xiaomi.hardware.videoservice.IVideoService, which does not exist in
 * this port.  VideoSaver still inserts the MediaStore row and sets
 * is_pending=0, but nobody renames the .pending-... file, so the gallery
 * cannot find it.  Rename it to VID....mp4 and update the MediaStore row
 * after the saver returns.
 */
public final class CameraMultiViewHooks {
    private static final String SAVER = "li.x0";
    private static final Set<Class<?>> HOOKED = ConcurrentHashMap.newKeySet();
    private static final Set<Class<?>> VIDEO_HOOKED = ConcurrentHashMap.newKeySet();

    private CameraMultiViewHooks() {
    }

    public static void install(ClassLoader classLoader) {
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args.length > 0 && SAVER.equals(param.args[0])) {
                                Object result = param.getResult();
                                if (result instanceof Class<?>) {
                                    hookSaver((Class<?>) result);
                                }
                            }
                        }
                    });
        } catch (Throwable error) {
            HookLog.error("multi-view class-loader hook installation failed", error);
        }
        try {
            hookSaver(XposedHelpers.findClass(SAVER, classLoader));
        } catch (Throwable error) {
            HookLog.info("multi-view saver not loaded yet");
        }
        try {
            Class<?> videoMode = XposedHelpers.findClass("li.t0", classLoader);
            hookVideoMode(videoMode);
        } catch (Throwable error) {
            HookLog.info("multi-view video mode not loaded yet");
        }
        try {
            Class<?> base = XposedHelpers.findClass(
                    "dalvik.system.BaseDexClassLoader", classLoader);
            XposedBridge.hookAllMethods(base, "findClass", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0) {
                        Object name = param.args[0];
                        Object result = param.getResult();
                        if (result instanceof Class<?>) {
                            if ("li.t0".equals(name)) {
                                hookVideoMode((Class<?>) result);
                            } else if (SAVER.equals(name)) {
                                hookSaver((Class<?>) result);
                            }
                        }
                    }
                }
            });
            HookLog.info("multi-view BaseDexClassLoader hook installed");
        } catch (Throwable error) {
            HookLog.error("multi-view BaseDexClassLoader hook failed", error);
        }
    }

    private static void hookVideoMode(Class<?> videoMode) {
        if (videoMode == null || !VIDEO_HOOKED.add(videoMode)) {
            return;
        }
        XposedBridge.hookAllConstructors(videoMode, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object saver = XposedHelpers.getObjectField(
                            param.thisObject, "Q2");
                    if (saver != null) {
                        hookSaver(saver.getClass());
                    }
                } catch (Throwable ignored) {
                }
            }
        });
        HookLog.info("multi-view video mode constructor hook installed");
    }

    private static void hookSaver(Class<?> saver) {
        if (saver == null || !HOOKED.add(saver)) {
            return;
        }
        XposedBridge.hookAllMethods(saver, "b", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object path = XposedHelpers.getObjectField(param.thisObject, "a");
                Object uri = XposedHelpers.getObjectField(param.thisObject, "d");
                if (!(path instanceof String) || !(uri instanceof Uri)) {
                    return;
                }
                String pendingPath = (String) path;
                if (!pendingPath.contains("/.pending-")) {
                    return;
                }
                Object activity = XposedHelpers.getObjectField(param.thisObject, "m");
                Context context = activity instanceof Context
                        ? ((Context) activity).getApplicationContext() : null;
                if (context == null) {
                    context = currentApplication();
                }
                if (context == null) {
                    return;
                }
                final Context appContext = context;
                final Uri pendingUri = (Uri) uri;
                Thread thread = new Thread(
                        () -> finalizePending(appContext, pendingPath, pendingUri),
                        "MultiViewFinalize");
                thread.start();
            }
        });
        HookLog.info("multi-view pending finalize installed");
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread
                    .getMethod("currentApplication")
                    .invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void finalizePending(Context context, String path, Uri uri) {
        try {
            Thread.sleep(1500);
            File source = new File(path);
            String name = source.getName();
            int marker = name.indexOf("-VID");
            if (marker < 0) {
                HookLog.info("multi-view: no VID marker in " + name);
                return;
            }
            String finalName = name.substring(marker + 1);
            File target = new File(source.getParentFile(), finalName);
            if (!source.exists()) {
                HookLog.info("multi-view: pending file missing " + path);
                return;
            }
            if (target.exists()) {
                HookLog.info("multi-view: target already exists " + target);
                return;
            }
            boolean renamed = source.renameTo(target);
            HookLog.info("multi-view: rename " + renamed + " -> " + target);
            ContentValues values = new ContentValues();
            values.put("_data", target.getAbsolutePath());
            values.put("_display_name", finalName);
            values.put("is_pending", 0);
            int updated = context.getContentResolver().update(uri, values, null, null);
            HookLog.info("multi-view: MediaStore update " + updated);
            MediaScannerConnection.scanFile(
                    context, new String[]{target.getAbsolutePath()}, null, null);
        } catch (Throwable error) {
            HookLog.error("multi-view finalize failed", error);
        }
    }
}

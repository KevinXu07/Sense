package local.mio.gt7profodfix;

import android.content.Context;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Multi-view finalize fallback.  The camera's RecordProcessor stop path
 * releases the HEVC encoder but never reaches MPEG4Writer.stop(), so the
 * pending MP4 has ftyp/mdat and no moov.  Track Java muxer/recorder objects
 * created in the camera process and force stop() after RecordProcessor.I()
 * returns.
 */
final class CameraMuxerHooks {
    private static final List<Object> MUXERS = new CopyOnWriteArrayList<>();
    private static final List<Object> RECORDERS = new CopyOnWriteArrayList<>();

    private CameraMuxerHooks() {
    }

    static void install(ClassLoader loader) {
        try {
            XposedBridge.hookAllConstructors(MediaMuxer.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null) {
                        MUXERS.add(param.thisObject);
                        HookLog.info("MediaMuxer created, total=" + MUXERS.size());
                    }
                }
            });
        } catch (Throwable error) {
            HookLog.error("MediaMuxer constructor hook failed", error);
        }
        try {
            XposedBridge.hookAllConstructors(MediaRecorder.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject != null) {
                        RECORDERS.add(param.thisObject);
                        HookLog.info("MediaRecorder created, total=" + RECORDERS.size());
                    }
                }
            });
        } catch (Throwable error) {
            HookLog.error("MediaRecorder constructor hook failed", error);
        }
        try {
            Class<?> processor = XposedHelpers.findClass("rj.e0", loader);
            XposedBridge.hookMethod(
                    XposedHelpers.findMethodExact(processor, "I", int.class, boolean.class),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            HookLog.info("RecordProcessor.I returned " + param.getResult()
                                    + ", muxers=" + MUXERS.size()
                                    + ", recorders=" + RECORDERS.size());
                            Thread thread = new Thread(CameraMuxerHooks::forceStop,
                                    "MultiViewForceStop");
                            thread.start();
                        }
                    });
            HookLog.info("RecordProcessor stop hook installed");
        } catch (Throwable error) {
            HookLog.error("RecordProcessor stop hook failed", error);
        }
    }

    private static void forceStop() {
        try {
            Thread.sleep(1200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return;
        }
        for (Object muxer : MUXERS) {
            try {
                ((MediaMuxer) muxer).stop();
                HookLog.info("forced MediaMuxer.stop");
            } catch (Throwable error) {
                HookLog.info("forced MediaMuxer.stop skipped: " + error);
            }
        }
        MUXERS.clear();
        for (Object recorder : RECORDERS) {
            try {
                ((MediaRecorder) recorder).stop();
                HookLog.info("forced MediaRecorder.stop");
            } catch (Throwable error) {
                HookLog.info("forced MediaRecorder.stop skipped: " + error);
            }
        }
        RECORDERS.clear();
        finalizePendingVideos();
    }

    private static void finalizePendingVideos() {
        try {
            File dir = new File("/storage/emulated/0/DCIM/Camera");
            File[] files = dir.listFiles((d, name) ->
                    name.startsWith(".pending-") && name.contains("-VID")
                            && name.endsWith(".mp4"));
            if (files == null || files.length == 0) {
                return;
            }
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            long now = System.currentTimeMillis();
            Context context = currentApplication();
            for (File source : files) {
                if (now - source.lastModified() > 60_000L) {
                    continue;
                }
                String name = source.getName();
                int marker = name.indexOf("-VID");
                if (marker < 0) {
                    continue;
                }
                File target = new File(dir, name.substring(marker + 1));
                if (target.exists()) {
                    continue;
                }
                if (source.renameTo(target)) {
                    HookLog.info("multi-view renamed " + target.getName());
                    if (context != null) {
                        MediaScannerConnection.scanFile(
                                context, new String[]{target.getAbsolutePath()}, null, null);
                    }
                } else {
                    HookLog.info("multi-view rename failed " + name);
                }
            }
        } catch (Throwable error) {
            HookLog.error("multi-view pending finalize failed", error);
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

package local.mio.gt7profodfix;

import android.media.MediaFormat;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Diagnostics for the multi-view HEVC encoder.  The vendor codec2 service
 * enables VPP filters (sIgF/cvpF/imgTxrF) whose stop/release path fails and
 * the MPEG4Writer video track never receives EOS.  Log the MediaFormat keys
 * the camera sets so the VPP-enabling key can be removed.
 */
final class CameraCodecHooks {
    private CameraCodecHooks() {
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> format = XposedHelpers.findClass("android.media.MediaFormat", loader);
            XposedBridge.hookAllMethods(format, "setInteger", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length >= 2 && "isOplusCamera".equals(param.args[0])) {
                        HookLog.info("force isOplusCamera=0 (was " + param.args[1] + ")");
                        param.args[1] = 0;
                    }
                }
            });
            for (String method : new String[]{
                    "setInteger", "setString", "setLong", "setFloat", "setFeatureEnabled"}) {
                XposedBridge.hookAllMethods(format, method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length < 2 || !(param.args[0] instanceof String)) {
                            return;
                        }
                        String key = (String) param.args[0];
                        String lower = key.toLowerCase();
                        if (lower.contains("vpp") || lower.contains("codec")
                                || lower.contains("c2.") || lower.contains("vendor.qti")
                                || lower.contains("feature")) {
                            HookLog.info("MediaFormat." + method + " " + key
                                    + "=" + param.args[1]);
                        }
                    }
                });
            }
            HookLog.info("MediaFormat key diagnostics installed");
        } catch (Throwable error) {
            HookLog.error("MediaFormat key diagnostics failed", error);
        }
        try {
            Class<?> codec = XposedHelpers.findClass("android.media.MediaCodec", loader);
            XposedBridge.hookAllMethods(codec, "configure", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof MediaFormat) {
                        HookLog.info("MediaCodec.configure " + param.args[0]);
                    }
                }
            });
        } catch (Throwable error) {
            HookLog.error("MediaCodec.configure diagnostics failed", error);
        }
    }
}

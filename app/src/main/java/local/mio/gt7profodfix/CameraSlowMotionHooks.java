package local.mio.gt7profodfix;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * OS4's framework rejects the OplusCamera slow-video preview surface
 * (format 35 / usage 0x103) before the constrained high-speed session is
 * sent to cameraserver.  The Oplus app/HAL pair is valid on the stock
 * ColorOS framework, so keep the app's high-speed session and skip only
 * the framework-side surface-shape checks in the camera app process.
 */
public final class CameraSlowMotionHooks {
    private CameraSlowMotionHooks() {
    }

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> surfaceUtils = XposedHelpers.findClass(
                    "android.hardware.camera2.utils.SurfaceUtils", classLoader);
            XposedBridge.hookAllMethods(surfaceUtils, "checkHighSpeedSurfaceFormat",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return null;
                        }
                    });
            XposedBridge.hookAllMethods(surfaceUtils, "checkConstrainedHighSpeedSurfaces",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return null;
                        }
                    });
            HookLog.info("camera slow-video SurfaceUtils checks bypassed");
        } catch (Throwable error) {
            HookLog.error("camera slow-video hook installation failed", error);
        }
    }
}

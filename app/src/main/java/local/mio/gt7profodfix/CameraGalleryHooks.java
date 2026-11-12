package local.mio.gt7profodfix;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The ColorOS gallery package is only a blank stub on this port.  Point the
 * camera's thumbnail intent at the installed HyperOS gallery instead, so the
 * bottom-left thumbnail opens a real viewer.
 */
public final class CameraGalleryHooks {
    private CameraGalleryHooks() {
    }

    public static void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("yn.h1", classLoader, "a",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return "com.miui.gallery";
                        }
                    });
            HookLog.info("camera thumbnail gallery -> com.miui.gallery");
        } catch (Throwable error) {
            HookLog.error("camera gallery hook installation failed", error);
        }
    }
}

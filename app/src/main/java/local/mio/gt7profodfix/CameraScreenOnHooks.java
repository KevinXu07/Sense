package local.mio.gt7profodfix;

import android.app.Activity;
import android.view.Window;
import android.view.WindowManager;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Keep the display on while the camera is in the foreground.
 *
 * The ported camera does not reliably hold FLAG_KEEP_SCREEN_ON.  With the
 * system screen timeout at 60 s the display turns off during preview; the
 * camera then shows its "点击屏幕恢复显示" overlay, which looks like the
 * telephoto switch blacking out the screen.  Add the flag on resume and stop
 * the app from clearing it.
 */
final class CameraScreenOnHooks {
    private static final int KEEP_ON = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

    private CameraScreenOnHooks() {
    }

    static void install(ClassLoader loader) {
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) {
                        Activity activity = (Activity) param.thisObject;
                        if (activity.getClass().getName().startsWith("com.oplus.camera")) {
                            activity.getWindow().addFlags(KEEP_ON);
                            HookLog.info("camera window keep-screen-on added");
                        }
                    }
                }
            });
            HookLog.info("camera keep-screen-on Activity.onResume hook installed");
        } catch (Throwable error) {
            HookLog.error("camera keep-screen-on onResume hook failed", error);
        }
        try {
            XposedBridge.hookAllMethods(Window.class, "clearFlags", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof Integer) {
                        int flags = (Integer) param.args[0];
                        if ((flags & KEEP_ON) != 0) {
                            param.args[0] = flags & ~KEEP_ON;
                        }
                    }
                }
            });
            HookLog.info("camera window clearFlags keep-screen-on guard installed");
        } catch (Throwable error) {
            HookLog.error("camera keep-screen-on clearFlags hook failed", error);
        }
    }
}

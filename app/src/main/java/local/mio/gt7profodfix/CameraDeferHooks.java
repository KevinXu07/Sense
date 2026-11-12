package local.mio.gt7profodfix;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Portrait (and other APS deferred) captures are saved into the native
 * APSDeferJobGoverner, but the governor only runs a job after Java sends the
 * defer "Start" command.  While the camera stays in the foreground that
 * command comes from ForeGroundStillController (kc.c), which posts it
 * capture_defer.trigger.delay ms after a shot.  Every trigger path there is
 * gated by this config key, which the port's camera config does not carry,
 * so jobs piled up until onStop flushed them.  Report the key as enabled.
 */
public final class CameraDeferHooks {
    private static final String FG_STILL_KEY =
            "com.oplus.camera.feature.capture_defer.fg.still.open.support";

    private CameraDeferHooks() {
    }

    public static void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.camera.configure.CameraConfig",
                    classLoader, "getConfigBooleanValue", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (FG_STILL_KEY.equals(param.args[0])) {
                                param.setResult(Boolean.TRUE);
                            }
                        }
                    });
            HookLog.info("camera foreground defer trigger enabled");
        } catch (Throwable error) {
            HookLog.error("camera defer hook installation failed", error);
        }
    }
}

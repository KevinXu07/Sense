package local.mio.gt7profodfix;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The ported OS4 APS stack has no ProfessionalMode pipeline.  Map that
 * capture mode to the normal photo ("common") APS pipeline at runtime so the
 * HAL frames get a valid trigger/pipeline and the final JPEG is delivered.
 * This keeps the stock unit.sdk.jar untouched, so slow-motion and the other
 * APS modes are unaffected.
 *
 * Starry mode is deliberately not mapped: with the 201 ODM algorithm pair it
 * has its own ALGO_STARMODE pipeline, and forcing it to common leaves the
 * 15-frame star request without a capture trigger (no JPEG at all).
 */
public final class CameraProModeHooks {
    private CameraProModeHooks() {
    }

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> convert = XposedHelpers.findClass(
                    "com.oplus.ocs.camera.consumer.ApsDataConvert", classLoader);
            XposedBridge.hookAllMethods(convert, "getApsModeName", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 2
                            && "professional_mode".equals(param.args[0])) {
                        param.setResult("common");
                    }
                }
            });
            HookLog.info("professional mode mapped to common APS");
        } catch (Throwable error) {
            HookLog.error("professional mode hook installation failed", error);
        }
    }
}

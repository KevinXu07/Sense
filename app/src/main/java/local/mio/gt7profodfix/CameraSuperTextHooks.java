package local.mio.gt7profodfix;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * The ported XDoc SDK's OpenCL preview path cannot share the OS4 camera
 * preview texture (SetInputTexture returns 16), then dereferences the null
 * buffer and SIGSEGVs.  The capture path uses the CPU detectTextByByteNative
 * entry, so return an empty preview quad result and let the mode fall back to
 * its built-in "no document" state instead of crashing.
 */
public final class CameraSuperTextHooks {
    private CameraSuperTextHooks() {
    }

    public static void install(ClassLoader classLoader) {
        try {
            Class<?> helper = XposedHelpers.findClass(
                    "com.oplus.ocs.camera.SuperTextHelper", classLoader);
            XposedBridge.hookAllMethods(helper, "FindQuadByTextureNative",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return "";
                        }
                    });
            HookLog.info("super text preview quad disabled (GPU path unsafe)");
        } catch (Throwable error) {
            HookLog.error("super text hook installation failed", error);
        }
    }
}

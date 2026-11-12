package local.mio.gt7profodfix;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Preview face-beauty crash guard for the ported 6.070 OplusCamera.
 *
 * ob.e.process passes TexturePreviewRequest.getCustomBeautyParams() as the
 * last argument of OplusFaceBeautyPreviewHelper.process(int, int[], int[],
 * int[]). libApsFaceBeautyPreviewJni.so stores that array pointer in its
 * AppFaceBeautyParam_t with a fixed count of 8, and
 * ParamAdjustFactory::updateParamByAppParam dereferences it unconditionally,
 * so a null array (modes that never set custom beauty, e.g. high-definition
 * after a cold start) is a SIGSEGV on PreviewGLThread.
 *
 * Substitute an all-zero parameter block for a null one: zero is "no custom
 * adjustment", which is what a missing array means to the app.
 */
final class CameraFaceBeautyHooks {
    /** updateParamByAppParam reads 8 ints; leave headroom for newer layouts. */
    private static final int PARAM_COUNT = 32;

    private CameraFaceBeautyHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> helper = XposedHelpers.findClass(
                "com.oplus.ocs.camera.OplusFaceBeautyPreviewHelper", loader);
        XposedBridge.hookAllMethods(helper, "process", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object[] args = param.args;
                if (args.length != 4 || args[3] != null) {
                    return;
                }
                args[3] = new int[PARAM_COUNT];
                HookLog.once("face-beauty-null",
                        "face beauty: substituted empty custom params");
            }
        });
        HookLog.info("face beauty: preview param guard installed");
    }
}

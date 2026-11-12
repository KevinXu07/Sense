package local.mio.gt7profodfix;

import android.content.res.Resources;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The MIUI dynamic island geometry lives in MiuiSystemUI resources. The original
 * signed DevicesOverlay (74px island / 14px status-bar padding) cannot be
 * installed on Android 17 because a third-party-signed static overlay is
 * rejected ("signed with different certificates"). Force the two dimension
 * values at the Resources layer instead.
 */
final class IslandHooks {
    private static final int CUT_OUT_CENTER_Y = 0x7f0702c1;
    private static final int STATUS_BAR_PADDING_TOP = 0x7f071938;
    private static final int ISLAND_Y = 74;
    private static final int PADDING_TOP = 14;

    private static volatile boolean logged;

    private IslandHooks() {
    }

    static void install(ClassLoader loader) {
        XposedBridge.hookAllMethods(Resources.class, "getDimensionPixelSize",
                new PixelHook());
        XposedBridge.hookAllMethods(Resources.class, "getDimensionPixelOffset",
                new PixelHook());
        XposedBridge.hookAllMethods(Resources.class, "getDimension",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Integer value = valueOf(param);
                        if (value != null) {
                            param.setResult((float) value);
                        }
                    }
                });
        HookLog.info("island resource hooks installed");
    }

    private static Integer valueOf(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0) {
            return null;
        }
        Object arg = param.args[0];
        if (!(arg instanceof Integer)) {
            return null;
        }
        int id = (Integer) arg;
        int value;
        if (id == CUT_OUT_CENTER_Y) {
            value = ISLAND_Y;
        } else if (id == STATUS_BAR_PADDING_TOP) {
            value = PADDING_TOP;
        } else {
            return null;
        }
        if (!logged) {
            logged = true;
            HookLog.info("island resource override active id=0x" + Integer.toHexString(id));
        }
        return value;
    }

    private static final class PixelHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Integer value = valueOf(param);
            if (value != null) {
                param.setResult(value);
            }
        }
    }
}

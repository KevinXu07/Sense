package local.mio.gt7profodfix;

import android.content.res.Resources;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * The original (signed) donor DevicesAndroidOverlay carries donor geometry:
 * rounded_corner_radius=140px and status_bar_height_portrait=169px.  The
 * RMX5010 port needs 116px / 160px.  Force those android: dimens at the
 * Resources layer in system_server and SystemUI.
 */
final class CornerHooks {
    private static final String[] NAMES = {
            "rounded_corner_radius", "status_bar_height_portrait", "status_bar_height"
    };
    private static final int[] VALUES = {116, 160, 160};

    private static volatile int[] ids;

    private CornerHooks() {
    }

    static void install(ClassLoader loader) {
        XposedBridge.hookAllMethods(Resources.class, "getDimensionPixelSize",
                new PixelHook());
        XposedBridge.hookAllMethods(Resources.class, "getDimensionPixelOffset",
                new PixelHook());
        XposedBridge.hookAllMethods(Resources.class, "getDimension",
                new FloatHook());
        HookLog.info("corner resource hooks installed");
    }

    private static int valueOf(XC_MethodHook.MethodHookParam param) {
        int[] resolved = resolve();
        if (resolved == null || param.args == null || param.args.length == 0
                || !(param.args[0] instanceof Integer)) {
            return Integer.MIN_VALUE;
        }
        int id = (Integer) param.args[0];
        for (int i = 0; i < resolved.length; i++) {
            if (id == resolved[i]) {
                return VALUES[i];
            }
        }
        return Integer.MIN_VALUE;
    }

    private static synchronized int[] resolve() {
        if (ids != null) {
            return ids;
        }
        try {
            Resources res = Resources.getSystem();
            int[] out = new int[NAMES.length];
            for (int i = 0; i < NAMES.length; i++) {
                out[i] = res.getIdentifier(NAMES[i], "dimen", "android");
            }
            ids = out;
            HookLog.info("corner ids=" + out[0] + "," + out[1] + "," + out[2]);
            return out;
        } catch (Throwable error) {
            HookLog.error("corner resolve failed", error);
            return null;
        }
    }

    private static final class PixelHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            int value = valueOf(param);
            if (value != Integer.MIN_VALUE) {
                param.setResult(value);
            }
        }
    }

    private static final class FloatHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            int value = valueOf(param);
            if (value != Integer.MIN_VALUE) {
                param.setResult((float) value);
            }
        }
    }
}

package local.mio.gt7profodfix;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Carry the RMX5010 status-bar color fix in the module instead of replacing
 * the 50 MB MiuiSystemUI APK.
 *
 * The stock HyperOS callback masks displayId with 0xFEFFFFFF before comparing
 * it with the per-display instance value. On this port some appearance events
 * carry displayId 0x01xxxxxx, so the masked comparison never matches and the
 * status-bar icon color stays stale. The verified custom SystemUI simply
 * compares the raw displayId. Normalizing the argument to the instance display
 * id before the stock method runs produces the same result and is idempotent
 * on the already-patched live APK.
 */
final class StatusBarHooks {
    private static final int DISPLAY_ID_MASK = 0xFEFFFFFF;

    private StatusBarHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> callback = Reflect.findClass(loader,
                "com.android.systemui.statusbar.data.repository."
                        + "StatusBarModePerDisplayRepositoryImpl$commandQueueCallback$1");
        if (callback == null) {
            HookLog.once("statusbar_callback_missing",
                    "status bar display callback missing; color fix skipped");
            return;
        }
        int hooks = hookDisplay(callback, "onSystemBarAttributesChanged");
        hooks += hookDisplay(callback, "showTransient");
        hooks += hookDisplay(callback, "abortTransient");
        if (hooks == 0) {
            HookLog.once("statusbar_methods_missing",
                    "status bar display methods missing; color fix skipped");
        } else {
            HookLog.info("status bar color normalization hooks=" + hooks);
        }
    }

    private static int hookDisplay(Class<?> callback, String methodName) {
        return Reflect.hookNamedMethods(callback, methodName, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int displayId = (Integer) param.args[0];
                Object instanceId = Reflect.get(param.thisObject, "$thisDisplayId");
                if (!(instanceId instanceof Integer)) {
                    return;
                }
                int expected = (Integer) instanceId;
                if (displayId != expected
                        && (displayId & DISPLAY_ID_MASK) == expected) {
                    param.args[0] = expected;
                    HookLog.once("statusbar_display_" + methodName,
                            "status bar " + methodName + " displayId "
                                    + displayId + " -> " + expected);
                }
            }
        });
    }
}

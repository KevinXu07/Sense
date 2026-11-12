package local.mio.gt7profodfix;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Expose the stock "lift to wake" switch without forcing it on.
 *
 * SystemUiHooks owns the strict behavior: it only registers the type 22 pickup
 * sensor while pick_up_gesture_wakeup_mode is enabled. Settings hides the item
 * unless the HyperOS feature flag and the AOD/lock-screen capability say it is
 * supported, so advertise the capability only.
 */
final class SettingsHooks {
    private SettingsHooks() {
    }

    static void install(ClassLoader loader) {
        FeatureFlagHooks.forceTrue(loader, Set.of("support_gesture_wakeup"), "settings");

        String[] candidates = {
                "com.android.settings.aod.AodAndLockScreenSettings",
                "com.android.settings.AodAndLockScreenSettings",
                "com.android.settings.display.AodAndLockScreenSettings"
        };
        for (String name : candidates) {
            Class<?> target = Reflect.findClass(loader, name);
            if (target == null) {
                continue;
            }
            int count = Reflect.hookNamedMethods(target, "isSupportPickupWakeup",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
            HookLog.info("Settings pickup capability hooks=" + count + " in " + name);
            return;
        }
        HookLog.once("settings_pickup_class_missing",
                "AodAndLockScreenSettings missing; pickup switch capability not advertised");
    }
}

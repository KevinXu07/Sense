package local.mio.gt7profodfix;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * AOSP AutoDataSwitchController ships two gate hooks that the vendor subclass
 * (QtiAutoDataSwitchController) is meant to fill in, but on this port the plain
 * controller is the one running and both gates return false. The only remaining
 * gate is the per-slot "mobile_data" switch, which HyperOS keeps on for both
 * slots, so the non-default SIM takes over data whenever the chosen SIM is
 * roaming or weaker. Wire the gates to the real user toggle, the
 * MOBILE_DATA_POLICY_AUTO_DATA_SWITCH policy on the backup SIM.
 */
final class AutoDataSwitchHooks {
    private static final String CONTROLLER =
            "com.android.internal.telephony.data.AutoDataSwitchController";
    private static final String PHONE = "com.android.internal.telephony.Phone";
    private static final int MOBILE_DATA_POLICY_AUTO_DATA_SWITCH = 3;
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AutoDataSwitchHooks() {
    }

    static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) return;
        Class<?> controller = XposedHelpers.findClass(CONTROLLER, classLoader);
        Class<?> phone = XposedHelpers.findClass(PHONE, classLoader);

        XposedHelpers.findAndHookMethod(controller, "isUserDataSettingEnabledOnNonDDS", phone,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(true);
                    }
                });

        XposedHelpers.findAndHookMethod(controller, "isAutoSwitchEnabledByUser", phone,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        boolean enabled = false;
                        try {
                            Object dsm = XposedHelpers.callMethod(param.args[0],
                                    "getDataSettingsManager");
                            enabled = (Boolean) XposedHelpers.callMethod(dsm,
                                    "isMobileDataPolicyEnabled",
                                    MOBILE_DATA_POLICY_AUTO_DATA_SWITCH);
                        } catch (Throwable error) {
                            HookLog.once("ads-policy-err",
                                    "AutoDataSwitch: policy lookup failed: " + error);
                        }
                        param.setResult(enabled);
                        if (!enabled) {
                            HookLog.once("ads-off",
                                    "AutoDataSwitch: backup SIM has auto switch off, keeping chosen SIM");
                        }
                    }
                });
        HookLog.info("AutoDataSwitch: user-policy gate installed");
    }
}

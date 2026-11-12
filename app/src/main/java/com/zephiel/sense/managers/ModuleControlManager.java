package com.zephiel.sense.managers;

import com.zephiel.sense.util.RootUtils;

import java.util.Locale;

/**
 * Bridge between the Sense UI and the RMX5010 KSU module.
 *
 * The module is the execution plane: it owns root scripts, the touch node and
 * the wallet AppOp. Sense only writes the documented Settings.Global keys and
 * calls the explicit ctl.sh actions; it never edits /data/adb files directly.
 */
public final class ModuleControlManager {

    public static final String MODULE_DIR = "/data/adb/modules/rmx5010_fod_bridge";
    public static final String TOUCH_RATE_KEY = "op13_fix_touch_sampling_rate";
    public static final String TOUCH_OWNER_KEY = "op13_fix_touch_owner";
    public static final String HAPTIC_SCALE_KEY = "op13_fix_haptic_scale";
    public static final String IFAA_MODEL_KEY = "op13_fix_ifaa_model";
    public static final String XPOSED_ACTIVE_API_KEY = "op13_fix_xposed_active_api";
    public static final String WALLET_PACKAGE = "com.finshell.wallet";
    public static final String IFAA_PACKAGE = "org.ifaa.aidl.manager";

    private static final String WALLET_SWIPE_CLASS =
            "com.nearme.wallet.nfc.ui.NfcConsumeActivity";
    private static final String WALLET_OPEN_ACTION =
            "finshell.wallet.intent.action.OPEN";
    private static final String WALLET_SESSION_EXTRA =
            "local.mio.coloroswalletcompat.extra.LOCKSCREEN_SESSION";

    private ModuleControlManager() {
    }

    public static Status load() {
        Status status = new Status();
        RootUtils.CommandResult result = RootUtils.exec(
                "sh " + MODULE_DIR + "/ctl.sh status 2>/dev/null", 12000);
        if (result != null && result.isSuccess()) {
            parse(result.output, status);
        }

        if (status.moduleVersion.isEmpty()) {
            RootUtils.CommandResult v = RootUtils.exec(
                    "grep '^version=' " + MODULE_DIR + "/module.prop 2>/dev/null", 3000);
            if (v != null && v.isSuccess() && v.output.startsWith("version=")) {
                status.moduleVersion = v.output.substring("version=".length()).trim();
                status.moduleInstalled = true;
            }
        }
        if (!status.moduleInstalled) {
            RootUtils.CommandResult exists = RootUtils.exec(
                    "test -f " + MODULE_DIR + "/module.prop && echo 1", 3000);
            status.moduleInstalled = exists != null && exists.isSuccess()
                    && "1".equals(exists.output.trim());
        }

        if (status.touchRate.isEmpty()) {
            status.touchRate = getGlobal(TOUCH_RATE_KEY, "120");
        }
        if (status.touchOwner.isEmpty()) {
            status.touchOwner = getGlobal(TOUCH_OWNER_KEY, "module");
        }
        if (status.hapticScale.isEmpty()) {
            status.hapticScale = getGlobal(HAPTIC_SCALE_KEY, "1.0");
        }
        if (status.ifaaModel.isEmpty()) {
            status.ifaaModel = getGlobal(IFAA_MODEL_KEY, "ONEPLUS-R23821");
        }
        if ("unknown".equals(status.walletAppop) || status.walletAppop.isEmpty()) {
            status.walletAppop = readWalletAppOp();
        }
        status.walletInstalled = status.walletInstalled || packageInstalled(WALLET_PACKAGE);
        status.lspActive = isLspActive();
        return status;
    }

    public static boolean setTouchRate(int rate) {
        if (rate != 70 && rate != 120 && rate != 180 && rate != 240 && rate != 360) {
            return false;
        }
        boolean rateOk = setGlobal(TOUCH_RATE_KEY, String.valueOf(rate));
        boolean ownerOk = setGlobal(TOUCH_OWNER_KEY, "sense");
        return rateOk && ownerOk;
    }

    public static boolean setHapticScale(float scale) {
        float clamped = Math.max(0.3f, Math.min(1.2f, scale));
        String value = String.format(Locale.US, "%.1f", clamped);
        boolean ok = setGlobal(HAPTIC_SCALE_KEY, value);
        RootUtils.CommandResult applied = RootUtils.exec(
                "sh " + MODULE_DIR + "/ctl.sh set-haptic " + value, 5000);
        return ok && applied != null && applied.isSuccess();
    }

    public static boolean setIfaaModel(String model) {
        String value = model == null ? "" : model.trim();
        if (value.isEmpty() || !value.matches("[A-Za-z0-9._-]{1,64}")) {
            return false;
        }
        return setGlobal(IFAA_MODEL_KEY, value);
    }

    public static boolean setTouchOwnerSense() {
        return setGlobal(TOUCH_OWNER_KEY, "sense");
    }

    public static boolean grantWalletShowWhenLocked() {
        if (!packageInstalled(WALLET_PACKAGE)) {
            return false;
        }
        RootUtils.CommandResult result = RootUtils.exec(
                "cmd appops set " + WALLET_PACKAGE + " 10020 allow", 5000);
        return result != null && result.isSuccess()
                && "allow".equals(readWalletAppOp());
    }

    public static boolean testWalletSwipe() {
        if (!packageInstalled(WALLET_PACKAGE)) {
            return false;
        }
        String command = "am start -a " + WALLET_OPEN_ACTION
                + " -n " + WALLET_PACKAGE + "/" + WALLET_SWIPE_CLASS
                + " --ez " + WALLET_SESSION_EXTRA + " true"
                + " -f 0x34080000";
        RootUtils.CommandResult result = RootUtils.exec(command, 5000);
        return result != null && result.isSuccess();
    }

    public static boolean restartIfaa() {
        RootUtils.CommandResult result = RootUtils.exec(
                "am force-stop " + IFAA_PACKAGE, 3000);
        return result != null && result.isSuccess();
    }

    public static boolean reapply() {
        RootUtils.CommandResult result = RootUtils.exec(
                "sh " + MODULE_DIR + "/ctl.sh reapply", 15000);
        return result != null && result.isSuccess();
    }

    private static boolean setGlobal(String key, String value) {
        if (!isSafeKey(key) || value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
            return false;
        }
        RootUtils.CommandResult result = RootUtils.exec(
                "settings put global " + key + " " + value, 3000);
        return result != null && result.isSuccess();
    }

    private static boolean isSafeKey(String key) {
        return TOUCH_RATE_KEY.equals(key)
                || TOUCH_OWNER_KEY.equals(key)
                || HAPTIC_SCALE_KEY.equals(key)
                || IFAA_MODEL_KEY.equals(key);
    }

    private static String getGlobal(String key, String fallback) {
        RootUtils.CommandResult result = RootUtils.exec(
                "settings get global " + key, 3000);
        if (result == null || !result.isSuccess()) {
            return fallback;
        }
        String value = result.output.trim();
        return value.isEmpty() || "null".equals(value) ? fallback : value;
    }

    private static boolean isLspActive() {
        RootUtils.CommandResult result = RootUtils.exec(
                "settings get global " + XPOSED_ACTIVE_API_KEY, 3000);
        if (result == null || !result.isSuccess()) {
            return false;
        }
        try {
            return Integer.parseInt(result.output.trim())
                    == android.os.Build.VERSION.SDK_INT;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static boolean packageInstalled(String packageName) {
        RootUtils.CommandResult result = RootUtils.exec(
                "pm list packages " + packageName, 5000);
        if (result == null || !result.isSuccess()) {
            return false;
        }
        for (String line : result.output.split("\n")) {
            if (("package:" + packageName).equals(line.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String readWalletAppOp() {
        RootUtils.CommandResult result = RootUtils.exec(
                "cmd appops get " + WALLET_PACKAGE + " 10020", 3000);
        if (result == null || !result.isSuccess()) {
            return "unknown";
        }
        String value = result.output;
        if (value.contains("allow")) {
            return "allow";
        }
        if (value.contains("ignore")) {
            return "ignore";
        }
        return "unknown";
    }

    private static void parse(String output, Status status) {
        for (String line : output.split("\n")) {
            int index = line.indexOf('=');
            if (index <= 0) {
                continue;
            }
            String key = line.substring(0, index).trim();
            String value = line.substring(index + 1).trim();
            switch (key) {
                case "module":
                    status.moduleInstalled = !value.isEmpty();
                    break;
                case "version":
                    status.moduleVersion = value;
                    break;
                case "boot_time":
                    status.bootTime = value;
                    break;
                case "touch_rate":
                    status.touchRate = value;
                    break;
                case "touch_owner":
                    status.touchOwner = value;
                    break;
                case "touch_supported":
                    status.touchSupported = "1".equals(value);
                    break;
                case "haptic_scale":
                    status.hapticScale = value;
                    break;
                case "ifaa_model":
                    status.ifaaModel = value;
                    break;
                case "ifaa_domain":
                    status.ifaaDomain = value;
                    break;
                case "wallet_installed":
                    status.walletInstalled = "1".equals(value);
                    break;
                case "wallet_appop":
                    status.walletAppop = value;
                    break;
                case "audio_mounted":
                    status.audioMounted = "1".equals(value);
                    break;
                default:
                    break;
            }
        }
    }

    public static final class Status {
        public boolean moduleInstalled;
        public String moduleVersion = "";
        public String bootTime = "";
        public String touchRate = "";
        public String touchOwner = "";
        public boolean touchSupported;
        public String hapticScale = "";
        public String ifaaModel = "";
        public String ifaaDomain = "";
        public boolean walletInstalled;
        public String walletAppop = "";
        public boolean lspActive;
        public boolean audioMounted;

        public float hapticScaleValue() {
            try {
                return Float.parseFloat(hapticScale);
            } catch (NumberFormatException ignored) {
                return 1.0f;
            }
        }
    }
}

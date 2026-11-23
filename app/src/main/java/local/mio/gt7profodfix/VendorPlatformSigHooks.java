package local.mio.gt7profodfix;

import android.content.pm.PermissionInfo;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;

/**
 * The realme vendor apps (CneApp, IWlanService, CACertService, ...) live in
 * /vendor/app and get their signature permissions from being signed with the
 * realme platform key.  The ported system's platform key is Xiaomi's, so they
 * lose them: CneApp can no longer request the IMS network for the IMS daemon
 * (requestNetwork throws SecurityException for CONNECTIVITY_USE_RESTRICTED_NETWORKS),
 * the framework never brings the IMS PDN up as an Android network and the IMS
 * media stack never gets a net handle.  Making them vendor priv-apps does not
 * help, because privileged vendor apps only get vendorPrivileged permissions.
 *
 * Treat a vendor/odm package signed with the realme platform certificate as
 * platform-signed for the platform's ("android") signature permissions, which
 * is what ColorOS decides for the same packages.
 */
final class VendorPlatformSigHooks {
    private static final String POLICY_CLASS =
            "com.android.server.permission.access.permission.AppIdPermissionPolicy";
    // SHA-256 of the realme/Oplus platform certificate (CN=AndroidTeam, O=Oplus).
    private static final byte[] REALME_PLATFORM_CERT_SHA256 = hex(
            "fc98dae63ad39626c8c67fbe83f2f06f74932a9cd146b92cecfc6a047a904386");

    private VendorPlatformSigHooks() {
    }

    static void install(ClassLoader loader) {
        // Images built with scripts/patch_services_vendor_sig.py carry the same
        // check in services.jar.
        if (Reflect.findClass(loader, "com.android.server.pm.RmxVendorPlatformSig") != null) {
            HookLog.once("vendor_sig_in_image", "vendor platform signature: in image, hook skipped");
            return;
        }
        Class<?> policy = Reflect.findClass(loader, POLICY_CLASS);
        if (policy == null) {
            HookLog.once("vendor_sig_policy_missing", POLICY_CLASS + " missing; vendor platform signature hook skipped");
            return;
        }
        int hooked = Reflect.hookNamedMethods(policy, "shouldGrantPermissionBySignature", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.getResult()) || param.args.length < 3) {
                    return;
                }
                Object packageState = param.args[1];
                Object permission = param.args[2];
                try {
                    if (!isPlatformPermission(permission) || !isVendorPackage(packageState)
                            || !isRealmePlatformSigned(packageState)) {
                        return;
                    }
                } catch (Throwable error) {
                    HookLog.once("vendor_sig_check_failed", "vendor platform signature check failed: " + error);
                    return;
                }
                param.setResult(Boolean.TRUE);
                Object name = safeCall(packageState, "getPackageName");
                HookLog.once("vendor_sig_" + name, "vendor platform signature granted for " + name);
            }
        });
        HookLog.info("vendor platform signature hook: " + hooked + " method(s)");
    }

    private static boolean isPlatformPermission(Object permission) {
        Object info = Reflect.call(permission, "getPermissionInfo");
        return info instanceof PermissionInfo && "android".equals(((PermissionInfo) info).packageName);
    }

    private static boolean isVendorPackage(Object packageState) {
        return Boolean.TRUE.equals(safeCall(packageState, "isVendor"))
                || Boolean.TRUE.equals(safeCall(packageState, "isOdm"));
    }

    private static boolean isRealmePlatformSigned(Object packageState) throws Exception {
        Object pkg = Reflect.call(packageState, "getAndroidPackage");
        if (pkg == null) {
            return false;
        }
        Object details = Reflect.call(pkg, "getSigningDetails");
        Object signatures = details == null ? null : Reflect.call(details, "getSignatures");
        if (!(signatures instanceof Signature[]) || ((Signature[]) signatures).length != 1) {
            return false;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(((Signature[]) signatures)[0].toByteArray());
        return Arrays.equals(digest, REALME_PLATFORM_CERT_SHA256);
    }

    private static Object safeCall(Object target, String method) {
        try {
            return Reflect.call(target, method);
        } catch (Throwable error) {
            return null;
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

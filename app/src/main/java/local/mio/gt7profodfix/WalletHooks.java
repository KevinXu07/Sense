package local.mio.gt7profodfix;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodReplacement;

/**
 * Replace SystemUI's Quick Access Wallet gesture launch with FinShell's
 * dedicated NFC swipe page.
 *
 * PowerKeyRule / CentralSurfaces already own the double-click timing and the
 * single-press suppression. The only failing link is that FinShell does not
 * implement QuickAccessWalletService, so startGestureUiIntent() asks the QAW
 * client for a pending intent and gets nothing (and the ROM shows
 * "current default wallet doesn't support this gesture"). This hook does not
 * touch the power key stream, so it cannot lock the screen.
 */
final class WalletHooks {
    private static final String WALLET_PACKAGE = "com.finshell.wallet";
    private static final String WALLET_SWIPE_CLASS =
            "com.nearme.wallet.nfc.ui.NfcConsumeActivity";
    private static final String WALLET_OPEN_ACTION =
            "finshell.wallet.intent.action.OPEN";
    private static final String LOCKSCREEN_SESSION_EXTRA =
            "local.mio.coloroswalletcompat.extra.LOCKSCREEN_SESSION";

    private WalletHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> controller = Reflect.findClass(loader,
                "com.android.systemui.wallet.controller.QuickAccessWalletController");
        if (controller == null) {
            HookLog.once("wallet_controller_missing",
                    "QuickAccessWalletController missing; wallet gesture hook skipped");
            return;
        }
        Method gesture = Reflect.findCompatibleMethod(
                controller, "startGestureUiIntent", 1);
        if (gesture == null) {
            HookLog.once("wallet_gesture_missing",
                    "startGestureUiIntent missing; wallet gesture hook skipped");
            return;
        }
        Reflect.hookMethodOnce(gesture, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                Object contextObject = Reflect.get(param.thisObject, "mContext");
                if (!(contextObject instanceof Context)) {
                    HookLog.once("wallet_gesture_context_missing",
                            "wallet gesture context missing");
                    return null;
                }
                launchSwipePage((Context) contextObject);
                return null;
            }
        });
        HookLog.info("wallet gesture -> FinShell swipe hook installed");
    }

    private static void launchSwipePage(Context context) {
        try {
            context.getPackageManager().getPackageInfo(WALLET_PACKAGE, 0);
            Intent intent = new Intent(WALLET_OPEN_ACTION);
            intent.setComponent(new ComponentName(
                    WALLET_PACKAGE, WALLET_SWIPE_CLASS));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | 0x00080000);
            intent.putExtra(LOCKSCREEN_SESSION_EXTRA, true);
            context.startActivity(intent);
            HookLog.info("wallet gesture -> " + WALLET_PACKAGE + "/"
                    + WALLET_SWIPE_CLASS);
        } catch (Throwable error) {
            HookLog.error("wallet gesture launch failed", error);
        }
    }
}

package local.mio.coloroswalletcompat

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * HyperOS Greeze freezes the user-installed ColorOS wallet almost as soon as
 * it leaves the foreground (`dumpsys greezer`: frozen/bg rate 1.00). NFC
 * broadcasts sent to it while frozen are parked in
 * GreezeManagerService.mCachedBCList and replayed on whatever thaw comes next,
 * so the swipe/transaction page appears long after the reader is gone. A
 * process started for such a broadcast also forks into the frozen uid cgroup
 * and stalls until the next thaw.
 *
 * ColorOS keeps the wallet out of its freezer. Reuse HyperOS's own "no
 * restriction" app predicate (the one behind the battery "No restrictions"
 * choice, MILLET_NO_RESTRICT_APP) for the wallet package only, so Greeze's
 * filter treats it like any other unrestricted app. Android's own cached-app
 * freezer and LMK still apply.
 */
internal object WalletFreezeExemption {
    private const val TAG = "ColorOSWalletCompat"
    private const val IMMOBULUS_MODE = "com.miui.server.greeze.AurogonImmobulusMode"
    private val EXEMPT_PACKAGES = setOf("com.finshell.wallet")

    @Volatile
    private var installed = false

    fun install(classLoader: ClassLoader) {
        if (installed) return
        runCatching {
            val mode = XposedHelpers.findClass(IMMOBULUS_MODE, classLoader)
            XposedHelpers.findAndHookMethod(
                mode,
                "isNoRestrictApp",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] in EXEMPT_PACKAGES) param.result = true
                    }
                },
            )
            // The stock predicate still lets night deep-idle freeze
            // unrestricted apps; a wallet must answer a reader at any hour.
            XposedHelpers.findAndHookMethod(
                mode,
                "isNoRestrictFreezeable",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] in EXEMPT_PACKAGES) param.result = false
                    }
                },
            )
            installed = true
            XposedBridge.log("$TAG: Greeze wallet freeze exemption installed")
        }.onFailure {
            XposedBridge.log("$TAG: Greeze wallet freeze exemption unavailable")
            XposedBridge.log(it)
        }
    }
}

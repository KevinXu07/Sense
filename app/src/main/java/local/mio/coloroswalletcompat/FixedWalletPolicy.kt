package local.mio.coloroswalletcompat

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executor
import java.util.function.Consumer

/** The former companion's OPPO policy, owned by NfcService instead of a shell. */
internal class FixedWalletPolicy(
    private val context: Context,
    private val handler: Handler,
    private val service: Any,
    private val onRoutingChanged: (String) -> Unit,
) {
    companion object {
        const val WALLET = "com.finshell.wallet"
        const val CARD_SERVICE = "com.nearme.wallet.nfc.CardService"
        const val COMPONENT = "$WALLET/$CARD_SERVICE"
        const val ROLE = "android.app.role.WALLET"
        const val EVENT_PERMISSION = "oplus.permission.OPLUS_COMPONENT_SAFE"
        const val ENABLED = "coloros_wallet_compat_enabled"
        const val DEFAULT = "nfc_payment_default_component"
        const val FOREGROUND = "nfc_payment_foreground"
        const val ACTIVE = "nfc_multise_active"
        const val ROUTES = "nfc_multise_list"
        const val ESE = "Embedded SE"
        const val TAG = "ColorOSWalletCompat"
        private const val MAX_CORRECTIONS = 6
        private const val CORRECTION_WINDOW_MS = 15_000L

        // Exactly the companion's extended mode. Unlisted apps retain the
        // system default. This map controls tag dispatch, never HCE services.
        val TAG_PREFERENCES = mapOf(
            WALLET to true,
            "com.eg.android.AlipayGphone" to true,
            "com.tencent.mm" to true,
            "cmb.pb" to true,
            "com.chinamobile.mcloud" to true,
            "com.greenpoint.android.mc10086.activity" to true,
            "com.miui.tsmclient" to false,
            "mark.via" to false,
            "com.heytap.tas" to false,
            "com.xiaomi.market" to false,
            "com.yuanwofei.cardemulator.pro" to false,
            "com.android.apps.tag" to false,
        )
        val WALLET_PACKAGES = setOf(
            WALLET, "com.heytap.tas", "com.unionpay.tsmservice",
            "com.heytap.htms", "com.oplus.eid",
        )
    }

    private var userContext: Context? = null
    private var pendingReason = "start"
    private var roleInFlight = false
    private var roleReplayPending = false
    private var roleAttempts = 0
    private var finishedLogged = false
    private var correctionsPaused = false
    private val correctionTimes = ArrayDeque<Long>()
    private val errors = mutableSetOf<String>()
    private val executor = Executor { handler.post(it) }
    private val reconcileTask = Runnable {
        if (reconcile(pendingReason)) onRoutingChanged("wallet_policy")
    }
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            schedule("settings_changed")
        }
    }

    fun install() {
        // The existing card bridge is owner-user scoped. Do not copy owner
        // defaults or cards into a guest/work/clone profile.
        userContext = XposedHelpers.callMethod(
            context, "createContextAsUser", owner(), 0,
        ) as Context
        val resolver = userContext!!.contentResolver
        listOf(ENABLED, ACTIVE, ROUTES).forEach {
            resolver.registerContentObserver(Settings.Global.getUriFor(it), false, settingsObserver)
        }
        listOf(DEFAULT, FOREGROUND).forEach {
            resolver.registerContentObserver(Settings.Secure.getUriFor(it), false, settingsObserver)
        }

        val lifecycle = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_UNLOCKED ||
                    intent.action == Intent.ACTION_BOOT_COMPLETED
                ) resetCorrectionBudget()
                schedule("${intent.action}")
            }
        }
        context.registerReceiver(lifecycle, IntentFilter().apply {
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }, null, handler, Context.RECEIVER_EXPORTED)
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (pkg in TAG_PREFERENCES || pkg in WALLET_PACKAGES) {
                    if (intent.action == Intent.ACTION_PACKAGE_ADDED ||
                        intent.action == Intent.ACTION_PACKAGE_REPLACED
                    ) resetCorrectionBudget()
                    schedule("package_changed:$pkg")
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }, null, handler, Context.RECEIVER_EXPORTED)

        // These callbacks occur after PM scanning, wallet role updates and
        // user switches. Post to the NFC handler; never re-enter its cache lock.
        step("service_events") {
            val manager = manager() ?: return@step
            listOf("onServicesUpdated", "onWalletRoleHolderChanged", "onUserSwitched").forEach { name ->
                XposedBridge.hookAllMethods(manager.javaClass, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        schedule(name)
                    }
                })
            }
        }
        step("tag_events") {
            val adapter = XposedHelpers.getObjectField(service, "mNfcAdapter")
            XposedBridge.hookAllMethods(adapter.javaClass, "setTagIntentAppPreferenceForUser", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable() || param.result != 0) return
                    if (param.args.getOrNull(0) == 0 && param.args.getOrNull(1) in TAG_PREFERENCES) {
                        schedule("tag_preference_changed")
                    }
                }
            })
        }
        schedule("install")
        // A finite boot settling check also covers wallet roles created late.
        handler.postDelayed({ schedule("boot_settled") }, 20_000L)
        XposedBridge.log("$TAG: fixed OPPO wallet policy installed (event-driven, no Action)")
    }

    private fun schedule(reason: String) {
        if (correctionsPaused) return
        pendingReason = reason
        handler.removeCallbacks(reconcileTask)
        handler.postDelayed(reconcileTask, 350L)
    }

    /** True only if changes require the existing card/routing bridge to replay. */
    fun reconcile(reason: String): Boolean {
        val ownerContext = userContext ?: return false
        if (reason == "adapter_on" || reason == "service_start") resetCorrectionBudget()
        if (correctionsPaused) return false
        val identity = Binder.clearCallingIdentity()
        try {
            if (!isOwnerActive() || !walletInstalled(ownerContext)) return false
            var changed = roleReplayPending
            var routingChanged = roleReplayPending
            roleReplayPending = false
            val resolver = ownerContext.contentResolver
            step("settings") {
                mapOf(ENABLED to "1", ACTIVE to ESE, ROUTES to ESE).forEach { (key, value) ->
                    if (Settings.Global.getString(resolver, key) != value) {
                        check(Settings.Global.putString(resolver, key, value)) { "write $key failed" }
                        changed = true
                        routingChanged = true
                    }
                }
                // The companion uses Global only. A17 rejects this custom key
                // in Settings.System; wallet System readers already have the
                // process-local multise fallback in HookEntry.
                if (Settings.Secure.getString(resolver, FOREGROUND) != "1") {
                    check(Settings.Secure.putInt(resolver, FOREGROUND, 1))
                    changed = true
                }
            }
            step("offhost") {
                val manager = manager() ?: return@step
                val cache = XposedHelpers.getObjectField(manager, "mServiceCache")
                for (pkg in listOf(WALLET, "com.heytap.tas")) {
                    val component = ComponentName(pkg, CARD_SERVICE)
                    val info = XposedHelpers.callMethod(cache, "getService", 0, component) ?: continue
                    if (XposedHelpers.callMethod(info, "isOnHost") == true) continue
                    if (XposedHelpers.callMethod(info, "getOffHostSecureElement") != "eSE1") {
                        // A17 inlines the cache setter into the Binder facade.
                        // The NFC UID is privileged in NfcInjector; keep all of
                        // the facade's user/service/ownership checks intact.
                        val api = XposedHelpers.getObjectField(manager, "mCardEmulationInterface")
                        check(XposedHelpers.callMethod(
                            api, "setOffHostForService", 0, component, "eSE1",
                        ) == true)
                        changed = true
                        routingChanged = true
                    }
                }
            }
            step("payment") {
                if (Settings.Secure.getString(resolver, DEFAULT) != COMPONENT) {
                    manager()?.let { manager ->
                        XposedHelpers.callMethod(manager, "setDefaultServiceForCategoryChecked",
                            0, ComponentName(WALLET, CARD_SERVICE), "payment")
                    }
                    if (Settings.Secure.getString(resolver, DEFAULT) != COMPONENT) {
                        check(Settings.Secure.putString(resolver, DEFAULT, COMPONENT))
                    }
                    changed = true
                    routingChanged = true
                }
            }
            step("wallet_role") { reconcileRole(ownerContext, reason) }
            step("tag_preferences") {
                val adapter = XposedHelpers.getObjectField(service, "mNfcAdapter")
                val current = XposedHelpers.callMethod(
                    adapter, "getTagIntentAppPreferenceForUser", 0,
                ) as Map<*, *>
                TAG_PREFERENCES.forEach { (pkg, allowed) ->
                    if (current[pkg] != allowed && packageInstalled(ownerContext, pkg)) {
                        check(XposedHelpers.callMethod(adapter,
                            "setTagIntentAppPreferenceForUser", 0, pkg, allowed) == 0)
                        changed = true
                    }
                }
            }
            if (changed || !finishedLogged) {
                finishedLogged = true
                XposedBridge.log("$TAG: fixed wallet policy reconciled reason=$reason changed=$changed")
            }
            if (changed) recordCorrection()
            // Tag preference repairs need only the stock preference setter;
            // never reset RF discovery just because an Alipay/WeChat tag
            // preference changed during a foreground Tap session.
            return routingChanged
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun resetCorrectionBudget() {
        correctionsPaused = false
        correctionTimes.clear()
        roleAttempts = 0
    }

    private fun recordCorrection() {
        val now = SystemClock.uptimeMillis()
        while (correctionTimes.isNotEmpty() && now - correctionTimes.first() > CORRECTION_WINDOW_MS) {
            correctionTimes.removeFirst()
        }
        correctionTimes.addLast(now)
        if (correctionTimes.size >= MAX_CORRECTIONS) {
            correctionsPaused = true
            handler.removeCallbacks(reconcileTask)
            XposedBridge.log("$TAG: repeated wallet policy conflict; corrections paused until unlock, NFC ON or package update")
            // No delayed reset or periodic retry. Another writer cannot keep
            // waking this policy forever by fighting the same Settings values.
        }
    }

    private fun reconcileRole(ownerContext: Context, reason: String) {
        val roles = ownerContext.getSystemService("role") ?: return
        if (XposedHelpers.callMethod(roles, "isRoleAvailable", ROLE) != true) return
        val holders = XposedHelpers.callMethod(roles, "getRoleHoldersAsUser", ROLE, owner()) as List<*>
        if (holders == listOf(WALLET)) {
            roleAttempts = 0
            return
        }
        if (roleInFlight || roleAttempts >= 3) return
        if (ownerContext.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        roleAttempts++
        roleInFlight = true
        try {
            // Single-holder role replacement: never clear first, which produces
            // a transient no-wallet state and can tear down foreground HCE.
            XposedHelpers.callMethod(roles, "addRoleHolderAsUser", ROLE, WALLET, 0, owner(),
                executor, Consumer<Boolean> { accepted ->
                    roleInFlight = false
                    roleReplayPending = accepted
                    XposedBridge.log("$TAG: wallet role request accepted=$accepted reason=$reason")
                    schedule("wallet_role_result")
                })
        } catch (error: Throwable) {
            roleInFlight = false
            throw error
        }
    }

    private fun manager(): Any? = runCatching {
        XposedHelpers.getObjectField(service, "mCardEmulationManager")
    }.getOrNull()

    private fun owner(): UserHandle = XposedHelpers.callStaticMethod(
        UserHandle::class.java, "of", 0,
    ) as UserHandle

    private fun isOwnerActive(): Boolean = runCatching {
        (XposedHelpers.callMethod(service, "getUserId") as Number).toInt() == 0
    }.getOrDefault(false)

    private fun walletInstalled(context: Context): Boolean = runCatching {
        val info = context.packageManager.getServiceInfo(ComponentName(WALLET, CARD_SERVICE), 0)
        info.enabled && info.applicationInfo.enabled
    }.getOrDefault(false)

    private fun packageInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    }.getOrDefault(false)

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
            errors.remove(name)
        } catch (error: Throwable) {
            if (errors.add(name)) {
                XposedBridge.log("$TAG: fixed wallet policy $name unavailable: $error")
            }
        }
    }
}

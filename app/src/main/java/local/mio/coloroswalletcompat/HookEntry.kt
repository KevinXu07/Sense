package local.mio.coloroswalletcompat

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.nfc.NfcAdapter
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.security.MessageDigest
import java.util.HashMap
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Compatibility layer for the stock ColorOS wallet packages on a OnePlus 13
 * running a transplanted system image.
 *
 * This deliberately changes only public software identity values visible to
 * the three wallet processes. It does not modify attestation properties,
 * secure-element identifiers, CPLC, certificates, TEE data, or device keys.
 */
class HookEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {
    private companion object {
        const val TAG = "ColorOSWalletCompat"
        const val NFC_PACKAGE = "com.android.nfc"
        const val FIN_SHELL_PACKAGE = "com.finshell.wallet"
        const val OPLUS_EID_PACKAGE = "com.oplus.eid"
        const val OPLUS_EID_HAL_SERVICE =
            "vendor.oplus.hardware.eid.IEidDevice/default"
        const val FIN_SHELL_CARD_SERVICE =
            "com.nearme.wallet.nfc.CardService"
        const val FIN_SHELL_PAYMENT_COMPONENT =
            "$FIN_SHELL_PACKAGE/$FIN_SHELL_CARD_SERVICE"
        const val EMBEDDED_SE_ROUTE = "Embedded SE"
        const val OFF_HOST_ESE_NAME = "eSE1"
        const val NFC_PAYMENT_DEFAULT_COMPONENT =
            "nfc_payment_default_component"
        const val NFC_PAYMENT_FOREGROUND = "nfc_payment_foreground"
        const val NFC_MULTISE_ACTIVE = "nfc_multise_active"
        const val NFC_MULTISE_LIST = "nfc_multise_list"
        const val MISSING_NFC_PAYMENT_FRAGMENT =
            "com.android.settings.nfc.PaymentSettings"
        const val WORKING_NFC_PAYMENT_FRAGMENT =
            "com.android.settings.nfc.DefaultPaymentSettings"
        const val ONEPLUS13_MODEL = "PJZ110"
        const val ONEPLUS13_OFFICIAL_OTA =
            "PJZ110_11.C.92_1920_202607071800"
        const val TRANSIT_REQUEST_PATH = "nfc/transit/"
        const val OPLUS_FRAMEWORK_CALLBACK_CLASS =
            "com.oplus.osense.task.BgRunningCallback"
        const val EMBEDDED_OPLUS_FRAMEWORK_ASSET =
            "assets/oplus-fwk.jar"
        const val EMBEDDED_OPLUS_FRAMEWORK_SHA256 =
            "b56f6e6d682d78e9f7995eae854122c49a6174fd1822497b6922cc674a2d0d46"
        const val FIN_SHELL_EVENT_PERMISSION =
            "oplus.permission.OPLUS_COMPONENT_SAFE"

        const val ACTION_UPDATE_CITY_CONFIG =
            "com.nfc.action.UPDATE_CITY_CONFIG"
        const val ACTION_ENTER_SWIPE_PAGE =
            "com.nfc.action.ENTER_SWIPE_PAGE"
        const val ACTION_UPDATE_SMART_CARD_MANAGER =
            "com.nfc.action.UPDATE_SMART_CARD_MANAGER"
        const val ACTION_RECONCILE_NFC =
            "local.mio.coloroswalletcompat.action.RECONCILE_NFC"

        const val ACCESS_CARD_TYPE = "accesscard"
        const val EID_CARD_TYPE = "eidcard"
        const val ACCESS_CARD_AID_PREFIX =
            "A0000003964D344D1061707056434D"
        const val NFC_BRIDGE_PREFS = "coloros_wallet_compat_nfc"
        const val NFC_BRIDGE_TIMEOUT_MS = 10 * 60 * 1000L
        const val NXP_TRANSIT_SERVICE =
            "vendor.nxp.nxpnfc_aidl.INxpNfc/default"
        const val NXP_TRANSIT_DESCRIPTOR =
            "vendor.nxp.nxpnfc_aidl.INxpNfc"
        const val NXP_SET_TRANSIT_CONFIG_TRANSACTION = 3
        const val STALE_XIAOMI_ESE_ROUTE = 0x01

        /**
         * The OnePlus 13 SN220T reports its active eSE as NFCEE 0xC0
         * (NFA handle 0x04C0). The transplanted Xiaomi NFC package reads
         * OFFHOST_ROUTE_ESE={01} from its own configuration and consequently
         * addresses the non-existent handle 0x0401. Xiaomi's JNI silently
         * falls that route back to DH. Keep this correction process-local;
         * never rewrite the donor ROM's persistent NFC configuration.
         */
        const val ONEPLUS13_ESE_NFCEE_ID = 0xC0

        @Volatile
        var nfcBridgeInstalled = false

        @Volatile
        var routingManagerRef: Any? = null

        @Volatile
        var aidRoutingManagerRef: Any? = null

        @Volatile
        var cardEmulationManagerRef: Any? = null

        @Volatile
        var routingContextRef: Context? = null

        @Volatile
        var routingCompatEnabled: Boolean? = null

        @Volatile
        var routingOriginalIsoDep: Int? = null

        @Volatile
        var routingOriginalOffHost: Int? = null

        @Volatile
        var routingOriginalFelica: Int? = null

        @Volatile
        var routingEse: Int? = null

        @Volatile
        var moduleApkPath: String? = null

        @Volatile
        var oplusFrameworkAttachHookInstalled = false

        @Volatile
        var finShellUnfreezeHookInstalled = false

        val TARGETS = setOf(
            FIN_SHELL_PACKAGE,
            "com.heytap.tas",
            "com.unionpay.tsmservice",
        )

        val OFF_HOST_WALLET_PACKAGES = setOf(
            FIN_SHELL_PACKAGE,
            "com.heytap.tas",
        )

        const val SECURE_ELEMENT_PACKAGE = "com.android.se"
        const val MIUI_TSM_PACKAGE = "com.miui.tsmclient"
        const val MIUI_TRANSIT_SHORTCUT =
            "com.miui.tsmclient.ui.quick.DoubleClickActivity"
        const val MIUI_DOUBLE_CLICK_ACTION =
            "com.miui.intent.action.DOUBLE_CLICK"
        const val MIUI_EVENT_SOURCE_TYPE = "event_source_type"
        val MIUI_AUTOMATIC_NFC_EVENT_TYPES = setOf(
            "com.miui.nfc.action.RF_ON",
            "com.miui.nfc.action.TRANSACTION",
            "com.miui.nfc.action.SMART_SELECT_CARD",
        )
        const val FIN_SHELL_CARD_PACKAGE =
            "com.nearme.pay.business.cardpackage.ui.CardPackageActivity"
        const val FIN_SHELL_LOCKSCREEN_SESSION =
            "local.mio.coloroswalletcompat.extra.LOCKSCREEN_SESSION"
        const val LOCKSCREEN_WALLET_SESSION_MS = 60_000L

        @Volatile
        var lockscreenWalletSessionUntil = 0L

        // These are the original signer certificates shipped in the ColorOS
        // donor. Package name and certificate must both match.
        val ESE_ALLOWED_SIGNERS = mapOf(
            "com.finshell.wallet" to setOf(
                "7f68e808c85fc6f97f63197aad345a904242145ef4499310f90f8d0653e855be",
            ),
            "com.heytap.tas" to setOf(
                "bdab0168db8aae8dd157d975ee63c129756d923b9bb64120086f78db26b9c6d2",
            ),
        )

        val BUILD_FIELDS = mapOf(
            "BRAND" to "OnePlus",
            "MANUFACTURER" to "OnePlus",
            "MODEL" to ONEPLUS13_MODEL,
            "DEVICE" to "OP5D0DL1",
            "PRODUCT" to ONEPLUS13_MODEL,
        )

        val PROPERTY_VALUES = mapOf(
            "ro.product.brand" to "OnePlus",
            "ro.product.manufacturer" to "OnePlus",
            "ro.product.model" to ONEPLUS13_MODEL,
            "ro.product.device" to "OP5D0DL1",
            "ro.product.name" to ONEPLUS13_MODEL,
            "ro.build.product" to "OP5D0DL1",
            "ro.product.brand.sub" to "OnePlus",
            "ro.product.marketname" to "OnePlus 13",
            "ro.vendor.oplus.market.name" to "OnePlus 13",
            "ro.vendor.oplus.market.enname" to "OnePlus 13",
            "ro.boot.prjname" to "23821",
            "ro.build.version.oplusrom" to "V16.1.0",
            "ro.build.version.oplusrom.display" to "16.1",
            "ro.build.version.ota" to ONEPLUS13_OFFICIAL_OTA,
            "persist.sys.pkwallet.support" to "true",
            "persist.sys.oplus.region" to "CN",
            "persist.sys.oppo.region" to "CN",
            "ro.vendor.oplus.regionmark" to "CN",
            // Match FinShell's built-in OnePlus ESE carrier descriptor exactly.
            // The local gate only requires the value to contain Build.BRAND;
            // adding an OPPO prefix shifts the fixed-position TSM fields.
            "ro.product.cuptsm" to "ONEPLUS|ESE|01|02",
        )
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        moduleApkPath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName == "android" && lpparam.processName == "android") {
            WalletRuntimePermissions.installSystemHooks(lpparam.classLoader)
            return
        }
        if (lpparam.packageName == OPLUS_EID_PACKAGE) {
            hookOplusEidDeclaredServiceCompat()
            return
        }
        if (lpparam.packageName == NFC_PACKAGE) {
            hookOfficialColorOsRoutingDefaults(lpparam.classLoader)
            hookOffHostSecureElementResolution(lpparam.classLoader)
            hookNfcTechChooserCompat(lpparam.classLoader)
            hookColorOsAccessCardBridge(lpparam.classLoader)
            return
        }
        if (lpparam.packageName == "com.android.settings") {
            hookNfcPaymentSettingsFragmentCompatibility(lpparam.classLoader)
            WalletRuntimePermissions.installSettingsFallback()
            return
        }
        if (lpparam.packageName == SECURE_ELEMENT_PACKAGE) {
            hookSecureElementOplusAccessFallback(lpparam.classLoader)
            return
        }
        if (lpparam.packageName == MIUI_TSM_PACKAGE) {
            hookMiuiAutomaticNfcActivityLaunch()
            hookMiuiTransitShortcut(lpparam.classLoader)
            return
        }
        if (lpparam.packageName !in TARGETS) return

        XposedBridge.log("$TAG: applying to ${lpparam.packageName}/${lpparam.processName}")
        spoofBuildFields()
        hookSystemProperties()
        runCatching {
            WalletCapabilityCompat.install(lpparam.classLoader, lpparam.packageName)
        }.onFailure {
            XposedBridge.log("$TAG: wallet capability compatibility unavailable: $it")
        }

        if (lpparam.packageName == FIN_SHELL_PACKAGE ||
            lpparam.packageName == "com.heytap.tas"
        ) {
            /*
             * FinShell and TAS bundle separate copies of the same vendor,
             * eID and NFC compatibility classes. The old smali payload
             * patched both APKs, so install the equivalent hooks in both
             * processes as well.
             */
            hookEidServiceSelection(lpparam.classLoader)
            hookFinShellLocalVendorGate(lpparam)
            hookFinShellNfcEnvironment(lpparam.classLoader)
            hookFinShellAccessCardTuningGate(lpparam.classLoader)
        }

        if (lpparam.packageName == FIN_SHELL_PACKAGE) {
            makeOplusFrameworkVisible(lpparam.classLoader)
            WalletCapabilityCompat.installOplusFeatures(lpparam.classLoader)
            // Only FinShell carried the old OSense-unfreeze smali patch.
            hookFinShellUnfreezeCompat(lpparam.classLoader)
            hookFinShellLockscreenWalletSession()
            hookFinShellTransitRequestIdentity(lpparam.classLoader)
        }
    }

    /**
     * HyperOS 4 / Android 17 rejects IServiceManager.isDeclared() from the
     * transplanted OPlus eID bridge even though the eID HAL is registered and
     * the same domain has the normal service_manager find permission. The
     * bridge consequently never reaches waitForService() and reports state 47.
     *
     * Bypass only that redundant declaration probe, only in com.oplus.eid and
     * only for the physical OPlus eID HAL. The following waitForService() and
     * Binder calls are untouched, so an absent or inaccessible HAL still fails
     * normally instead of being emulated.
     */
    private fun hookOplusEidDeclaredServiceCompat() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.os.ServiceManager",
                null,
                "isDeclared",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.firstOrNull() == OPLUS_EID_HAL_SERVICE) {
                            param.result = true
                        }
                    }
                },
            )
            XposedBridge.log(
                "$TAG: OPlus eID Android 17 declaration compat installed",
            )
        }.onFailure {
            XposedBridge.log("$TAG: OPlus eID declaration compat unavailable")
            XposedBridge.log(it)
        }
    }

    /**
     * Xiaomi persists default protocol/technology routes in
     * RoutingOptionPrefs. Those values override the NXP configuration files
     * and can survive a ROM transplant, so a stale DH/UICC choice wins even
     * after the SN220 defaults have been restored.
     *
     * ColorOS resolves the eSE NFCEE from RoutingOptionManager and uses it as
     * the ISO-DEP and A/B technology default. Recreate that decision in
     * memory, before CardEmulationManager consumes the values. Do not delete
     * or rewrite another ROM's preferences: system mode can therefore return
     * to the exact routes that were present when NfcService started.
     */
    private fun hookOfficialColorOsRoutingDefaults(classLoader: ClassLoader) {
        runCatching {
            val routingClass = XposedHelpers.findClass(
                "com.android.nfc.cardemulation.RoutingOptionManager",
                classLoader,
            )
            val aidRoutingClass = XposedHelpers.findClass(
                "com.android.nfc.cardemulation.AidRoutingManager",
                classLoader,
            )
            val cardEmulationClass = XposedHelpers.findClass(
                "com.android.nfc.cardemulation.CardEmulationManager",
                classLoader,
            )
            val deviceConfigClass = XposedHelpers.findClass(
                "com.android.nfc.DeviceConfigFacade",
                classLoader,
            )

            /*
             * Install these hooks before RoutingOptionManager.getInstance()
             * constructs the singleton. This makes createLookUpTable() build
             * the correct eSE1 <-> 0xC0 mapping on its first pass, and also
             * fixes every later "-2 / restore native default" read.
             */
            XposedBridge.hookAllMethods(
                routingClass,
                "doGetOffHostEseDestination",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val routes = param.result as? ByteArray
                        if (routes == null || routes.isEmpty()) {
                            param.result =
                                byteArrayOf(ONEPLUS13_ESE_NFCEE_ID.toByte())
                            return
                        }
                        routes.indices.forEach { index ->
                            if (
                                routes[index].toInt() and 0xFF ==
                                STALE_XIAOMI_ESE_ROUTE
                            ) {
                                routes[index] =
                                    ONEPLUS13_ESE_NFCEE_ID.toByte()
                            }
                        }
                    }
                },
            )
            listOf(
                "doGetDefaultRouteDestination",
                "doGetDefaultIsoDepRouteDestination",
                "doGetDefaultOffHostRouteDestination",
                "doGetDefaultFelicaRouteDestination",
                "doGetDefaultScRouteDestination",
            ).forEach { methodName ->
                XposedBridge.hookAllMethods(
                    routingClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(
                            param: MethodHookParam,
                        ) {
                            val nativeRoute =
                                (param.result as? Number)?.toInt() ?: return
                            synchronized(HookEntry::class.java) {
                                when (methodName) {
                                    "doGetDefaultIsoDepRouteDestination" ->
                                        if (routingOriginalIsoDep == null) {
                                            routingOriginalIsoDep = nativeRoute
                                        }

                                    "doGetDefaultOffHostRouteDestination" ->
                                        if (routingOriginalOffHost == null) {
                                            routingOriginalOffHost = nativeRoute
                                        }

                                    "doGetDefaultFelicaRouteDestination" ->
                                        if (routingOriginalFelica == null) {
                                            routingOriginalFelica = nativeRoute
                                        }
                                }
                            }
                            if (nativeRoute == STALE_XIAOMI_ESE_ROUTE) {
                                param.result = ONEPLUS13_ESE_NFCEE_ID
                            }
                        }
                    },
                )
            }

            val legacyPrefsHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        /*
                         * If the singleton was initialized unusually early,
                         * repair its identity before preference strings such
                         * as "eSE1" are converted back into numeric routes.
                         */
                        patchOfficialEseIdentity(param.thisObject)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as? Context ?: return
                        val manager = param.thisObject
                        patchOfficialEseIdentity(manager)
                        val eseRoute = (
                            XposedHelpers.callMethod(
                                manager,
                                "getRouteForSecureElement",
                                "eSE1",
                            ) as? Number
                            )?.toInt() ?: return
                        if (eseRoute <= 0) {
                            XposedBridge.log(
                                "$TAG: eSE1 route unavailable; " +
                                    "leaving platform routing unchanged",
                            )
                            return
                        }

                        synchronized(HookEntry::class.java) {
                            routingManagerRef = manager
                            routingEse = eseRoute
                            routingContextRef = context
                        }

                        val compatEnabled = true
                        routingCompatEnabled = compatEnabled
                        patchOfficialRoutingObjects(compatEnabled)

                        XposedBridge.log(
                            "$TAG: routing defaults captured " +
                                "iso=${routingOriginalIsoDep}, " +
                                "offhost=${routingOriginalOffHost}, " +
                                "felica=${routingOriginalFelica}, " +
                                "eSE=$eseRoute",
                        )
                    }
                }
            hookExactMethodIfPresent(
                routingClass,
                "readRoutingOptionsFromPrefs",
                arrayOf(Context::class.java, deviceConfigClass),
                legacyPrefsHook,
            )

            /*
             * Android 17 moved preference loading into CardEmulationManager
             * and removed readRoutingOptionsFromPrefs/getDefault* helpers.
             * Capture the singleton at construction time as a version-neutral
             * fallback. The native doGet* hooks above preserve the exact
             * pre-compat routes before any stale 0x01 translation occurs.
             */
            XposedBridge.hookAllConstructors(
                routingClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val manager = param.thisObject
                        synchronized(HookEntry::class.java) {
                            routingManagerRef = manager
                            routingEse = ONEPLUS13_ESE_NFCEE_ID
                            if (routingOriginalIsoDep == null) {
                                routingOriginalIsoDep = XposedHelpers.getIntField(
                                    manager,
                                    "mDefaultIsoDepRoute",
                                )
                            }
                            if (routingOriginalOffHost == null) {
                                routingOriginalOffHost = XposedHelpers.getIntField(
                                    manager,
                                    "mDefaultOffHostRoute",
                                )
                            }
                            if (routingOriginalFelica == null) {
                                routingOriginalFelica = XposedHelpers.getIntField(
                                    manager,
                                    "mDefaultFelicaRoute",
                                )
                            }
                            routingCompatEnabled = true
                        }
                        patchOfficialEseIdentity(manager)
                        patchOfficialRoutingObjects(true)
                        XposedBridge.log(
                            "$TAG: routing singleton captured " +
                                "iso=$routingOriginalIsoDep, " +
                                "offhost=$routingOriginalOffHost, " +
                                "felica=$routingOriginalFelica, " +
                                "eSE=$ONEPLUS13_ESE_NFCEE_ID",
                        )
                    }
                },
            )

            val defaultRouteResultHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (routingCompatEnabled != true) return
                    val eseRoute = routingEse ?: return
                    if (eseRoute > 0) {
                        param.result = eseRoute
                    }
                }
            }
            hookExactMethodIfPresent(
                routingClass,
                "getDefaultIsoDepRoute",
                emptyArray(),
                defaultRouteResultHook,
            )
            hookExactMethodIfPresent(
                routingClass,
                "getDefaultOffHostRoute",
                emptyArray(),
                defaultRouteResultHook,
            )

            XposedBridge.hookAllMethods(
                routingClass,
                "recoverOverridedRoutingTable",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        refreshOfficialRoutingMode()
                        patchOfficialRoutingObjects(
                            routingCompatEnabled == true,
                        )
                    }
                },
            )
            XposedBridge.hookAllMethods(
                routingClass,
                "overwriteRoutingTable",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        refreshOfficialRoutingMode()
                        patchOfficialRoutingObjects(
                            routingCompatEnabled == true,
                        )
                    }
                },
            )

            XposedBridge.hookAllConstructors(
                cardEmulationClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        cardEmulationManagerRef = param.thisObject
                        (param.args.firstOrNull { it is Context } as? Context)?.let {
                            routingContextRef = it
                            refreshOfficialRoutingMode()
                        }
                        patchCachedEseRouteArray(param.thisObject)
                        patchOfficialRoutingObjects(
                            routingCompatEnabled == true,
                        )
                    }
                },
            )

            XposedBridge.hookAllConstructors(
                aidRoutingClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        aidRoutingManagerRef = param.thisObject
                        patchCachedEseRouteArray(param.thisObject)
                        refreshOfficialRoutingMode()
                        patchOfficialRoutingObjects(
                            routingCompatEnabled == true,
                        )
                        XposedBridge.log(
                            "$TAG: AidRoutingManager follows live ColorOS route",
                        )
                    }
                },
            )
            XposedBridge.hookAllMethods(
                aidRoutingClass,
                "configureRouting",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Multiple managers can be constructed by the
                        // platform injector. Always patch the instance that
                        // is actually rebuilding the live table.
                        aidRoutingManagerRef = param.thisObject
                        refreshOfficialRoutingMode()
                        patchOfficialRoutingObjects(
                            routingCompatEnabled == true,
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        patchOfficialRoutingObjects(
                            routingCompatEnabled == true,
                        )
                    }
                },
            )

            XposedBridge.log(
                "$TAG: official ColorOS eSE routing-default hook installed",
            )
        }.onFailure {
            XposedBridge.log("$TAG: ColorOS eSE routing-default hook unavailable")
            XposedBridge.log(it)
        }
    }

    private fun hookExactMethodIfPresent(
        targetClass: Class<*>,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        callback: XC_MethodHook,
    ): Boolean {
        val method = targetClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == methodName &&
                candidate.parameterTypes.contentEquals(parameterTypes)
        } ?: return false
        XposedBridge.hookMethod(method, callback)
        return true
    }

    /**
     * Invoke a platform method using its exact primitive signature. This is
     * deliberately independent of XposedHelpers' runtime argument matching:
     * Android 17 removed NfcService#setIsoDepProtocolRoute and the boxed
     * Integer supplied by Kotlin can otherwise turn that API change into an
     * ambiguous NoSuchMethodError.
     */
    private fun callExactMethod(
        receiver: Any,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        arguments: Array<Any?>,
    ): Any? {
        var current: Class<*>? = receiver.javaClass
        while (current != null) {
            val method = current.declaredMethods.firstOrNull { candidate ->
                candidate.name == methodName &&
                    candidate.parameterTypes.contentEquals(parameterTypes)
            }
            if (method != null) {
                method.isAccessible = true
                return method.invoke(receiver, *arguments)
            }
            current = current.superclass
        }
        throw NoSuchMethodException(
            "${receiver.javaClass.name}#$methodName" +
                parameterTypes.joinToString(prefix = "(", postfix = ")") {
                    it.name
                },
        )
    }

    /** Android 16 has a direct setter; Android 17 queues message 22. */
    private fun queueIsoDepRoute(nfcService: Any, route: Int) {
        val direct = runCatching {
            callExactMethod(
                nfcService,
                "setIsoDepProtocolRoute",
                arrayOf(Int::class.javaPrimitiveType!!),
                arrayOf(route),
            )
        }
        if (direct.isSuccess) return

        callExactMethod(
            nfcService,
            "sendMessage",
            arrayOf(Int::class.javaPrimitiveType!!, Any::class.java),
            arrayOf(22, Integer.valueOf(route)),
        )
    }

    private fun queueTechnologyRoutes(
        nfcService: Any,
        offHostRoute: Int,
        felicaRoute: Int,
    ) {
        callExactMethod(
            nfcService,
            "setTechnologyABFRoute",
            arrayOf(
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ),
            arrayOf(offHostRoute, felicaRoute),
        )
    }

    /**
     * Android 16 exposes a convenience rebuild method on RegisteredAidCache.
     * Android 17 folds it into updateRoutingLocked(force, routingChange).
     * Use the platform cache lock for the latter, matching its own caller.
     */
    private fun rebuildAidRouting(cardEmulationManager: Any): Int? {
        val aidCache = XposedHelpers.getObjectField(
            cardEmulationManager,
            "mAidCache",
        )
        val legacy = runCatching {
            (callExactMethod(
                aidCache,
                "onRoutingOverridedOrRecovered",
                emptyArray(),
                emptyArray(),
            ) as? Number)?.toInt()
        }
        if (legacy.isSuccess) return legacy.getOrNull()

        val cacheLock = runCatching {
            XposedHelpers.getObjectField(aidCache, "mLock")
        }.getOrElse { aidCache }
        return synchronized(cacheLock) {
            (callExactMethod(
                aidCache,
                "updateRoutingLocked",
                arrayOf(
                    Boolean::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                ),
                arrayOf(true, true),
            ) as? Number)?.toInt()
        }
    }

    private fun refreshOfficialRoutingMode() {
        routingCompatEnabled = true
    }

    /**
     * Repair every Java representation of the eSE identity. The route arrays
     * are final fields but intentionally mutable and are shared by
     * RoutingOptionManager, CardEmulationManager and AidRoutingManager.
     * Updating the elements is safe and also covers already-constructed
     * consumers; the explicit consumer patches below defend against vendor
     * branches that clone the array.
     */
    private fun patchOfficialEseIdentity(routingManager: Any) {
        patchCachedEseRouteArray(routingManager)

        runCatching {
            @Suppress("UNCHECKED_CAST")
            val routeForSecureElement =
                XposedHelpers.getObjectField(
                    routingManager,
                    "mRouteForSecureElement",
                ) as? MutableMap<Any?, Any?>
                    ?: return@runCatching
            @Suppress("UNCHECKED_CAST")
            val secureElementForRoute =
                XposedHelpers.getObjectField(
                    routingManager,
                    "mSecureElementForRoute",
                ) as? MutableMap<Any?, Any?>
                    ?: return@runCatching

            val previousRoute =
                (routeForSecureElement["eSE1"] as? Number)?.toInt()
            routeForSecureElement["eSE1"] = ONEPLUS13_ESE_NFCEE_ID
            if (
                previousRoute != null &&
                previousRoute != ONEPLUS13_ESE_NFCEE_ID &&
                secureElementForRoute[previousRoute] == "eSE1"
            ) {
                secureElementForRoute.remove(previousRoute)
            }
            secureElementForRoute[ONEPLUS13_ESE_NFCEE_ID] = "eSE1"
        }.onFailure {
            XposedBridge.log("$TAG: failed to repair eSE route lookup tables")
            XposedBridge.log(it)
        }

        listOf(
            "mDefaultRoute",
            "mDefaultIsoDepRoute",
            "mDefaultOffHostRoute",
            "mDefaultFelicaRoute",
            "mDefaultScRoute",
            "mOverrideDefaultRoute",
            "mOverrideDefaultIsoDepRoute",
            "mOverrideDefaultOffHostRoute",
            "mOverrideDefaultFelicaRoute",
            "mOverrideDefaultScRoute",
        ).forEach { fieldName ->
            translateStaleEseField(routingManager, fieldName)
        }
    }

    private fun patchCachedEseRouteArray(owner: Any) {
        val hasRouteArray = generateSequence(owner.javaClass) { type ->
            type.superclass
        }.any { type ->
            type.declaredFields.any { field ->
                field.name == "mOffHostRouteEse"
            }
        }
        if (!hasRouteArray) return

        runCatching {
            val routes =
                XposedHelpers.getObjectField(
                    owner,
                    "mOffHostRouteEse",
                ) as? ByteArray
                    ?: return@runCatching
            routes.indices.forEach { index ->
                if (
                    routes[index].toInt() and 0xFF ==
                    STALE_XIAOMI_ESE_ROUTE
                ) {
                    routes[index] = ONEPLUS13_ESE_NFCEE_ID.toByte()
                }
            }
        }.onFailure {
            XposedBridge.log("$TAG: failed to repair cached eSE route array")
            XposedBridge.log(it)
        }
    }

    private fun translateStaleEseField(owner: Any, fieldName: String) {
        runCatching {
            if (
                XposedHelpers.getIntField(owner, fieldName) ==
                STALE_XIAOMI_ESE_ROUTE
            ) {
                XposedHelpers.setIntField(
                    owner,
                    fieldName,
                    ONEPLUS13_ESE_NFCEE_ID,
                )
            }
        }
    }

    private fun patchOfficialRoutingObjects(compat: Boolean) {
        val routingManager = routingManagerRef ?: return
        patchOfficialEseIdentity(routingManager)
        cardEmulationManagerRef?.let(::patchCachedEseRouteArray)
        aidRoutingManagerRef?.let(::patchCachedEseRouteArray)

        val isoRoute = if (compat) {
            routingEse
        } else {
            routingOriginalIsoDep
        } ?: return
        val offHostRoute = if (compat) {
            routingEse
        } else {
            routingOriginalOffHost
        } ?: return
        val felicaRoute = routingOriginalFelica ?: return

        XposedHelpers.setIntField(
            routingManager,
            "mDefaultIsoDepRoute",
            isoRoute,
        )
        XposedHelpers.setIntField(
            routingManager,
            "mDefaultOffHostRoute",
            offHostRoute,
        )
        XposedHelpers.setIntField(
            routingManager,
            "mDefaultFelicaRoute",
            felicaRoute,
        )

        aidRoutingManagerRef?.let { aidManager ->
            translateStaleEseField(aidManager, "mDefaultRoute")
            XposedHelpers.setIntField(
                aidManager,
                "mDefaultIsoDepRoute",
                isoRoute,
            )
            XposedHelpers.setIntField(
                aidManager,
                "mDefaultOffHostRoute",
                offHostRoute,
            )
            XposedHelpers.setIntField(
                aidManager,
                "mDefaultFelicaRoute",
                felicaRoute,
            )
        }
    }

    /**
     * Android's parsed off-host service metadata is the source of truth for
     * dynamic AID routing. A shell-side `cmd nfc set-offhost-se` entry can be
     * lost when the package cache is rebuilt or after an OTA. Keep the genuine
     * FinShell/TAS CardService on the physical embedded secure element while
     * compatibility mode is active, directly at the framework object that the
     * NFC service already uses.
     */
    private fun hookOffHostSecureElementResolution(classLoader: ClassLoader) {
        runCatching {
            val apduServiceInfo = XposedHelpers.findClass(
                "android.nfc.cardemulation.ApduServiceInfo",
                classLoader,
            )
            XposedBridge.hookAllConstructors(
                apduServiceInfo,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applyFinShellOffHostSecureElement(param.thisObject)
                    }
                },
            )
            val getterHooks = XposedBridge.hookAllMethods(
                apduServiceInfo,
                "getOffHostSecureElement",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val current = param.result as? String
                        if (!current.isNullOrBlank()) return
                        if (!isFinShellOffHostService(param.thisObject)) return
                        val context = currentApplicationContext() ?: return
                        if (!isColorOsWalletMode(context) ||
                            !isEmbeddedSecureElementConfigured(context)
                        ) {
                            return
                        }
                        param.result = OFF_HOST_ESE_NAME
                    }
                },
            )
            check(getterHooks.isNotEmpty()) {
                "ApduServiceInfo.getOffHostSecureElement hook target not found"
            }
            XposedBridge.log(
                "$TAG: FinShell off-host secure-element resolution installed",
            )
        }.onFailure {
            XposedBridge.log(
                "$TAG: FinShell off-host secure-element resolution unavailable",
            )
            XposedBridge.log(it)
        }
    }

    private fun applyFinShellOffHostSecureElement(serviceInfo: Any?) {
        if (!isFinShellOffHostService(serviceInfo)) return
        val context = currentApplicationContext() ?: return
        if (!isColorOsWalletMode(context) ||
            !isEmbeddedSecureElementConfigured(context)
        ) {
            return
        }
        runCatching {
            XposedHelpers.callMethod(
                serviceInfo,
                "setOffHostSecureElement",
                OFF_HOST_ESE_NAME,
            )
        }.onFailure {
            XposedBridge.log("$TAG: unable to set parsed FinShell off-host route")
        }
    }

    private fun isFinShellOffHostService(serviceInfo: Any?): Boolean {
        if (serviceInfo == null) return false
        val component = runCatching {
            XposedHelpers.callMethod(serviceInfo, "getComponent") as? ComponentName
        }.getOrNull() ?: return false
        return component.packageName in OFF_HOST_WALLET_PACKAGES &&
            component.className == FIN_SHELL_CARD_SERVICE
    }

    private fun currentApplicationContext(): Context? {
        routingContextRef?.let { return it }
        return runCatching {
            val activityThread = XposedHelpers.findClass(
                "android.app.ActivityThread",
                null,
            )
            XposedHelpers.callStaticMethod(
                activityThread,
                "currentApplication",
            ) as? Context
        }.getOrNull()
    }

    private fun isEmbeddedSecureElementConfigured(context: Context): Boolean {
        val active = runCatching {
            Settings.Global.getString(
                context.contentResolver,
                NFC_MULTISE_ACTIVE,
            ) ?: Settings.System.getString(
                context.contentResolver,
                NFC_MULTISE_ACTIVE,
            )
        }.getOrNull()
        // An empty value is the pre-reconciliation state. The NFC service will
        // fill it with Embedded SE before it commits routing.
        return active.isNullOrBlank() || active == "null" ||
            active == EMBEDDED_SE_ROUTE
    }

    /**
     * FinShell already has a backward-compatible broadcast path for systems
     * without OPlus' VendorNfcAdapter. Only its aggregate tuning gate is false
     * on the transplanted system, which suppresses the per-card parameter
     * index. Enable that narrow gate; leave SET_CONFIG itself unsupported so
     * FinShell continues to use its normal explicit broadcast fallback.
     */
    private fun hookFinShellAccessCardTuningGate(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.nearme.nfc.e.f",
                classLoader,
                "a",
                XC_MethodReplacement.returnConstant(true),
            )
            XposedBridge.log("$TAG: FinShell access-card tuning gate enabled")
        }.onFailure {
            XposedBridge.log("$TAG: FinShell access-card tuning gate unavailable")
            XposedBridge.log(it)
        }
    }

    /**
     * Recreates the small part of ColorOS VendorNfcService required by an
     * off-host access card:
     *
     *  1. cache UPDATE_CITY_CONFIG card identity sent by FinShell;
     *  2. while that card remains selected, load the matching access-card RF
     *     profile through the existing NXP vendor AIDL;
     *  3. disable the global forced ISO-DEP SAK bit in register A11B so the
     *     selected eSE applet can advertise its own SAK (normally 0x08);
     *  4. keep the selected card usable from the lock screen, and restore the
     *     exact previous profile/register value on card or wallet-mode change.
     *
     * No polling, wake lock, TEE emulation, CPLC modification or secure-world
     * bypass is involved. All controller operations run on NfcService's own
     * handler and are transactional.
     */
    private fun hookColorOsAccessCardBridge(classLoader: ClassLoader) {
        runCatching {
            val nfcServiceClass = XposedHelpers.findClass(
                "com.android.nfc.NfcService",
                classLoader,
            )
            XposedBridge.hookAllConstructors(
                nfcServiceClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        installNfcBridgeIfReady(param.thisObject, classLoader)
                    }
                },
            )

            // Android 17 creates an empty NfcService and initializes all of
            // its fields manually from NfcApplication.onCreate. The
            // constructor callback above therefore runs before mContext and
            // mHandler exist. Retry once after application initialization;
            // Android 16 remains covered by the constructor path.
            val nfcApplicationClass = XposedHelpers.findClass(
                "com.android.nfc.NfcApplication",
                classLoader,
            )
            XposedBridge.hookAllMethods(
                nfcApplicationClass,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val service = runCatching {
                            XposedHelpers.getStaticObjectField(
                                nfcServiceClass,
                                "sService",
                            )
                        }.getOrNull() ?: return
                        installNfcBridgeIfReady(service, classLoader)
                    }
                },
            )
            XposedBridge.log("$TAG: ColorOS access-card bridge constructor hook installed")
        }.onFailure {
            XposedBridge.log("$TAG: ColorOS access-card bridge unavailable")
            XposedBridge.log(it)
        }
    }

    private fun installNfcBridgeIfReady(
        service: Any,
        classLoader: ClassLoader,
    ) {
        synchronized(HookEntry::class.java) {
            if (nfcBridgeInstalled) return
            val context = runCatching {
                XposedHelpers.getObjectField(service, "mContext") as? Context
            }.getOrNull() ?: return
            val handler = runCatching {
                XposedHelpers.getObjectField(service, "mHandler") as? Handler
            }.getOrNull() ?: return

            ColorOsAccessCardBridge(
                context,
                handler,
                service,
                classLoader,
            ).install()
            nfcBridgeInstalled = true
        }
    }

    private inner class ColorOsAccessCardBridge(
        private val context: Context,
        private val handler: Handler,
        private val nfcService: Any,
        private val classLoader: ClassLoader,
    ) {
        private val preferences = context.getSharedPreferences(
            NFC_BRIDGE_PREFS,
            Context.MODE_PRIVATE,
        )

        private var cardType = preferences.getString("card_type", null)
        private var aid = preferences.getString("aid", null)
        private var parameterIndex = preferences.getString(
            "parameter_index",
            null,
        )
        private var usingDh = preferences.getBoolean("using_dh", false)
        private var swipePage = preferences.getBoolean("swipe_page", false)
        private var swipeStartedAt = preferences.getLong("swipe_started_at", 0L)
        private var runtimeApplied = preferences.getBoolean(
            "runtime_applied",
            false,
        )
        private var savedSak = hexToBytes(
            preferences.getString("saved_sak", null),
        )
        private var savedDh85Mode = preferences.getInt(
            "saved_dh85_mode",
            -1,
        ).takeIf { it == 0 || it == 1 }
        private var savedTransitConfig = preferences.getString(
            "saved_transit_config",
            null,
        )
        private var routingLiveCompat: Boolean? = null
        @Volatile
        private var fullRoutingRebuildInFlight = false
        @Volatile
        private var fullRoutingRebuiltCompat: Boolean? = null
        private val fixedWalletPolicy = FixedWalletPolicy(context, handler, nfcService) { reason ->
            reconcileAfterNfcReady(reason)
        }

        private val timeoutRollback = Runnable {
            if (!swipePage) return@Runnable
            XposedBridge.log(
                "$TAG: access-card swipe-page context expired; " +
                    "selected-card RF profile remains active",
            )
            swipePage = false
            persistPageState()
        }

        private val walletReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_UPDATE_CITY_CONFIG -> onCardConfig(intent)
                    ACTION_ENTER_SWIPE_PAGE -> onSwipePage(intent)
                    ACTION_UPDATE_SMART_CARD_MANAGER -> onSmartCardEvent(intent)
                    ACTION_RECONCILE_NFC -> reconcileAfterNfcReady(
                        "module_mode_change",
                    )
                }
            }
        }

        private val systemReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (swipePage) {
                            swipePage = false
                            persistPageState()
                            handler.removeCallbacks(timeoutRollback)
                        }
                    }

                    NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> {
                        when (
                            intent.getIntExtra(
                                NfcAdapter.EXTRA_ADAPTER_STATE,
                                NfcAdapter.STATE_OFF,
                            )
                        ) {
                            NfcAdapter.STATE_OFF -> onNfcOff()
                            NfcAdapter.STATE_ON -> {
                                reconcileAfterNfcReady("adapter_on")
                                // ACTION_ADAPTER_STATE_ON can arrive before
                                // discovery is enabled. HyperOS deliberately
                                // skips MSG_COMMIT_ROUTING in that window.
                                // Retry once after the controller settles;
                                // this is event-driven and creates no poller.
                                handler.postDelayed(
                                    {
                                        reconcileAfterNfcReady(
                                            "adapter_stable",
                                        )
                                    },
                                    1_200L,
                                )
                            }
                        }
                    }
                }
            }
        }

        fun install() {
            runCatching { fixedWalletPolicy.install() }.onFailure {
                XposedBridge.log("$TAG: fixed wallet policy install failed: $it")
            }
            val walletFilter = IntentFilter().apply {
                addAction(ACTION_UPDATE_CITY_CONFIG)
                addAction(ACTION_ENTER_SWIPE_PAGE)
                addAction(ACTION_UPDATE_SMART_CARD_MANAGER)
                addAction(ACTION_RECONCILE_NFC)
            }
            context.registerReceiver(
                walletReceiver,
                walletFilter,
                FIN_SHELL_EVENT_PERMISSION,
                handler,
                Context.RECEIVER_EXPORTED,
            )

            val systemFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            }
            context.registerReceiver(
                systemReceiver,
                systemFilter,
                null,
                handler,
                Context.RECEIVER_EXPORTED,
            )

            handler.postDelayed(
                {
                    hydrateSelectedCardFromGlobalSettings()
                    syncSelectedWalletAidGroup("service_start")
                    reconcileAfterNfcReady("service_start")
                },
                1_500L,
            )
            XposedBridge.log(
                "$TAG: protected, event-driven ColorOS access-card bridge installed",
            )
        }

        private fun onCardConfig(intent: Intent) {
            val newCardType = intent.getStringExtra("cardType") ?: return
            val newAid = intent.getStringExtra("aid")
            val newParameterIndex = intent.getStringExtra("parameter_index")
            val newUsingDh = intent.getStringExtra("isUsingDH")
                .equals("yes", ignoreCase = true)

            val identityChanged =
                newCardType != cardType ||
                    newAid != aid ||
                    newParameterIndex != parameterIndex ||
                    newUsingDh != usingDh

            if (
                identityChanged &&
                (runtimeApplied || savedSak != null || savedDh85Mode != null)
            ) {
                deactivateRuntime("card_change")
            }

            cardType = newCardType
            aid = newAid
            parameterIndex = newParameterIndex
            usingDh = newUsingDh

            preferences.edit()
                .putString("card_type", cardType)
                .putString("aid", aid)
                .putString("parameter_index", parameterIndex)
                .putBoolean("using_dh", usingDh)
                .apply()
            mirrorColorOsCardSettings(intent)
            val dynamicAidChanged =
                syncSelectedWalletAidGroup("card_config")
            if (
                identityChanged &&
                dynamicAidChanged &&
                cardType.equals(EID_CARD_TYPE, ignoreCase = true)
            ) {
                showEidSwitchToast()
            }

            XposedBridge.log(
                "$TAG: cached NFC card config type=$cardType, " +
                    "access=${isAccessCard()}, dh=$usingDh",
            )

            val routingQueued = reconcileOfficialRouting(
                "card_config",
                force = dynamicAidChanged,
            )
            if (routingQueued) {
                handler.post {
                    applySelectedCardRuntime("card_config")
                    applyRoutingAfterOfficialSequence("card_config")
                }
            } else {
                applySelectedCardRuntime("card_config")
            }
        }

        /**
         * FinShell publishes the selected wallet-card identity through its
         * protected UPDATE_CITY_CONFIG broadcast and mirrors the same values
         * into Settings.Global. Some transplanted NFC builds retain the
         * previous dynamic "other" AID group even after the eSE applet has
         * switched, producing a mixed old-route/new-card state. This affects
         * every off-host card type, including access, transit, bank and eID.
         *
         * Update the platform's existing FinShell service cache through its
         * normal RegisteredServicesCache path. The dynamic "other" group is
         * FinShell's current-card slot, so replace stale entries with exactly
         * the currently selected AID. Static service AIDs remain untouched.
         * Registration invokes the stock onServicesUpdated routing callback.
         * It is event-driven: there is no observer loop or timer.
         */
        private fun syncSelectedWalletAidGroup(reason: String): Boolean {
            if (!isCompatEnabled() || !isOffHostWalletCard()) return false

            val selectedAid = normalizeAid(aid) ?: run {
                XposedBridge.log(
                    "$TAG: ignored invalid selected wallet-card AID ($reason)",
                )
                return false
            }

            return runCatching {
                val cardEmulationManager = XposedHelpers.getObjectField(
                    nfcService,
                    "mCardEmulationManager",
                )
                val serviceCache = XposedHelpers.getObjectField(
                    cardEmulationManager,
                    "mServiceCache",
                )
                val cardEmulationInterface = runCatching {
                    XposedHelpers.getObjectField(
                        cardEmulationManager,
                        "mCardEmulationInterface",
                    )
                }.getOrNull()
                val component = ComponentName(
                    FIN_SHELL_PACKAGE,
                    FIN_SHELL_CARD_SERVICE,
                )
                val walletUid = context.packageManager.getPackageUid(
                    FIN_SHELL_PACKAGE,
                    0,
                )
                val userId = walletUid / 100_000
                val existingGroup = runCatching {
                    // Android 16 and earlier keep the privileged UID-aware
                    // helper directly on RegisteredServicesCache.
                    XposedHelpers.callMethod(
                        serviceCache,
                        "getAidGroupForService",
                        userId,
                        walletUid,
                        component,
                        "other",
                    )
                }.getOrElse {
                    // Android 17 moved the implementation into the card-
                    // emulation Binder stub and derives the caller UID there.
                    val cardInterface = cardEmulationInterface ?: throw it
                    XposedHelpers.callMethod(
                        cardInterface,
                        "getAidGroupForService",
                        userId,
                        component,
                        "other",
                    )
                }
                val existingAids = if (existingGroup == null) {
                    emptyList()
                } else {
                    @Suppress("UNCHECKED_CAST")
                    (
                        XposedHelpers.callMethod(
                            existingGroup,
                            "getAids",
                        ) as? List<Any?>
                        )
                        ?.mapNotNull { it as? String }
                        .orEmpty()
                }
                val desiredAids = listOf(selectedAid)

                if (
                    existingAids.map { it.uppercase(Locale.US) } ==
                    desiredAids
                ) {
                    return@runCatching false
                }

                val aidGroupClass = XposedHelpers.findClass(
                    "android.nfc.cardemulation.AidGroup",
                    classLoader,
                )
                val aidGroup = XposedHelpers.newInstance(
                    aidGroupClass,
                    desiredAids,
                    "other",
                )
                val updated = runCatching {
                    XposedHelpers.callMethod(
                        serviceCache,
                        "registerAidGroupForService",
                        userId,
                        walletUid,
                        component,
                        aidGroup,
                    )
                }.getOrElse {
                    val cardInterface = cardEmulationInterface ?: throw it
                    XposedHelpers.callMethod(
                        cardInterface,
                        "registerAidGroupForService",
                        userId,
                        component,
                        aidGroup,
                    )
                } as? Boolean == true
                if (updated) {
                    XposedBridge.log(
                        "$TAG: synchronized selected wallet-card dynamic AID " +
                            "type=$cardType aid=$selectedAid ($reason)",
                    )
                } else {
                    XposedBridge.log(
                        "$TAG: selected wallet-card dynamic AID update " +
                            "was rejected ($reason)",
                    )
                }
                updated
            }.getOrElse {
                XposedBridge.log(
                    "$TAG: selected wallet-card dynamic AID sync failed " +
                        "($reason)",
                )
                XposedBridge.log(it)
                false
            }
        }

        private fun normalizeAid(value: String?): String? {
            val normalized = value
                ?.trim()
                ?.uppercase(Locale.US)
                ?: return null
            if (
                normalized.length !in 10..32 ||
                normalized.length % 2 != 0 ||
                !normalized.matches(Regex("^[0-9A-F]+$"))
            ) {
                return null
            }
            return normalized
        }

        private fun showEidSwitchToast() {
            // Text toasts otherwise remain visible for roughly two seconds.
            // Cancel this confirmation after 500 ms as requested. This runs
            // only after a real eID identity change and successful dynamic
            // AID registration, never for duplicate wallet broadcasts.
            handler.postDelayed(
                {
                    runCatching {
                        val toast = Toast.makeText(
                            context,
                            "模拟卡已切换",
                            Toast.LENGTH_SHORT,
                        )
                        toast.show()
                        handler.postDelayed(
                            { runCatching { toast.cancel() } },
                            500L,
                        )
                    }.onFailure {
                        XposedBridge.log("$TAG: eID switch toast failed")
                        XposedBridge.log(it)
                    }
                },
                180L,
            )
        }

        private fun hydrateSelectedCardFromGlobalSettings() {
            runCatching {
                val resolver = context.contentResolver
                val globalCardType = Settings.Global.getString(
                    resolver,
                    "cardType",
                )
                val globalAid = normalizeAid(
                    Settings.Global.getString(resolver, "aid"),
                )
                if (
                    !globalCardType.isNullOrBlank() &&
                    !globalCardType.equals("defaultcard", ignoreCase = true) &&
                    globalAid != null
                ) {
                    cardType = globalCardType
                    aid = globalAid
                    parameterIndex = Settings.Global.getString(
                        resolver,
                        "parameter_index",
                    )
                    preferences.edit()
                        .putString("card_type", cardType)
                        .putString("aid", aid)
                        .putString("parameter_index", parameterIndex)
                        .apply()
                }
            }.onFailure {
                XposedBridge.log(
                    "$TAG: selected card settings hydration failed",
                )
                XposedBridge.log(it)
            }
        }

        private fun applySelectedCardRuntime(reason: String) {
            if (isCompatEnabled() && isAccessCard() && !usingDh) {
                // ColorOS applies the selected card's RF profile here, not
                // merely while its foreground page is open. This is what
                // makes screen-off/lock-screen access-card emulation work.
                activateRuntime(reason)
            } else {
                if (swipePage) {
                    swipePage = false
                    persistPageState()
                    handler.removeCallbacks(timeoutRollback)
                }
                if (
                    runtimeApplied ||
                    savedSak != null ||
                    savedDh85Mode != null
                ) {
                    deactivateRuntime("non_access_card")
                }
            }
        }

        private fun onSwipePage(intent: Intent) {
            when (intent.getIntExtra("swipePage", -1)) {
                1 -> {
                    if (!isAccessCard()) {
                        XposedBridge.log(
                            "$TAG: ignored swipe-page enter for non-access card",
                        )
                        return
                    }
                    if (usingDh) {
                        XposedBridge.log(
                            "$TAG: DH/HCE access-card parameters are not changed; " +
                                "off-host eSE path remains isolated",
                        )
                        return
                    }

                    swipePage = true
                    swipeStartedAt = System.currentTimeMillis()
                    persistPageState()
                    scheduleTimeout(NFC_BRIDGE_TIMEOUT_MS)
                    activateRuntime("swipe_page_enter")
                }

                0 -> {
                    swipePage = false
                    persistPageState()
                    handler.removeCallbacks(timeoutRollback)
                    XposedBridge.log(
                        "$TAG: wallet swipe page exited; " +
                            "selected access-card RF profile retained",
                    )
                }
            }
        }

        private fun onSmartCardEvent(intent: Intent) {
            if (
                intent.getBooleanExtra("user_switch", false) &&
                swipePage &&
                isAccessCard() &&
                !usingDh
            ) {
                activateRuntime("user_switch")
            }
        }

        private fun reconcileAfterNfcReady(reason: String) {
            fixedWalletPolicy.reconcile(reason)
            if (!isNfcEnabled()) return

            val routingQueued = reconcileOfficialRouting(
                reason,
                force =
                    reason == "adapter_on" ||
                        reason == "adapter_stable" ||
                        reason == "service_start" ||
                        reason == "wallet_policy" ||
                        reason == "module_mode_change",
            )
            if (routingQueued) {
                // NfcService serializes protocol/technology route changes on
                // this same Handler. Posting the rest of the ColorOS sequence
                // guarantees route -> RF profile -> DH85 -> SAK -> apply.
                handler.post {
                    reconcileCardStateAfterRouting(reason)
                    applyRoutingAfterOfficialSequence(reason)
                }
                return
            }
            reconcileCardStateAfterRouting(reason)
        }

        private fun reconcileCardStateAfterRouting(reason: String) {
            if (!isCompatEnabled()) {
                if (
                    runtimeApplied ||
                    savedSak != null ||
                    savedDh85Mode != null
                ) {
                    deactivateRuntime("${reason}_system_mode")
                }
                return
            }

            val elapsed = System.currentTimeMillis() - swipeStartedAt
            if (swipePage) {
                if (elapsed in 0 until NFC_BRIDGE_TIMEOUT_MS) {
                    scheduleTimeout(NFC_BRIDGE_TIMEOUT_MS - elapsed)
                } else {
                    swipePage = false
                    persistPageState()
                }
            }

            if (isAccessCard() && !usingDh) {
                // A service/controller restart can lose live register state.
                // Keep the original snapshot but force one idempotent replay.
                if (runtimeApplied) {
                    runtimeApplied = false
                    persistRuntimeState()
                }
                activateRuntime(reason)
            } else if (
                runtimeApplied ||
                savedSak != null ||
                savedDh85Mode != null
            ) {
                deactivateRuntime("${reason}_non_access")
            }
        }

        private fun onNfcOff() {
            handler.removeCallbacks(timeoutRollback)
            routingLiveCompat = null
            fullRoutingRebuildInFlight = false
            fullRoutingRebuiltCompat = null
            runtimeApplied = false
            persistRuntimeState()
            XposedBridge.log(
                "$TAG: NFC off; retained selected-card RF snapshot for " +
                    "event-driven replay or exact mode-change restore",
            )
        }

        /**
         * Mirrors ColorOS' default-route decision through the platform's own
         * NfcService queue. The route ID is resolved from eSE1 instead of
         * hard-coded, while the original Xiaomi routes are retained for an
         * exact system-mode restore.
         *
         * Returns true when route messages were queued. Callers then post the
         * RF/SAK work to the same Handler so the native order stays official.
         */
        private fun reconcileOfficialRouting(
            reason: String,
            force: Boolean = false,
        ): Boolean {
            val manager = routingManagerRef ?: return false
            val eseRoute = routingEse ?: return false
            val compat = isCompatEnabled()
            routingCompatEnabled = compat
            val isoRoute = if (compat) {
                eseRoute
            } else {
                routingOriginalIsoDep ?: return false
            }
            val offHostRoute = if (compat) {
                eseRoute
            } else {
                routingOriginalOffHost ?: return false
            }
            val felicaRoute = routingOriginalFelica ?: return false

            return runCatching {
                patchOfficialRoutingObjects(compat)

                if (!isNfcEnabled()) {
                    routingLiveCompat = null
                    return@runCatching false
                }
                if (!force && routingLiveCompat == compat) {
                    return@runCatching false
                }

                queueIsoDepRoute(nfcService, isoRoute)
                queueTechnologyRoutes(
                    nfcService,
                    offHostRoute,
                    felicaRoute,
                )
                routingLiveCompat = compat
                XposedBridge.log(
                    "$TAG: official routing queued ($reason) " +
                        "iso=$isoRoute, ab=$offHostRoute, " +
                        "felica=$felicaRoute, compat=$compat",
                )
                true
            }.getOrElse {
                XposedBridge.log(
                    "$TAG: official routing reconciliation failed ($reason)",
                )
                XposedBridge.log(it)
                false
            }
        }

        private fun applyRoutingAfterOfficialSequence(reason: String) {
            runCatching {
                if (isNfcEnabled()) {
                    // Protocol/technology setters update the NFCC's pending
                    // routing table. ColorOS commits that transaction before
                    // refreshing discovery; applyRouting alone does not make
                    // the pending NFCEE destinations live.
                    XposedHelpers.callMethod(
                        nfcService,
                        "commitRouting",
                        false,
                    )
                    handler.post {
                        runCatching {
                            if (isNfcEnabled()) {
                                val needsFullRebuild =
                                    reason == "adapter_stable" ||
                                    reason == "service_start" ||
                                    reason == "wallet_policy" ||
                                    reason == "module_mode_change"
                                if (
                                    needsFullRebuild &&
                                    beginFullRoutingRebuild(reason)
                                ) {
                                    return@runCatching
                                }
                                handler.post {
                                    finishNativeRoutingCommit(reason)
                                }
                            }
                        }.onFailure {
                            XposedBridge.log(
                                "$TAG: official full routing rebuild failed " +
                                    "($reason)",
                            )
                            XposedBridge.log(it)
                        }
                    }
                }
            }.onFailure {
                XposedBridge.log("$TAG: final official routing commit failed")
                XposedBridge.log(it)
            }
        }

        /**
         * AidRoutingManager's forced transaction calls commitRouting(true),
         * which waits for NfcService's Handler to process MSG_COMMIT_ROUTING.
         * Calling it from that Handler deadlocks until the 10-second timeout.
         * Start one short-lived worker so the Handler remains free to perform
         * the official clear -> AIDs -> protocol/tech -> commit sequence.
         */
        private fun beginFullRoutingRebuild(reason: String): Boolean {
            val compat = isCompatEnabled()
            synchronized(this) {
                if (fullRoutingRebuildInFlight) return true
                if (fullRoutingRebuiltCompat == compat) return false
                fullRoutingRebuildInFlight = true
            }

            Thread(
                {
                    val rebuildStatus = runCatching {
                        val cardEmulationManager =
                            XposedHelpers.getObjectField(
                                nfcService,
                                "mCardEmulationManager",
                            )
                        rebuildAidRouting(cardEmulationManager)
                    }.getOrElse {
                        XposedBridge.log(
                            "$TAG: official full routing worker failed " +
                                "($reason)",
                        )
                        XposedBridge.log(it)
                        null
                    }

                    handler.post {
                        synchronized(this) {
                            fullRoutingRebuildInFlight = false
                            if (rebuildStatus == 0) {
                                fullRoutingRebuiltCompat = compat
                            }
                        }
                        XposedBridge.log(
                            "$TAG: official full routing rebuild " +
                                "status=$rebuildStatus ($reason)",
                        )
                        finishNativeRoutingCommit(reason)
                    }
                },
                "ColorOsNfcRouting",
            ).start()
            return true
        }

        private fun finishNativeRoutingCommit(reason: String) {
            runCatching {
                if (isNfcEnabled()) {
                                // HyperOS drops MSG_COMMIT_ROUTING when its
                                // discovery state is briefly false during
                                // adapter bring-up. Finish the same native
                                // DeviceHost transaction synchronously on
                                // NfcService's own Handler. This is a one-shot
                                // fallback, not a polling loop.
                                val compat = isCompatEnabled()
                                val isoRoute = if (compat) {
                                    routingEse
                                } else {
                                    routingOriginalIsoDep
                                } ?: return@runCatching
                                val offHostRoute = if (compat) {
                                    routingEse
                                } else {
                                    routingOriginalOffHost
                                } ?: return@runCatching
                                val felicaRoute =
                                    routingOriginalFelica
                                        ?: return@runCatching
                                val deviceHost =
                                    XposedHelpers.getObjectField(
                                        nfcService,
                                        "mDeviceHost",
                                    )
                                callExactMethod(
                                    deviceHost,
                                    "setIsoDepProtocolRoute",
                                    arrayOf(Int::class.javaPrimitiveType!!),
                                    arrayOf(isoRoute),
                                )
                                callExactMethod(
                                    deviceHost,
                                    "setTechnologyABFRoute",
                                    arrayOf(
                                        Int::class.javaPrimitiveType!!,
                                        Int::class.javaPrimitiveType!!,
                                    ),
                                    arrayOf(offHostRoute, felicaRoute),
                                )
                                val status = (
                                    XposedHelpers.callMethod(
                                        deviceHost,
                                        "commitRouting",
                                    ) as? Number
                                    )?.toInt()
                                XposedBridge.log(
                                    "$TAG: direct official routing commit " +
                                        "status=$status iso=$isoRoute, " +
                                        "ab=$offHostRoute, " +
                                        "felica=$felicaRoute ($reason)",
                                )
                                XposedHelpers.callMethod(
                                    nfcService,
                                    "applyRouting",
                                    true,
                                )
                }
            }.onFailure {
                XposedBridge.log(
                    "$TAG: post-rebuild native routing commit failed ($reason)",
                )
                XposedBridge.log(it)
                }
        }

        private fun activateRuntime(reason: String) {
            if (!isNfcEnabled()) {
                XposedBridge.log(
                    "$TAG: deferred access-card RF activation until NFC is on",
                )
                return
            }
            if (!isCompatEnabled()) return
            if (!isAccessCard() || usingDh) return
            if (runtimeApplied) return

            val existingSnapshot = savedSak
            val sakSnapshot = existingSnapshot ?: readSakRegister() ?: run {
                    XposedBridge.log(
                        "$TAG: A11B snapshot failed; access-card RF change aborted",
                    )
                    return
                }
            val existingDh85Snapshot = savedDh85Mode
            val dh85Snapshot = existingDh85Snapshot ?: readDh85Mode() ?: run {
                    XposedBridge.log(
                        "$TAG: DH85 snapshot failed; access-card RF change aborted",
                    )
                    return
                }
            val baseConfig = if (existingSnapshot == null) {
                readTransitConfig(
                    File("/data/vendor/nfc/libnfc-nxpTransit.conf"),
                ) ?: readTransitConfig(
                    File("/data/nfc/libnfc-nxpTransit.conf"),
                )
            } else {
                savedTransitConfig
            }
            val accessConfig = readTransitConfig(resolveAccessProfile())

            if (existingSnapshot == null) {
                savedSak = sakSnapshot
                savedTransitConfig = baseConfig
            }
            if (existingDh85Snapshot == null) {
                savedDh85Mode = dh85Snapshot
            }
            persistRuntimeState()

            if (accessConfig != null) {
                if (!applyTransitConfig(accessConfig)) {
                    XposedBridge.log(
                        "$TAG: access-card transit profile rejected; " +
                            "continuing with transactional SAK compatibility",
                    )
                } else {
                    XposedBridge.log(
                        "$TAG: selected access-card transit profile applied " +
                            "(${profileLabel()})",
                    )
                }
            } else {
                XposedBridge.log(
                    "$TAG: access-card transit profile missing; using current RF profile",
                )
            }

            // ColorOS' official non-DH/eSE branch first writes CORE_SET_CONFIG
            // 85=01 (disable host-side Type-A parameter override), then calls
            // setForceSAK(false, 8), which maps to A11B=00 on SN220T. The eSE
            // applet is then responsible for advertising its own ATQA/SAK.
            if (!writeDh85Mode(1)) {
                if (baseConfig != null) {
                    applyTransitConfig(baseConfig)
                }
                XposedBridge.log(
                    "$TAG: official eSE DH85 switch failed; restored transit profile",
                )
                return
            }
            if (!writeSakRegister(sakSnapshot, 0)) {
                writeDh85Mode(dh85Snapshot)
                if (baseConfig != null) {
                    applyTransitConfig(baseConfig)
                }
                XposedBridge.log(
                    "$TAG: A11B update failed; restored DH85/transit state",
                )
                return
            }

            runtimeApplied = true
            persistRuntimeState()
            XposedBridge.log(
                "$TAG: selected access-card RF state active ($reason)",
            )
        }

        private fun deactivateRuntime(reason: String) {
            if (
                !runtimeApplied &&
                savedSak == null &&
                savedDh85Mode == null
            ) {
                return
            }
            if (!isNfcEnabled()) {
                onNfcOff()
                return
            }

            val baseConfig = savedTransitConfig ?: readTransitConfig(
                File("/data/vendor/nfc/libnfc-nxpTransit.conf"),
            )
            val profileRestored =
                baseConfig == null || applyTransitConfig(baseConfig)
            val sakRestored = savedSak?.let {
                writeSakRegister(it, null)
            } ?: true
            val dh85Restored = savedDh85Mode?.let {
                writeDh85Mode(it)
            } ?: true

            if (profileRestored && sakRestored && dh85Restored) {
                runtimeApplied = false
                savedSak = null
                savedDh85Mode = null
                savedTransitConfig = null
                persistRuntimeState()
                XposedBridge.log(
                    "$TAG: restored exact pre-access-card RF state ($reason)",
                )
            } else {
                XposedBridge.log(
                    "$TAG: RF restore incomplete ($reason) profile=$profileRestored " +
                        "sak=$sakRestored dh85=$dh85Restored; snapshot retained",
                )
            }
        }

        private fun isAccessCard(): Boolean {
            return cardType == ACCESS_CARD_TYPE &&
                aid?.startsWith(ACCESS_CARD_AID_PREFIX, ignoreCase = true) == true
        }

        private fun isOffHostWalletCard(): Boolean {
            val selectedType = cardType
            if (
                selectedType.isNullOrBlank() ||
                selectedType.equals("defaultcard", ignoreCase = true)
            ) {
                return false
            }
            // DH access cards are intentionally served by Android HCE. Every
            // other selected wallet card uses the eSE/off-host route.
            if (isAccessCard() && usingDh) return false
            return normalizeAid(aid) != null
        }

        private fun resolveAccessProfile(): File {
            val index = parameterIndex
            if (
                index != null &&
                Regex("^index[0-9]{1,2}$").matches(index)
            ) {
                val indexed = File(
                    "/data/vendor/nfc/libnfc_accesscard_${index}_config.conf",
                )
                if (indexed.isFile) return indexed
            }
            return File("/data/vendor/nfc/libnfc_accesscard_config.conf")
        }

        private fun profileLabel(): String {
            val index = parameterIndex
            return if (
                index != null &&
                Regex("^index[0-9]{1,2}$").matches(index) &&
                File(
                    "/data/vendor/nfc/libnfc_accesscard_${index}_config.conf",
                ).isFile
            ) {
                index
            } else {
                "default"
            }
        }

        private fun readTransitConfig(file: File): String? {
            return runCatching {
                if (!file.isFile || file.length() !in 1..262_144) {
                    return@runCatching null
                }
                file.readText(Charsets.UTF_8)
            }.getOrElse {
                XposedBridge.log("$TAG: failed reading ${file.absolutePath}")
                XposedBridge.log(it)
                null
            }
        }

        private fun applyTransitConfig(config: String): Boolean {
            var data: Parcel? = null
            var reply: Parcel? = null
            return runCatching {
                val serviceManager = XposedHelpers.findClass(
                    "android.os.ServiceManager",
                    classLoader,
                )
                val binder = XposedHelpers.callStaticMethod(
                    serviceManager,
                    "getService",
                    NXP_TRANSIT_SERVICE,
                ) as? IBinder ?: return@runCatching false
                if (binder.interfaceDescriptor != NXP_TRANSIT_DESCRIPTOR) {
                    XposedBridge.log(
                        "$TAG: unexpected NXP transit descriptor " +
                            binder.interfaceDescriptor,
                    )
                    return@runCatching false
                }

                data = Parcel.obtain()
                reply = Parcel.obtain()
                data!!.writeInterfaceToken(NXP_TRANSIT_DESCRIPTOR)
                data!!.writeString(config)
                if (
                    !binder.transact(
                        NXP_SET_TRANSIT_CONFIG_TRANSACTION,
                        data!!,
                        reply!!,
                        0,
                    )
                ) {
                    return@runCatching false
                }
                reply!!.readException()
                reply!!.readInt() != 0
            }.getOrElse {
                XposedBridge.log("$TAG: NXP transit config transaction failed")
                XposedBridge.log(it)
                false
            }.also {
                reply?.recycle()
                data?.recycle()
            }
        }

        private fun readSakRegister(): ByteArray? {
            return runCatching {
                val rfConfigUtils = XposedHelpers.findClass(
                    "com.android.nfc.rftools.RFConfigUtils",
                    classLoader,
                )
                val utils = XposedHelpers.callStaticMethod(
                    rfConfigUtils,
                    "getInstance",
                )
                val response = XposedHelpers.callMethod(
                    utils,
                    "getRegisterInfo",
                    byteArrayOf(0xA1.toByte(), 0x1B),
                )
                val status = (
                    XposedHelpers.getObjectField(response, "status") as? Number
                    )?.toInt()
                val payload = XposedHelpers.getObjectField(
                    response,
                    "payload",
                ) as? ByteArray
                if (status != 0 || payload == null || payload.size <= 5) {
                    return@runCatching null
                }
                payload.copyOfRange(5, payload.size)
            }.getOrElse {
                XposedBridge.log("$TAG: failed to snapshot A11B")
                XposedBridge.log(it)
                null
            }
        }

        private fun writeSakRegister(
            source: ByteArray,
            forcedMode: Int?,
        ): Boolean {
            if (source.isEmpty() || source.size > 250) return false
            val target = source.copyOf()
            if (forcedMode != null) {
                target[0] = forcedMode.toByte()
            }
            return runCatching {
                if (readSakRegister()?.contentEquals(target) == true) {
                    return@runCatching true
                }
                val result = XposedHelpers.callMethod(
                    nfcService,
                    "sendRFConfig",
                    byteArrayOf(0xA1.toByte(), 0x1B),
                    target,
                ) as? Number
                if (result?.toInt() == 0) return@runCatching true

                // A17 gates Xiaomi's public RF tuning facade behind a vendor
                // property absent on OnePlus. Do not enable Xiaomi's entire
                // automatic RF policy. Send exactly its original CORE_SET
                // packet, limited to this bridge's A11B snapshot/SAK update.
                val feature = XposedHelpers.findClassIfExists(
                    "com.android.nfc.Feature", classLoader,
                ) ?: return@runCatching false
                if (result?.toInt() != 3 ||
                    XposedHelpers.callStaticMethod(feature, "isRFCustomEnabled") != false
                ) return@runCatching false
                val deviceHost = XposedHelpers.getObjectField(nfcService, "mDeviceHost")
                val lock = runCatching {
                    XposedHelpers.getObjectField(nfcService, "mRfConfigLock")
                }.getOrDefault(nfcService)
                synchronized(lock) {
                    XposedHelpers.callMethod(deviceHost, "disableDiscovery")
                    try {
                        val payload = byteArrayOf(1, 0xA1.toByte(), 0x1B, target.size.toByte()) + target
                        val response = sendRawCoreCommand(0x02, payload)
                            ?: return@runCatching false
                        val status = (XposedHelpers.getObjectField(response, "status") as? Number)?.toInt()
                        val data = XposedHelpers.getObjectField(response, "payload") as? ByteArray
                        val accepted = status == 0 && data != null && data.isNotEmpty() && data[0].toInt() == 0
                        val verified = accepted && readSakRegister()?.contentEquals(target) == true
                        XposedBridge.log(
                            "$TAG: A17 wallet SAK fallback accepted=$accepted verified=$verified mode=${target[0].toInt() and 255}",
                        )
                        verified
                    } finally {
                        XposedHelpers.callMethod(nfcService, "applyRouting", true)
                    }
                }
            }.getOrElse {
                XposedBridge.log("$TAG: failed writing A11B")
                XposedBridge.log(it)
                false
            }
        }

        /**
         * Reads NCI CORE_CONFIG parameter 0x85. ColorOS uses value 0x01 for
         * the eSE path and 0x00 for its DH/HCE Type-A override path.
         */
        private fun readDh85Mode(): Int? {
            return runCatching {
                val response = sendRawCoreCommand(
                    0x03,
                    byteArrayOf(0x01, 0x85.toByte()),
                ) ?: return@runCatching null
                val status = (
                    XposedHelpers.getObjectField(response, "status") as? Number
                    )?.toInt()
                val payload = XposedHelpers.getObjectField(
                    response,
                    "payload",
                ) as? ByteArray
                if (status != 0 || payload == null || payload.size < 5) {
                    return@runCatching null
                }
                for (index in 0 until payload.size - 2) {
                    if (
                        (payload[index].toInt() and 0xff) == 0x85 &&
                        (payload[index + 1].toInt() and 0xff) == 1
                    ) {
                        val value = payload[index + 2].toInt() and 0xff
                        return@runCatching value.takeIf { it == 0 || it == 1 }
                    }
                }
                null
            }.getOrElse {
                XposedBridge.log("$TAG: failed to snapshot DH85")
                XposedBridge.log(it)
                null
            }
        }

        private fun writeDh85Mode(value: Int): Boolean {
            if (value != 0 && value != 1) return false
            return runCatching {
                val current = readDh85Mode()
                val success = if (current == value) {
                    true
                } else {
                    val deviceHost = XposedHelpers.getObjectField(
                        nfcService,
                        "mDeviceHost",
                    )
                    XposedHelpers.callMethod(deviceHost, "disableDiscovery")
                    try {
                        val response = sendRawCoreCommand(
                            0x02,
                            byteArrayOf(
                                0x01,
                                0x85.toByte(),
                                0x01,
                                value.toByte(),
                            ),
                        ) ?: return@runCatching false
                        val status = (
                            XposedHelpers.getObjectField(
                                response,
                                "status",
                            ) as? Number
                            )?.toInt()
                        val responsePayload = XposedHelpers.getObjectField(
                            response,
                            "payload",
                        ) as? ByteArray
                        val accepted =
                            status == 0 &&
                                (responsePayload == null ||
                                    responsePayload.isEmpty() ||
                                    responsePayload[0].toInt() == 0)
                        if (!accepted) {
                            XposedBridge.log(
                                "$TAG: DH85 CORE_SET rejected " +
                                    "status=$status payload=" +
                                    bytesToHex(responsePayload),
                            )
                        }
                        accepted
                    } finally {
                        XposedHelpers.callMethod(
                            nfcService,
                            "applyRouting",
                            true,
                        )
                    }
                }
                if (success) {
                    // ColorOS stores whether DH mode is enabled, which is the
                    // inverse of the raw controller value.
                    Settings.Global.putInt(
                        context.contentResolver,
                        "set_85_config_mode",
                        if (value == 0) 1 else 0,
                    )
                }
                success
            }.getOrElse {
                XposedBridge.log("$TAG: failed writing DH85")
                XposedBridge.log(it)
                false
            }
        }

        private fun sendRawCoreCommand(
            oid: Int,
            payload: ByteArray,
        ): Any? {
            val deviceHost = XposedHelpers.getObjectField(
                nfcService,
                "mDeviceHost",
            )
            return XposedHelpers.callMethod(
                deviceHost,
                "sendRawVendorCmd",
                1,
                0,
                oid,
                payload,
            )
        }

        private fun isNfcEnabled(): Boolean {
            return runCatching {
                XposedHelpers.callMethod(
                    nfcService,
                    "isNfcEnabled",
                ) as Boolean
            }.getOrDefault(false)
        }

        private fun isCompatEnabled(): Boolean {
            return true
        }

        private fun mirrorColorOsCardSettings(intent: Intent) {
            runCatching {
                val resolver = context.contentResolver
                Settings.Global.putString(resolver, "cardType", cardType)
                Settings.Global.putString(resolver, "aid", aid)
                Settings.Global.putString(
                    resolver,
                    "parameter_index",
                    parameterIndex,
                )
                val sak = intent.getStringExtra("sak")
                Settings.Global.putInt(
                    resolver,
                    "sak",
                    sak?.toIntOrNull(16) ?: 0,
                )
            }.onFailure {
                XposedBridge.log("$TAG: failed mirroring ColorOS card settings")
                XposedBridge.log(it)
            }
        }

        private fun scheduleTimeout(delayMs: Long) {
            handler.removeCallbacks(timeoutRollback)
            handler.postDelayed(timeoutRollback, delayMs.coerceAtLeast(1L))
        }

        private fun persistPageState() {
            preferences.edit()
                .putBoolean("swipe_page", swipePage)
                .putLong("swipe_started_at", swipeStartedAt)
                .apply()
        }

        private fun persistRuntimeState() {
            val editor = preferences.edit()
                .putBoolean("runtime_applied", runtimeApplied)
                .putString("saved_sak", bytesToHex(savedSak))
                .putString("saved_transit_config", savedTransitConfig)
            val dh85 = savedDh85Mode
            if (dh85 == null) {
                editor.remove("saved_dh85_mode")
            } else {
                editor.putInt("saved_dh85_mode", dh85)
            }
            editor.apply()
        }

        private fun bytesToHex(bytes: ByteArray?): String? {
            return bytes?.joinToString("") {
                String.format(Locale.ROOT, "%02X", it.toInt() and 0xff)
            }
        }

        private fun hexToBytes(value: String?): ByteArray? {
            if (
                value == null ||
                value.length < 2 ||
                value.length % 2 != 0 ||
                !value.matches(Regex("^[0-9A-Fa-f]+$"))
            ) {
                return null
            }
            return runCatching {
                ByteArray(value.length / 2) { index ->
                    value.substring(index * 2, index * 2 + 2)
                        .toInt(16)
                        .toByte()
                }
            }.getOrNull()
        }
    }

    /**
     * HyperOS 4 kept DefaultPaymentSettings but some vendor callers still
     * target the removed PaymentSettings fragment. Remap only that exact class
     * name through the stock Settings launch APIs; no custom settings page or
     * module UI is injected.
     */
    private fun hookNfcPaymentSettingsFragmentCompatibility(
        classLoader: ClassLoader,
    ) {
        var installed = 0
        val targets = listOf(
            "com.android.settings.SettingsActivity" to "switchToFragment",
            "com.android.settings.SettingsActivity" to "getStartingFragmentClass",
            "com.android.settings.Utils" to "getTargetFragment",
            "com.android.settings.core.SubSettingLauncher" to "setDestination",
        )
        targets.forEach { (className, methodName) ->
            val targetClass = runCatching {
                XposedHelpers.findClass(className, classLoader)
            }.getOrNull() ?: return@forEach
            runCatching {
                val hooks = XposedBridge.hookAllMethods(
                    targetClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.args.indices.forEach { index ->
                                if (param.args[index] == MISSING_NFC_PAYMENT_FRAGMENT) {
                                    param.args[index] = WORKING_NFC_PAYMENT_FRAGMENT
                                }
                            }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (param.result == MISSING_NFC_PAYMENT_FRAGMENT) {
                                param.result = WORKING_NFC_PAYMENT_FRAGMENT
                            }
                        }
                    },
                )
                installed += hooks.size
            }.onFailure {
                XposedBridge.log(
                    "$TAG: NFC payment fragment hook unavailable " +
                        "$className.$methodName",
                )
            }
        }
        XposedBridge.log(
            "$TAG: stock NFC payment settings compatibility installed " +
                "hooks=$installed",
        )
    }

    /**
     * HyperOS 4 / Android 17 builds the original TECH candidate list correctly,
     * but DispatchInfo.tryStartActivity(Intent) rejects its own explicit,
     * non-exported TechListChooserActivity while checking whether the target is
     * launchable. The method then returns false before NfcRootActivity is ever
     * started.
     *
     * Preserve Xiaomi's complete candidate list and all TagIntentAppPreference
     * decisions. Only when the stock method has already failed for the internal
     * chooser do we continue through the root intent it originally prepared.
     * NDEF/AAR, foreground dispatch, Reader Mode and HCE never match this hook.
     */
    private fun hookNfcTechChooserCompat(classLoader: ClassLoader) {
        runCatching {
            val dispatchInfo = XposedHelpers.findClass(
                "com.android.nfc.NfcDispatcher\$DispatchInfo",
                classLoader,
            )
            val hooks = XposedBridge.hookAllMethods(
                dispatchInfo,
                "tryStartActivity",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable() || param.result != false) return
                        if (param.args.size != 1) return

                        val chooserIntent = param.args[0] as? Intent ?: return
                        val component = chooserIntent.component ?: return
                        if (component.packageName != NFC_PACKAGE ||
                            !component.className.endsWith(".TechListChooserActivity")
                        ) return

                        val rootIntent = XposedHelpers.getObjectField(
                            param.thisObject,
                            "rootIntent",
                        ) as? Intent ?: return
                        val context = XposedHelpers.getObjectField(
                            param.thisObject,
                            "context",
                        ) as? Context ?: return
                        val userHandleClass = XposedHelpers.findClass(
                            "android.os.UserHandle",
                            null,
                        )
                        val currentUser = XposedHelpers.getStaticObjectField(
                            userHandleClass,
                            "CURRENT",
                        )

                        rootIntent.putExtra("launchIntent", chooserIntent)
                        XposedHelpers.callMethod(
                            context,
                            "startActivityAsUser",
                            rootIntent,
                            currentUser,
                        )
                        param.result = true
                        XposedBridge.log(
                            "$TAG: resumed stock Android 17 NFC TECH chooser",
                        )
                    }
                },
            )
            check(hooks.isNotEmpty()) {
                "DispatchInfo.tryStartActivity hook target not found"
            }
            XposedBridge.log(
                "$TAG: stock NFC TECH chooser compatibility installed (${hooks.size})",
            )
        }.onFailure {
            XposedBridge.log("$TAG: NFC TECH chooser compatibility unavailable")
            XposedBridge.log(it)
        }
    }

    private fun isColorOsWalletMode(context: Context): Boolean {
        return true
    }

    private fun isMiuiAutomaticNfcLaunch(intent: Intent): Boolean {
        if (intent.action != MIUI_DOUBLE_CLICK_ACTION) return false
        val sourceType = intent.getStringExtra(MIUI_EVENT_SOURCE_TYPE)
        return sourceType in MIUI_AUTOMATIC_NFC_EVENT_TYPES
    }

    /**
     * Mi TSM's NFC event service turns RF_ON/TRANSACTION notifications into a
     * NEW_TASK launch of DoubleClickActivity. When FinShell is the wallet role
     * holder this steals foreground from the actual wallet, even though the
     * secure-element transaction has already been routed correctly.
     *
     * Stop that automatic launch at ContextImpl, before ActivityTaskManager
     * creates a task. This hook runs only inside the Mi TSM process and only
     * matches intents carrying Mi TSM's own NFC event_source_type marker.
     * User-initiated power-button shortcuts have no such marker and continue
     * through the dedicated redirect below.
     */
    private fun hookMiuiAutomaticNfcActivityLaunch() {
        runCatching {
            val contextImpl = XposedHelpers.findClass(
                "android.app.ContextImpl",
                null,
            )
            XposedBridge.hookAllMethods(
                contextImpl,
                "startActivity",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as? Context ?: return
                        if (!isColorOsWalletMode(context)) return
                        val intent = param.args.firstOrNull { it is Intent }
                            as? Intent ?: return
                        if (!isMiuiAutomaticNfcLaunch(intent)) return
                        val targetPackage = intent.`package`
                        if (targetPackage != null &&
                            targetPackage != MIUI_TSM_PACKAGE
                        ) {
                            return
                        }

                        param.result = null
                        XposedBridge.log(
                            "$TAG: suppressed Mi TSM automatic NFC foreground launch " +
                                intent.getStringExtra(MIUI_EVENT_SOURCE_TYPE),
                        )
                    }
                },
            )
            XposedBridge.log(
                "$TAG: Mi TSM automatic NFC foreground guard installed",
            )
        }.onFailure {
            XposedBridge.log(
                "$TAG: Mi TSM automatic NFC foreground guard unavailable",
            )
            XposedBridge.log(it)
        }
    }

    /**
     * HyperOS sends the double-press transit-card shortcut to Mi TSM's
     * DoubleClickActivity. Redirect only that dedicated entry activity to
     * FinShell's card-package page; normal Mi TSM launches are untouched.
     */
    private fun hookMiuiTransitShortcut(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                MIUI_TRANSIT_SHORTCUT,
                classLoader,
                "onCreate",
                Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        if (!isColorOsWalletMode(activity)) return

                        // Fallback for launch paths that bypass ContextImpl in
                        // this process. Do not redirect an automatic RF/HCI
                        // notification into the visible FinShell card page.
                        if (isMiuiAutomaticNfcLaunch(activity.intent)) {
                            activity.overridePendingTransition(0, 0)
                            activity.finish()
                            activity.overridePendingTransition(0, 0)
                            XposedBridge.log(
                                "$TAG: finished Mi TSM automatic NFC activity fallback",
                            )
                            return
                        }

                        val redirect = Intent().apply {
                            component = ComponentName(
                                "com.finshell.wallet",
                                FIN_SHELL_CARD_PACKAGE,
                            )
                            putExtra(FIN_SHELL_LOCKSCREEN_SESSION, true)
                            addFlags(0x00080000)
                        }
                        activity.startActivity(redirect)
                        activity.finish()
                        XposedBridge.log(
                            "$TAG: redirected Mi TSM transit shortcut to FinShell",
                        )
                    }
                },
            )
            XposedBridge.log("$TAG: Mi TSM transit shortcut redirect installed")
        }.onFailure {
            XposedBridge.log("$TAG: Mi TSM transit shortcut redirect unavailable")
            XposedBridge.log(it)
        }
    }

    /**
     * FinShell's card-package entry can be shown over the keyguard, but most
     * activities opened after choosing a card do not declare showWhenLocked.
     * Android 17 therefore pauses the selected card page with reason=sleep as
     * soon as it becomes top, which looks like the wallet disappeared.
     *
     * Mark only redirects from the physical power-button shortcut. During a
     * short-lived session, keep FinShell's following activity visible over the
     * still-locked keyguard. This does not dismiss the keyguard, authenticate
     * the user, or alter any NFC/card routing state. Normal wallet launches
     * and automatic RF/HCI notifications never activate the session.
     */
    private fun hookFinShellLockscreenWalletSession() {
        runCatching {
            fun updateActivity(activity: Activity, intent: Intent?) {
                if (intent?.getBooleanExtra(
                        FIN_SHELL_LOCKSCREEN_SESSION,
                        false,
                    ) == true
                ) {
                    lockscreenWalletSessionUntil =
                        SystemClock.elapsedRealtime() +
                            LOCKSCREEN_WALLET_SESSION_MS
                    // Avoid accidentally forwarding the marker elsewhere.
                    intent.removeExtra(FIN_SHELL_LOCKSCREEN_SESSION)
                    XposedBridge.log(
                        "$TAG: FinShell lockscreen wallet session started",
                    )
                }

                if (SystemClock.elapsedRealtime() >=
                    lockscreenWalletSessionUntil
                ) {
                    return
                }
                val keyguard = activity.getSystemService(
                    Context.KEYGUARD_SERVICE,
                ) as? KeyguardManager ?: return
                if (!keyguard.isKeyguardLocked) return

                activity.setShowWhenLocked(true)
                XposedBridge.log(
                    "$TAG: kept ${activity.javaClass.name} visible over keyguard",
                )
            }

            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        updateActivity(activity, activity.intent)
                    }
                },
            )
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onNewIntent",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val intent = param.args.firstOrNull() as? Intent
                        updateActivity(activity, intent)
                    }
                },
            )
            XposedBridge.hookAllMethods(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        updateActivity(activity, activity.intent)
                    }
                },
            )
            XposedBridge.log(
                "$TAG: FinShell lockscreen wallet continuity installed",
            )
        }.onFailure {
            XposedBridge.log(
                "$TAG: FinShell lockscreen wallet continuity unavailable",
            )
            XposedBridge.log(it)
        }
    }

    /**
     * FinShell accepts exactly one eID bridge marked as platform-signed.
     * Select the original OPlus bridge matching this device's physical eID HAL
     * and exclude the transplanted Xiaomi bridge from this local decision.
     */
    private fun hookEidServiceSelection(classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.ApplicationPackageManager",
                classLoader,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String ?: return
                        val packageInfo = param.result as? PackageInfo ?: return
                        val applicationInfo = packageInfo.applicationInfo ?: return
                        val platformKeyFlag = 0x00100000
                        val privateFlags = XposedHelpers.getIntField(
                            applicationInfo,
                            "privateFlags",
                        )
                        when (packageName) {
                            "com.oplus.eid" -> {
                                XposedHelpers.setIntField(
                                    applicationInfo,
                                    "privateFlags",
                                    privateFlags or platformKeyFlag,
                                )
                            }
                            "com.rongcard.eid" -> {
                                XposedHelpers.setIntField(
                                    applicationInfo,
                                    "privateFlags",
                                    privateFlags and platformKeyFlag.inv(),
                                )
                            }
                            else -> return
                        }
                    }
                },
            )
            XposedBridge.log("$TAG: OPlus eID bridge selection installed")
        }.onFailure {
            XposedBridge.log("$TAG: eID bridge selection hook unavailable")
            XposedBridge.log(it)
        }
    }

    /**
     * ColorOS SecureElement adds an OPlus fallback around the standard
     * GlobalPlatform access-rule check. The transplanted AOSP/Xiaomi
     * SecureElement service does not contain that extension, so the genuine
     * FinShell/TAS certificates are rejected even though the eSE is present.
     *
     * Recreate only the narrow package+certificate decision. No wildcard,
     * no arbitrary caller and no change to on-card ARA-M rules.
     */
    private fun hookSecureElementOplusAccessFallback(classLoader: ClassLoader) {
        runCatching {
            val enforcer = XposedHelpers.findClass(
                "com.android.se.security.AccessControlEnforcer",
                classLoader,
            )
            val channelAccess = XposedHelpers.findClass(
                "com.android.se.security.ChannelAccess",
                classLoader,
            )
            XposedBridge.hookAllMethods(enforcer, "setUpChannelAccess", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val packageName = param.args.getOrNull(1) as? String ?: return
                    if (!hasExpectedSigner(packageName)) return

                    param.result = XposedHelpers.callStaticMethod(
                        channelAccess,
                        "getPrivilegeAccess",
                        packageName,
                        Binder.getCallingPid(),
                    )
                    XposedBridge.log(
                        "$TAG: granted donor-equivalent eSE access to $packageName",
                    )
                }
            })
            XposedBridge.log("$TAG: SecureElement OPlus access fallback installed")
        }.onFailure {
            XposedBridge.log("$TAG: SecureElement access fallback unavailable")
            XposedBridge.log(it)
        }
    }

    private fun hasExpectedSigner(packageName: String): Boolean {
        val expected = ESE_ALLOWED_SIGNERS[packageName] ?: return false
        val activityThread = XposedHelpers.findClass("android.app.ActivityThread", null)
        val application = XposedHelpers.callStaticMethod(
            activityThread,
            "currentApplication",
        ) as? Context ?: return false
        return runCatching {
            val packageInfo = application.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val signingInfo = packageInfo.signingInfo ?: return@runCatching false
            val certificates = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            certificates.any { signature ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte ->
                        String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
                    }
                digest in expected
            }
        }.getOrElse {
            XposedBridge.log("$TAG: signer verification failed for $packageName")
            XposedBridge.log(it)
            false
        }
    }

    /**
     * FinShell references OSense classes from the device's OPlus framework,
     * but its manifest does not request the oplus-fwk shared library on this
     * transplanted system. Prefer an already-installed framework jar. If the
     * ROM does not provide one, extract the bundled compatibility jar into
     * FinShell's private code cache and add that read-only copy only to the
     * wallet process. The system boot class path and signed wallet APK remain
     * untouched, and a camera/framework Magisk module is not required.
     */
    private fun makeOplusFrameworkVisible(classLoader: ClassLoader) {
        if (isOplusFrameworkVisible(classLoader)) {
            return
        }

        val frameworkPaths = listOf(
            "/system/framework/oplus-fwk.jar",
            "/system/system/framework/oplus-fwk.jar",
            "/system_ext/framework/oplus-fwk.jar",
            "/system/system_ext/framework/oplus-fwk.jar",
            "/product/framework/oplus-fwk.jar",
            "/system/product/framework/oplus-fwk.jar",
        )
        for (path in frameworkPaths) {
            if (addFrameworkDexPath(classLoader, path)) {
                XposedBridge.log("$TAG: exposed $path to FinShell")
                return
            }
        }

        val application = currentApplicationContext()
        if (application != null &&
            exposeEmbeddedOplusFramework(application, classLoader)
        ) {
            return
        }

        installEmbeddedOplusFrameworkAttachHook(classLoader)
        XposedBridge.log("$TAG: deferred bundled oplus-fwk loading until Application.attach")
    }

    private fun isOplusFrameworkVisible(classLoader: ClassLoader): Boolean =
        runCatching {
            XposedHelpers.findClass(OPLUS_FRAMEWORK_CALLBACK_CLASS, classLoader)
        }.isSuccess

    private fun addFrameworkDexPath(
        classLoader: ClassLoader,
        frameworkPath: String,
    ): Boolean {
        val frameworkFile = File(frameworkPath)
        if (!frameworkFile.isFile || !frameworkFile.canRead()) return false
        return runCatching {
            XposedHelpers.callMethod(classLoader, "addDexPath", frameworkPath)
            XposedHelpers.findClass(OPLUS_FRAMEWORK_CALLBACK_CLASS, classLoader)
        }.isSuccess
    }

    private fun installEmbeddedOplusFrameworkAttachHook(classLoader: ClassLoader) {
        if (oplusFrameworkAttachHookInstalled) return
        oplusFrameworkAttachHookInstalled = true

        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (isOplusFrameworkVisible(classLoader)) return
                        val context = param.args.firstOrNull() as? Context ?: return
                        if (exposeEmbeddedOplusFramework(context, classLoader)) {
                            WalletCapabilityCompat.installOplusFeatures(classLoader)
                            hookFinShellUnfreezeCompat(classLoader)
                        }
                    }
                },
            )
        }.onFailure {
            oplusFrameworkAttachHookInstalled = false
            XposedBridge.log("$TAG: unable to schedule bundled oplus-fwk loading")
            XposedBridge.log(it)
        }
    }

    private fun exposeEmbeddedOplusFramework(
        context: Context,
        classLoader: ClassLoader,
    ): Boolean {
        if (isOplusFrameworkVisible(classLoader)) return true
        val frameworkFile = extractEmbeddedOplusFramework(context) ?: return false
        val added = addFrameworkDexPath(classLoader, frameworkFile.absolutePath)
        if (added) {
            XposedBridge.log("$TAG: exposed bundled oplus-fwk to FinShell")
        } else {
            XposedBridge.log("$TAG: bundled oplus-fwk was extracted but could not be loaded")
        }
        return added
    }

    private fun extractEmbeddedOplusFramework(context: Context): File? = runCatching {
        val apkPath = moduleApkPath?.takeIf { it.isNotBlank() }
            ?: error("module APK path unavailable")
        val outputDirectory = File(context.codeCacheDir, "coloros_wallet_compat")
        check(outputDirectory.isDirectory || outputDirectory.mkdirs())

        val outputFile = File(
            outputDirectory,
            "oplus-fwk-${EMBEDDED_OPLUS_FRAMEWORK_SHA256.take(12)}.jar",
        )
        if (outputFile.isFile &&
            sha256File(outputFile) == EMBEDDED_OPLUS_FRAMEWORK_SHA256
        ) {
            check(outputFile.setReadOnly() || !outputFile.canWrite())
            return@runCatching outputFile
        }

        val temporaryFile = File(
            outputDirectory,
            "${outputFile.name}.${android.os.Process.myPid()}.tmp",
        )
        if (temporaryFile.exists()) check(temporaryFile.delete())
        ZipFile(apkPath).use { moduleApk ->
            val asset = moduleApk.getEntry(EMBEDDED_OPLUS_FRAMEWORK_ASSET)
                ?: error("bundled oplus-fwk asset missing")
            moduleApk.getInputStream(asset).use { input ->
                temporaryFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        check(sha256File(temporaryFile) == EMBEDDED_OPLUS_FRAMEWORK_SHA256)
        if (outputFile.isFile &&
            sha256File(outputFile) == EMBEDDED_OPLUS_FRAMEWORK_SHA256
        ) {
            check(temporaryFile.delete())
        } else {
            if (outputFile.exists()) check(outputFile.delete())
            if (!temporaryFile.renameTo(outputFile)) {
                check(
                    outputFile.isFile &&
                        sha256File(outputFile) == EMBEDDED_OPLUS_FRAMEWORK_SHA256,
                )
                check(temporaryFile.delete())
            }
        }
        check(outputFile.setReadOnly() || !outputFile.canWrite())
        outputFile
    }.onFailure {
        XposedBridge.log("$TAG: failed to prepare bundled oplus-fwk")
        XposedBridge.log(it)
    }.getOrNull()

    private fun sha256File(file: File): String =
        file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { byte ->
                String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
            }
        }

    private fun spoofBuildFields() {
        BUILD_FIELDS.forEach { (field, value) ->
            runCatching {
                XposedHelpers.setStaticObjectField(Build::class.java, field, value)
            }.onFailure {
                XposedBridge.log("$TAG: failed to set Build.$field")
                XposedBridge.log(it)
            }
        }
    }

    private fun hookSystemProperties() {
        runCatching {
            val systemProperties = XposedHelpers.findClass("android.os.SystemProperties", null)
            XposedBridge.hookAllMethods(systemProperties, "get", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args.firstOrNull() as? String ?: return
                    PROPERTY_VALUES[key]?.let { param.result = it }
                }
            })
            XposedBridge.hookAllMethods(
                systemProperties,
                "getBoolean",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args.firstOrNull() as? String ?: return
                        val value = PROPERTY_VALUES[key] ?: return
                        when {
                            value.equals("true", ignoreCase = true) ||
                                value == "1" -> param.result = true
                            value.equals("false", ignoreCase = true) ||
                                value == "0" -> param.result = false
                        }
                    }
                },
            )
        }.onFailure {
            XposedBridge.log("$TAG: failed to hook SystemProperties identity reads")
            XposedBridge.log(it)
        }
    }

    /**
     * Keep FinShell's local NFC capability predicates consistent across the
     * 5.44 and 5.47 package layouts. These hooks only repair exact NFC/eSE
     * checks and the platform default-payment result; unrelated wallet gates,
     * server responses and secure-element identifiers remain untouched.
     */
    private fun hookFinShellNfcEnvironment(classLoader: ClassLoader) {
        runCatching {
            val supportClass = XposedHelpers.findClass(
                "com.nearme.utils.ba",
                classLoader,
            )
            XposedHelpers.findAndHookMethod(
                supportClass,
                "a",
                XC_MethodReplacement.returnConstant(true),
            )
            XposedBridge.log("$TAG: FinShell OPlus NFC capability gate installed")
        }.onFailure {
            XposedBridge.log("$TAG: FinShell OPlus NFC capability gate unavailable")
        }

        var facadeHooks = 0
        listOf(
            "com.nearme.nfc.e.e",
            "com.nearme.nfc.utils.e",
        ).forEach { className ->
            val facade = runCatching {
                XposedHelpers.findClass(className, classLoader)
            }.getOrNull() ?: return@forEach

            runCatching {
                XposedHelpers.findAndHookMethod(
                    facade,
                    "m",
                    Context::class.java,
                    XC_MethodReplacement.returnConstant(true),
                )
                facadeHooks++
            }

            listOf("d", "h").forEach { methodName ->
                runCatching {
                    XposedHelpers.findAndHookMethod(
                        facade,
                        methodName,
                        Context::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (param.result == true) return
                                val context = param.args.firstOrNull() as? Context
                                    ?: currentApplicationContext()
                                    ?: return
                                if (!isColorOsWalletMode(context)) return
                                val aligned = when (methodName) {
                                    "d" -> isEmbeddedSecureElementConfigured(context)
                                    "h" -> isFinShellDefaultPayment(context)
                                    else -> false
                                }
                                if (aligned) param.result = true
                            }
                        },
                    )
                    facadeHooks++
                }
            }
        }

        hookWalletMultiseReadFallback()
        hookWalletDefaultPaymentResult()
        XposedBridge.log(
            "$TAG: FinShell NFC environment compatibility installed " +
                "facadeHooks=$facadeHooks",
        )
    }

    private fun hookWalletMultiseReadFallback() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args.getOrNull(1) as? String ?: return
                if (key != NFC_MULTISE_ACTIVE && key != NFC_MULTISE_LIST) return
                val current = param.result as? String
                if (!current.isNullOrBlank() && current != "null") return
                val context = currentApplicationContext() ?: return
                if (isColorOsWalletMode(context)) {
                    param.result = EMBEDDED_SE_ROUTE
                }
            }
        }
        listOf(Settings.Global::class.java, Settings.System::class.java).forEach {
            settingsClass ->
            runCatching {
                XposedHelpers.findAndHookMethod(
                    settingsClass,
                    "getString",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    hook,
                )
            }.onFailure {
                XposedBridge.log(
                    "$TAG: ${settingsClass.simpleName} multise read fallback unavailable",
                )
            }
        }
    }

    private fun hookWalletDefaultPaymentResult() {
        runCatching {
            val cardEmulation = XposedHelpers.findClass(
                "android.nfc.cardemulation.CardEmulation",
                null,
            )
            XposedBridge.hookAllMethods(
                cardEmulation,
                "isDefaultServiceForCategory",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == true) return
                        val component = param.args.firstOrNull {
                            it is ComponentName
                        } as? ComponentName ?: return
                        val category = param.args.firstOrNull {
                            it is String
                        } as? String
                        if (category != null && category != "payment") return
                        if (component.packageName != FIN_SHELL_PACKAGE ||
                            component.className != FIN_SHELL_CARD_SERVICE
                        ) {
                            return
                        }
                        val context = currentApplicationContext() ?: return
                        if (isColorOsWalletMode(context) &&
                            isFinShellDefaultPayment(context)
                        ) {
                            param.result = true
                        }
                    }
                },
            )
        }.onFailure {
            XposedBridge.log("$TAG: FinShell default-payment result hook unavailable")
            XposedBridge.log(it)
        }
    }

    private fun isFinShellDefaultPayment(context: Context): Boolean {
        return runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                NFC_PAYMENT_DEFAULT_COMPONENT,
            ) == FIN_SHELL_PAYMENT_COMPONENT
        }.getOrDefault(false)
    }

    private fun hookFinShellLocalVendorGate(lpparam: LoadPackageParam) {
        val classLoader = lpparam.classLoader

        // The lifecycle callback otherwise presents a non-dismissible
        // "unsupported vendor" dialog and exits the wallet.
        runCatching {
            val lifecycle = XposedHelpers.findClass(
                "com.nearme.common.lib.BaseActivityLifecycleCallbacks",
                classLoader,
            )
            XposedBridge.hookAllConstructors(lifecycle, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    XposedHelpers.setBooleanField(param.thisObject, "isNeedShowDialog", false)
                }
            })
            XposedBridge.hookAllMethods(
                lifecycle,
                "showForbidRunDialog",
                XC_MethodReplacement.returnConstant(null),
            )
        }.onFailure {
            XposedBridge.log("$TAG: lifecycle vendor-gate hook unavailable")
            XposedBridge.log(it)
        }

        // Current FinShell implementation:
        // c(Context) == third-party vendor, d(Context) == OnePlus,
        // e(Context) == any supported OPlus-family vendor.
        runCatching {
            val vendorUtil = XposedHelpers.findClass(
                "com.finshell.common.util.i",
                classLoader,
            )
            XposedHelpers.findAndHookMethod(
                vendorUtil,
                "c",
                Context::class.java,
                XC_MethodReplacement.returnConstant(false),
            )
            XposedHelpers.findAndHookMethod(
                vendorUtil,
                "d",
                Context::class.java,
                XC_MethodReplacement.returnConstant(true),
            )
            XposedHelpers.findAndHookMethod(
                vendorUtil,
                "e",
                Context::class.java,
                XC_MethodReplacement.returnConstant(true),
            )
        }.onFailure {
            XposedBridge.log("$TAG: FinShell vendor utility hook unavailable")
            XposedBridge.log(it)
        }
    }

    /**
     * FinShell builds transit-service headers in a cached vendor layer. On a
     * transplanted system those two fields can retain the Xiaomi donor model
     * even though Build/SystemProperties have already been aligned. Rewrite
     * only the official transit endpoint's explicit model and OTA headers.
     * Other wallet requests, authentication material and response bodies are
     * not changed.
     */
    private fun hookFinShellTransitRequestIdentity(classLoader: ClassLoader) {
        val headerHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val original = param.result as? Map<*, *> ?: return
                val requestUrl = findRequestUrl(param.args)
                if (!requestUrl.contains(TRANSIT_REQUEST_PATH)) return

                val rewritten = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (original as MutableMap<Any?, Any?>).also { headers ->
                        rewriteTransitIdentityHeaders(headers)
                    }
                }.getOrElse {
                    HashMap<Any?, Any?>(original).also { headers ->
                        rewriteTransitIdentityHeaders(headers)
                    }
                }
                param.result = rewritten
            }
        }

        var installed = 0
        listOf(
            "com.nearme.network.header.d",
            "com.nearme.network.header.b",
        ).forEach { className ->
            val headerClass = runCatching {
                XposedHelpers.findClass(className, classLoader)
            }.getOrNull() ?: return@forEach
            headerClass.declaredMethods.forEach { method ->
                if (method.name != "a" ||
                    !Map::class.java.isAssignableFrom(method.returnType)
                ) {
                    return@forEach
                }
                runCatching {
                    XposedBridge.hookMethod(method, headerHook)
                    installed++
                }
            }
        }
        XposedBridge.log(
            "$TAG: FinShell transit request identity installed hooks=$installed",
        )
    }

    private fun findRequestUrl(arguments: Array<Any?>): String {
        arguments.forEach { argument ->
            if (argument is String && argument.contains(TRANSIT_REQUEST_PATH)) {
                return argument
            }
            if (argument == null) return@forEach
            val url = runCatching {
                XposedHelpers.callMethod(argument, "getUrl") as? String
            }.getOrNull()
            if (!url.isNullOrBlank()) return url
        }
        return ""
    }

    private fun rewriteTransitIdentityHeaders(headers: MutableMap<Any?, Any?>) {
        headers["--p-c-model--"] = ONEPLUS13_MODEL
        if (headers["model"] is String) {
            headers["model"] = ONEPLUS13_MODEL
        }
        headers["otav"] = ONEPLUS13_OFFICIAL_OTA
    }

    /**
     * OSense is used here only to keep the wallet process temporarily
     * unfrozen around an APDU job. The wallet already ships an empty manager
     * for systems where that optimization is unavailable. Force that safe
     * fallback so NFC/eSE work is not coupled to the donor ROM's OSense
     * service implementation.
     */
    private fun hookFinShellUnfreezeCompat(classLoader: ClassLoader) {
        if (finShellUnfreezeHookInstalled) return
        runCatching {
            val manager = XposedHelpers.findClass(
                "com.finshell.unfreeze.c",
                classLoader,
            )
            XposedHelpers.findAndHookMethod(
                manager,
                "a",
                XC_MethodReplacement.returnConstant(false),
            )
            finShellUnfreezeHookInstalled = true
            XposedBridge.log("$TAG: FinShell OSense unfreeze fallback enabled")
        }.onFailure {
            XposedBridge.log("$TAG: FinShell OSense fallback hook unavailable")
            XposedBridge.log(it)
        }
    }
}

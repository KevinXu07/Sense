package local.mio.coloroswalletcompat

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Locale

/** sysystem's wider NFC feature/permission aliases, confined to wallet clients. */
internal object WalletCapabilityCompat {
    private val hookedFeatureClasses = mutableSetOf<Class<*>>()
    private val logged = mutableSetOf<String>()
    private val esePermissions = setOf(
        "com.oplus.permission.ACCESS_ESE",
        "com.miui.permission.ACCESS_ESE",
        "android.permission.SECURE_ELEMENT_PRIVILEGED_OPERATION",
    )
    private val hardwareFeatures = setOf(
        "android.hardware.nfc", "android.hardware.nfc.any",
        "android.hardware.nfc.hce", "android.hardware.nfc.hcef",
        "android.hardware.nfc.ese", "android.hardware.nfc.uicc",
        "android.hardware.se.omapi.ese", "android.hardware.se.omapi.uicc",
        "com.nxp.mifare",
    )

    fun install(loader: ClassLoader, packageName: String) {
        val pm = XposedHelpers.findClass("android.app.ApplicationPackageManager", loader)
        XposedBridge.hookAllMethods(pm, "hasSystemFeature", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || param.result == true) return
                val feature = param.args.firstOrNull() as? String ?: return
                if (supports(feature)) {
                    param.result = true
                    logOnce("feature:$feature")
                }
            }
        })
        XposedBridge.hookAllMethods(pm, "checkPermission", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || param.result == PackageManager.PERMISSION_GRANTED) return
                val permission = param.args.getOrNull(0) as? String ?: return
                val targetPackage = param.args.getOrNull(1) as? String ?: return
                // A client-side vendor-name alias only. The actual OMAPI
                // service still performs its signer and channel access checks.
                if (targetPackage == packageName && permission in esePermissions) {
                    param.result = PackageManager.PERMISSION_GRANTED
                    logOnce("permission-alias:$permission")
                }
            }
        })
        installOplusFeatures(loader)
        XposedBridge.hookAllMethods(Application::class.java, "attach", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.args.firstOrNull() as? Context ?: return
                installOplusFeatures(context.classLoader)
            }
        })
    }

    fun installOplusFeatures(loader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists("com.oplus.content.OplusFeatureConfigManager", loader) ?: return
        synchronized(hookedFeatureClasses) {
            if (!hookedFeatureClasses.add(cls)) return
        }
        cls.declaredMethods.filter {
            it.name in setOf("hasFeature", "hasFeatureMap", "hasFeatureRPC", "isEnabled", "isSupport") &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.firstOrNull() == String::class.java
        }.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val feature = param.args.firstOrNull() as? String ?: return
                        if (param.result != true && supports(feature)) {
                            param.result = true
                            logOnce("oplus-feature:$feature")
                        }
                    }
                })
            }.onFailure { XposedBridge.log("${FixedWalletPolicy.TAG}: feature method unavailable: $it") }
        }
    }

    private fun supports(feature: String): Boolean {
        val key = feature.lowercase(Locale.ROOT)
        // Missing vendor command transports must keep using the existing
        // explicit broadcast fallback. Hardware advertising cannot add an API.
        if (listOf("beam", "charging", "sofware.nfc", "set_config", "setconfig",
                "attest", "keystore", "serial", "imei", "tee").any { it in key }) return false
        if (key in hardwareFeatures) return true
        if (!key.startsWith("oplus.") && !key.startsWith("com.oplus.") &&
            !key.startsWith("oppo.") && !key.startsWith("com.oppo.")) return false
        return listOf("nfc", "ese", "hce", "wallet", "omapi").any { it in key }
    }

    private fun logOnce(key: String) {
        synchronized(logged) {
            if (logged.add(key)) XposedBridge.log("${FixedWalletPolicy.TAG}: wallet capability $key")
        }
    }
}

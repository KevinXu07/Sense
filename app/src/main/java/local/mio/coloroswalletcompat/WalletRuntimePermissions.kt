package local.mio.coloroswalletcompat

import android.app.AppOpsManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Persistent, package-scoped grants previously applied by companion common.sh. */
internal object WalletRuntimePermissions {
    private const val TAG = FixedWalletPolicy.TAG
    private val errors = mutableSetOf<String>()
    private var installed = false

    fun installSystemHooks(loader: ClassLoader) {
        runCatching {
            val ams = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", loader)
            XposedBridge.hookAllMethods(ams, "systemReady", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        install(XposedHelpers.getObjectField(param.thisObject, "mContext") as Context)
                    }.onFailure { XposedBridge.log("$TAG: wallet policy startup unavailable: $it") }
                }
            })
        }.onFailure { XposedBridge.log("$TAG: wallet system policy hook unavailable: $it") }
    }

    // Settings already runs as the system UID in this ROM and is an existing
    // module scope. This one-shot path supports hot updates before system_server
    // next starts with the added android scope. Boot independence uses AMS above.
    fun installSettingsFallback() {
        XposedBridge.hookAllMethods(Application::class.java, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val context = param.thisObject as? Context ?: return
                Handler(Looper.getMainLooper()).post { reconcile(context) }
            }
        })
    }

    private fun install(context: Context) {
        if (installed) return
        installed = true
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable { reconcile(context) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data != null &&
                    intent.data?.schemeSpecificPart !in FixedWalletPolicy.WALLET_PACKAGES
                ) return
                handler.removeCallbacks(task)
                handler.postDelayed(task, 1_000L)
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BOOT_COMPLETED)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }, null, handler, Context.RECEIVER_EXPORTED)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }, null, handler, Context.RECEIVER_EXPORTED)
        handler.postDelayed(task, 20_000L)
        XposedBridge.log("$TAG: wallet runtime permission policy installed")
    }

    private fun reconcile(context: Context) {
        if (android.os.Process.myUid() != 1000) return
        val token = Binder.clearCallingIdentity()
        try {
            val owner = XposedHelpers.callStaticMethod(UserHandle::class.java, "of", 0) as UserHandle
            val ownerContext = XposedHelpers.callMethod(context, "createContextAsUser", owner, 0) as Context
            val pm = ownerContext.packageManager
            val appOps = ownerContext.getSystemService(AppOpsManager::class.java) ?: return
            var writes = 0
            for (pkg in FixedWalletPolicy.WALLET_PACKAGES) {
                val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
                runCatching {
                    // MIUI's installed AppOpsManager implementation owns code
                    // 10008. On other ROMs do not invent a vendor operation.
                    val name = XposedHelpers.callStaticMethod(AppOpsManager::class.java, "opToName", 10008) as? String
                    if (name != null && name != "NONE" && !name.startsWith("Unknown")) {
                        val mode = (XposedHelpers.callMethod(appOps, "checkOpNoThrow", 10008, info.uid, pkg) as Number).toInt()
                        if (mode != AppOpsManager.MODE_ALLOWED) {
                            XposedHelpers.callMethod(appOps, "setMode", 10008, info.uid, pkg, AppOpsManager.MODE_ALLOWED)
                            writes++
                        }
                    }
                }.onFailure { logOnce("background:$pkg", it) }
                if (pkg == FixedWalletPolicy.WALLET || pkg == "com.heytap.tas") {
                    runCatching {
                        val permission = FixedWalletPolicy.EVENT_PERMISSION
                        val requested = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions
                        if (requested?.contains(permission) == true &&
                            pm.checkPermission(permission, pkg) != PackageManager.PERMISSION_GRANTED
                        ) {
                            XposedHelpers.callMethod(pm, "grantRuntimePermission", pkg, permission, owner)
                            writes++
                        }
                    }.onFailure { logOnce("broadcast:$pkg", it) }
                }
            }
            if (writes > 0) XposedBridge.log("$TAG: wallet runtime permission grants updated=$writes")
        } catch (error: Throwable) {
            logOnce("runtime", error)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun logOnce(key: String, error: Throwable) {
        if (errors.add(key)) XposedBridge.log("$TAG: wallet permission $key unavailable: $error")
    }
}

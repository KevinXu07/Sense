package com.zephiel.sense.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Single LSPosed entry for Sense.
 *
 * Sense is both the control UI and the only LSPosed module. The RMX5010
 * compatibility hooks and the ColorOS wallet compatibility hooks live in this
 * APK; both are dispatched here instead of from separate modules.
 */
class SenseXposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    private val rmxHooks = local.mio.gt7profodfix.HookEntry()
    private val walletHooks = local.mio.coloroswalletcompat.HookEntry()

    override fun initZygote(startupParam: StartupParam) {
        runCatching {
            walletHooks.initZygote(startupParam)
        }.onFailure {
            XposedBridge.log("SenseXposed: wallet initZygote failed: $it")
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        runCatching {
            rmxHooks.handleLoadPackage(lpparam)
        }.onFailure {
            XposedBridge.log("SenseXposed: RMX hooks failed for ${lpparam.packageName}: $it")
        }
        runCatching {
            walletHooks.handleLoadPackage(lpparam)
        }.onFailure {
            XposedBridge.log("SenseXposed: wallet hooks failed for ${lpparam.packageName}: $it")
        }
    }
}

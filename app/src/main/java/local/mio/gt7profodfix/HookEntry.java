package local.mio.gt7profodfix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        boolean systemServer = "android".equals(param.processName)
                && ("android".equals(param.packageName) || "system".equals(param.packageName));
        if (systemServer) {
            HookLog.info("system_server entry via pkg=" + param.packageName);
            try {
                SystemServerHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("system_server hook installation failed", error);
            }
            try {
                CornerHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("corner hook installation failed", error);
            }
            try {
                ScreenColorBridgeHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("screen color bridge installation failed", error);
            }
        } else if ("com.android.systemui".equals(param.packageName)) {
            try {
                SystemUiHooks.install(param.classLoader, param.processName);
            } catch (Throwable error) {
                HookLog.error("SystemUI hook installation failed", error);
            }
            try {
                IslandHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("Island hook installation failed", error);
            }
            try {
                CornerHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("corner hook installation failed", error);
            }
        } else if ("com.xiaomi.finddevice".equals(param.packageName)) {
            HookLog.info("finddevice entry, installing NoTZ credential hooks");
            try {
                FdNoTzHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("finddevice hook installation failed", error);
            }
        } else if ("com.xiaomi.aon".equals(param.packageName)) {
            HookLog.info("MiAON entry, bridging gaze to Oplus AON HAL");
            try {
                XiaomiAonGazeHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("MiAON gaze hook installation failed", error);
            }
        } else if ("com.miui.securitycenter".equals(param.packageName)
                || "com.miui.securitymanager".equals(param.packageName)) {
            HookLog.info("SecurityCenter entry, installing MiCharge/battery-time hooks");
            try {
                MiChargeHooks.install(param.packageName, param.processName,
                        param.classLoader);
            } catch (Throwable error) {
                HookLog.error("MiCharge hook installation failed", error);
            }
        } else if ("org.ifaa.aidl.manager".equals(param.packageName)) {
            HookLog.info("IFAA entry, installing fingerprint payment hooks");
            try {
                IfaaHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("IFAA hook installation failed", error);
            }
        } else if ("com.android.settings".equals(param.packageName)) {
            HookLog.info("Settings entry, exposing stock pickup switch");
            try {
                SettingsHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("Settings hook installation failed", error);
            }
            try {
                FaceEnrollHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("FaceEnroll hook installation failed", error);
            }
        } else if ("com.android.bluetooth".equals(param.packageName)) {
            HookLog.info("Bluetooth entry, installing LHDC V5 bridge");
            try {
                BluetoothHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("Bluetooth LHDC hook installation failed", error);
            }
        } else if ("com.oplus.camera".equals(param.packageName)) {
            HookLog.info("OplusCamera entry, installing camera hooks");
            try {
                CameraSlowMotionHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera slow-video hook installation failed", error);
            }
            try {
                CameraProModeHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera professional-mode hook installation failed", error);
            }
            try {
                CameraGalleryHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera gallery hook installation failed", error);
            }
            try {
                CameraSuperTextHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera super-text hook installation failed", error);
            }
            try {
                CameraTimeLapseHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera time-lapse hook installation failed", error);
            }
            try {
                CameraMultiViewHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera multi-view hook installation failed", error);
            }
            try {
                CameraMuxerHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera muxer stop hook installation failed", error);
            }
            try {
                CameraScreenOnHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera keep-screen-on hook installation failed", error);
            }
            try {
                CameraOrientationHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera orientation hook installation failed", error);
            }
            try {
                CameraFaceBeautyHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera face-beauty guard installation failed", error);
            }
            try {
                CameraDeferHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera defer hook installation failed", error);
            }
            try {
                CameraThermalHooks.install(param.classLoader);
            } catch (Throwable error) {
                HookLog.error("OplusCamera thermal hook installation failed", error);
            }
        }
    }
}

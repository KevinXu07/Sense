package local.mio.gt7profodfix;

import android.os.IBinder;

import de.robv.android.xposed.XposedHelpers;

/**
 * The 201 camera's native media stack expects the Xiaomi vendor AIDL
 * vendor.xiaomi.hardware.videoservice.IVideoService/default at video stop.
 * The port's vendor does not register it, so the multi-view muxer never
 * writes moov and leaves an unplayable .pending-*.mp4.  Register a default
 * binder stub in system_server so the native client can reach the interface.
 */
final class CameraVideoServiceHooks {
    private static final String NAME =
            "vendor.xiaomi.hardware.videoservice.IVideoService/default";

    private CameraVideoServiceHooks() {
    }

    static void install(ClassLoader loader) {
        try {
            Object service = new VideoServiceStub();
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            XposedHelpers.callStaticMethod(serviceManager, "addService",
                    NAME, (IBinder) service);
            HookLog.info("Xiaomi video service stub registered");
        } catch (Throwable error) {
            HookLog.error("Xiaomi video service stub registration failed", error);
        }
    }
}

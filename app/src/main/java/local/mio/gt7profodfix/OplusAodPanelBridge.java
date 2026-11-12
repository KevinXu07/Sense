package local.mio.gt7profodfix;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Put the panel into the Oplus "Longrui" full-screen AOD mode whenever
 * SystemUI's MiuiFullAodManager wants full-screen AOD (same contract as the
 * OP13 module's OplusAodPanelBridge).
 *
 * Verified against this device's HAL (/odm vendor.oplus.hardware.displaypanelfeature V1):
 *   transaction 1 = getDisplayPanelFeatureValue(int id, out int[] value) -> int
 *   transaction 2 = setDisplayPanelFeatureValue(int id, in int[] value)  -> int
 *   feature 217 dispatches to GetDisplayLongruiAOD / SetDisplayLongruiAOD.
 * get(217) returns the panel's Longrui capability (0x6 on the BOE AB849 panel);
 * the mode is only written when that advertises FULL_SCREEN_AOD_MODE.
 *
 * Kill switch: setprop persist.rmx5010.aod_panel 0 (takes effect on the next
 * AOD state change; writes mode 0).
 */
final class OplusAodPanelBridge {
    private static final String SERVICE_NAME =
            "vendor.oplus.hardware.displaypanelfeature.IDisplayPanelFeature/default";
    private static final String DESCRIPTOR =
            "vendor.oplus.hardware.displaypanelfeature.IDisplayPanelFeature";
    private static final int FEATURE_LONGRUI_AOD = 217;
    private static final int FULL_SCREEN_AOD_MODE = 1 << 2;
    private static final int GET_FEATURE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    private static final int SET_FEATURE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int UNKNOWN_MODE = Integer.MIN_VALUE;
    private static final String KILL_SWITCH = "persist.rmx5010.aod_panel";
    private static final String FULL_AOD_MANAGER =
            "com.android.keyguard.fullaod.MiuiFullAodManager";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rmx-aod-panel");
        thread.setDaemon(true);
        return thread;
    });

    // Only touched on EXECUTOR.
    private static IBinder panelService;
    private static int capability = UNKNOWN_MODE;
    private static int lastMode = UNKNOWN_MODE;

    private OplusAodPanelBridge() {
    }

    static void installSystemUi(ClassLoader loader, String processName) {
        if (!"com.android.systemui".equals(processName)) {
            return;
        }
        Class<?> manager = Reflect.findClass(loader, FULL_AOD_MANAGER);
        if (manager == null) {
            HookLog.info("aod panel: MiuiFullAodManager missing, panel mode not managed");
            return;
        }
        XC_MethodHook sync = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                sync(param.thisObject);
            }
        };
        de.robv.android.xposed.XposedBridge.hookAllConstructors(manager, sync);
        int update = Reflect.hookNamedMethods(manager, "updateFullAodEnable", sync);
        int dozing = 0;
        for (int index = 1; index <= 8; index++) {
            Class<?> callback = Reflect.findClass(loader, FULL_AOD_MANAGER + "$" + index);
            Method method = callback == null ? null
                    : Reflect.findMethod(callback, "onDozingChanged", boolean.class);
            if (method == null) {
                continue;
            }
            if (Reflect.hookMethodOnce(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sync(Reflect.get(param.thisObject, "this$0"));
                }
            })) {
                dozing++;
            }
        }
        HookLog.info("aod panel: MiuiFullAodManager hooks update=" + update
                + ", dozing=" + dozing);
    }

    private static void sync(Object manager) {
        if (manager == null) {
            return;
        }
        boolean wanted;
        try {
            wanted = Boolean.TRUE.equals(Reflect.call(manager, "fullAodEnable"));
        } catch (Throwable missing) {
            wanted = Reflect.getBoolean(manager, false, "isDeviceSupport")
                    && Reflect.getBoolean(manager, false, "mAodEnable")
                    && Reflect.getBoolean(manager, false, "mAodFullScreenEnable")
                    && Reflect.getBoolean(manager, false, "isFullAodSupport");
            HookLog.once("aod_panel_fallback", "aod panel: fullAodEnable missing, using fields");
        }
        boolean enabled = wanted;
        EXECUTOR.execute(() -> apply(enabled));
    }

    private static void apply(boolean wanted) {
        try {
            IBinder service = service();
            if (service == null) {
                return;
            }
            if (capability == UNKNOWN_MODE) {
                capability = getFeature(service);
                HookLog.info("aod panel: longrui capability=0x" + Integer.toHexString(capability));
            }
            if ((capability & FULL_SCREEN_AOD_MODE) == 0) {
                HookLog.once("aod_panel_unsupported",
                        "aod panel: full-screen AOD mode not advertised, not writing");
                return;
            }
            boolean allowed = !"0".equals(systemProperty(KILL_SWITCH));
            int mode = wanted && allowed ? FULL_SCREEN_AOD_MODE : 0;
            if (mode == lastMode) {
                return;
            }
            int result = setFeature(service, mode);
            if (result != 0) {
                HookLog.info("aod panel: set mode=" + mode + " result=" + result);
                return;
            }
            lastMode = mode;
            HookLog.info("aod panel: mode=" + mode + " (wanted=" + wanted
                    + ", allowed=" + allowed + "), readback=0x"
                    + Integer.toHexString(getFeature(service)));
        } catch (Throwable error) {
            panelService = null;
            capability = UNKNOWN_MODE;
            lastMode = UNKNOWN_MODE;
            HookLog.error("aod panel: update failed", error);
        }
    }

    private static IBinder service() throws Exception {
        if (panelService != null && panelService.isBinderAlive()) {
            return panelService;
        }
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder service = (IBinder) getService.invoke(null, SERVICE_NAME);
        if (service == null) {
            HookLog.once("aod_panel_service_missing", "aod panel: HAL service not visible");
            return null;
        }
        // HAL restarts reset the panel; forget cached state so the next sync rewrites.
        service.linkToDeath(() -> EXECUTOR.execute(() -> {
            panelService = null;
            capability = UNKNOWN_MODE;
            lastMode = UNKNOWN_MODE;
            HookLog.info("aod panel: HAL died, state reset");
        }), 0);
        panelService = service;
        capability = UNKNOWN_MODE;
        lastMode = UNKNOWN_MODE;
        return service;
    }

    private static int getFeature(IBinder service) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(FEATURE_LONGRUI_AOD);
            data.writeInt(1);
            if (!service.transact(GET_FEATURE_TRANSACTION, data, reply, 0)) {
                throw new IllegalStateException("get transaction not handled");
            }
            reply.readException();
            int result = reply.readInt();
            int[] values = reply.createIntArray();
            if (result != 0 || values == null || values.length == 0) {
                throw new IllegalStateException("get result=" + result
                        + " value=" + Arrays.toString(values));
            }
            return values[0];
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static int setFeature(IBinder service, int value) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(FEATURE_LONGRUI_AOD);
            data.writeIntArray(new int[]{value});
            if (!service.transact(SET_FEATURE_TRANSACTION, data, reply, 0)) {
                throw new IllegalStateException("set transaction not handled");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static String systemProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return (String) properties.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable error) {
            return "";
        }
    }
}

package local.mio.gt7profodfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;

/**
 * LHDC V5 software encoder bridge, scoped to com.android.bluetooth.
 *
 * The HyperOS MiuiBluetooth stack advertises LHDC V5 but this port has no
 * liblhdcv5 encoder backend. The helper replaces the stack's encoder interface
 * table with the packaged Savitech encoder and changes the V5 packet interval
 * from 20 ms to 10 ms. It validates the target image first and returns a
 * negative status (no patch) if the loaded libbluetooth_jni.so does not match.
 */
final class BluetoothHooks {
    // Sense is the single LSPosed module now; keep the old package as a
    // fallback so the same source still works with the legacy standalone APK.
    private static final String[] MODULE_PACKAGES = {
            "com.zephiel.sense",
            "local.mio.gt7profodfix"
    };
    private static final AtomicBoolean LIBRARY_LOADED = new AtomicBoolean();
    private static final AtomicBoolean APPLIED = new AtomicBoolean();
    private static final AtomicInteger ATTEMPTS = new AtomicInteger();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private BluetoothHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> service = Reflect.findClass(loader,
                "com.android.bluetooth.btservice.AdapterService");
        if (service == null) {
            HookLog.info("Bluetooth AdapterService missing; LHDC bridge skipped");
            return;
        }
        int hooks = hookAfter(service, "onCreate");
        hooks += hookAfter(service, "onStartCommand");
        HookLog.info("Bluetooth LHDC lifecycle hooks=" + hooks);
        if (hooks == 0) {
            return;
        }
        try {
            MAIN.postDelayed(BluetoothHooks::applyOnce, 2000L);
        } catch (Throwable ignored) {
        }
    }

    private static int hookAfter(Class<?> type, String methodName) {
        return Reflect.hookNamedMethods(type, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyOnce();
            }
        });
    }

    private static void applyOnce() {
        if (APPLIED.get() || ATTEMPTS.incrementAndGet() > 5) {
            return;
        }
        try {
            Context context = currentApplication();
            if (context == null) {
                HookLog.once("lhdc_no_context", "LHDC bridge: no Bluetooth context");
                return;
            }
            loadNativeLibrary(context);
            int probe = nativeProbeLhdc();
            HookLog.info("LHDC V5 packaged libraries status=" + probe);
            int result = nativeApplyLhdcPatch();
            if (result > 0) {
                APPLIED.set(true);
                HookLog.info("LHDC V5 software encoder interface installed");
            } else {
                HookLog.once("lhdc_patch_status_" + result,
                        "LHDC V5 bridge skipped, status=" + result);
            }
        } catch (Throwable error) {
            HookLog.error("LHDC V5 bridge failed", error);
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = Reflect.callStatic(activityThread, "currentActivityThread");
            Object app = thread == null ? null
                    : Reflect.call(thread, "getApplication");
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void loadNativeLibrary(Context context) throws Exception {
        if (LIBRARY_LOADED.get()) {
            return;
        }
        synchronized (LIBRARY_LOADED) {
            if (LIBRARY_LOADED.get()) {
                return;
            }
            ApplicationInfo info = null;
            for (String packageName : MODULE_PACKAGES) {
                try {
                    info = context.getPackageManager()
                            .getApplicationInfo(packageName, 0);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (info == null) {
                throw new IllegalStateException("no module package found");
            }
            File library = new File(info.nativeLibraryDir, "libop13_lhdc_patch.so");
            if (!library.isFile()) {
                throw new IllegalStateException("missing " + library);
            }
            System.load(library.getAbsolutePath());
            LIBRARY_LOADED.set(true);
        }
    }

    private static native int nativeProbeLhdc();

    private static native int nativeApplyLhdcPatch();
}

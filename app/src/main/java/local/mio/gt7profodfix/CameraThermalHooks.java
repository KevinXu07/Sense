package local.mio.gt7profodfix;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.SystemClock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Restore OplusCamera's phone-temperature reading.
 *
 * The camera reads the phone temperature through
 * com.oplus.util.OplusHoraeThermalHelper.getCurrentThermal() (thermal UI,
 * high-temperature video limits, and com.oplus.phone.temperature sent to
 * the HAL when com.oplus.temperature.sendtohal is set).  On ColorOS the
 * framework copy of that class talks to the horae thermal service; HyperOS
 * has neither, so the camera falls back to the compile stub bundled in its
 * own APK, which always returns -1.0 ("unknown").
 *
 * Report the battery temperature (closest public reading to horae's fitted
 * shell temperature) instead of the stub value.
 *
 * Note: this does NOT fix the telephoto far-focus limit.  That limit is
 * computed inside /odm/lib64/libAF.so from its own lens/sensor temperature
 * model and is fixed by the patched libAF.so in the KSU module
 * (RMX5010_HyperOSFix/scripts/patch_libaf_farlimit.py).
 */
final class CameraThermalHooks {
    private static final String HELPER = "com.oplus.util.OplusHoraeThermalHelper";
    private static final long CACHE_MS = 5_000L;

    private static float cached = Float.NaN;
    private static long cachedAt;

    private CameraThermalHooks() {
    }

    static void install(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(HELPER, loader, "getCurrentThermal",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object original = param.getResult();
                        if (original instanceof Float && (Float) original >= 0f) {
                            return; // a real horae implementation answered
                        }
                        float temp = readTemperature();
                        if (!Float.isNaN(temp)) {
                            param.setResult(temp);
                            HookLog.once("camera-thermal",
                                    "camera thermal: stub " + original + " -> " + temp + " C");
                        }
                    }
                });
        HookLog.info("camera thermal: OplusHoraeThermalHelper.getCurrentThermal hooked");
    }

    private static synchronized float readTemperature() {
        long now = SystemClock.elapsedRealtime();
        if (!Float.isNaN(cached) && now - cachedAt < CACHE_MS) {
            return cached;
        }
        float temp = Float.NaN;
        try {
            Context context = AndroidAppHelper.currentApplication();
            if (context != null) {
                Intent battery = context.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) {
                    int tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,
                            Integer.MIN_VALUE);
                    if (tenths > -200 && tenths < 1000) {
                        temp = tenths / 10f;
                    }
                }
            }
        } catch (Throwable error) {
            HookLog.once("camera-thermal-error", "camera thermal: battery read failed " + error);
        }
        if (!Float.isNaN(temp)) {
            cached = temp;
            cachedAt = now;
        }
        return temp;
    }
}

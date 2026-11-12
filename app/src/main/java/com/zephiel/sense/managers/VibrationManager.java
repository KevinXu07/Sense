package com.zephiel.sense.managers;

import android.content.Context;

import com.zephiel.sense.service.SenseService;
import com.zephiel.sense.util.RootUtils;
import com.zephiel.sense.util.SenseLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VibrationManager {

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ✅ Root failure 回调
    private final SenseService.RootFailureListener rootFailureListener;

    private boolean isDebugLoggingEnabled = false;
    private volatile boolean isEnabled = false;
    private volatile boolean isDestroyed = false;

    private Boolean lastEnabledState = null;

    public VibrationManager(Context context,
                            SenseService.RootFailureListener listener) {
        this.context = context.getApplicationContext();
        this.rootFailureListener = listener;
    }

    public void setDebugLogging(boolean enabled) {
        this.isDebugLoggingEnabled = enabled;
    }

    // ==============================
    // Lifecycle
    // ==============================

    public synchronized void setEnabled(boolean enabled) {
        if (this.isEnabled == enabled) return;

        this.isEnabled = enabled;
        SenseLog.i("Vibration service enabled: %b", enabled);

        applyVibrationSettings(enabled);
    }

    public void onDestroy() {
        isEnabled = false;
        isDestroyed = true;
        try {
            executor.shutdownNow();
        } catch (Exception e) {
            SenseLog.e("Error shutting down VibrationManager executor", e);
        }
    }

    // ==============================
    // Core Apply
    // ==============================

    public void applyVibrationSettings(boolean enabled) {
        if (isDestroyed) return;

        if (lastEnabledState != null && lastEnabledState == enabled) {
            if (isDebugLoggingEnabled) {
                SenseLog.vRaw("Skip duplicate vibration state: %b", enabled);
            }
            return;
        }

        lastEnabledState = enabled;

        executor.execute(() -> {
            try {
                if (enabled) {
                    SenseLog.dRaw("Applying vibration ENHANCEMENT props...");
                    applyVibrationEnableProps();
                } else {
                    SenseLog.dRaw("Restoring default vibration props...");
                    applyVibrationDisableProps();
                }
                SenseLog.i("Vibration props applied successfully (enabled=%b)", enabled);
            } catch (Throwable t) {
                if (!isDestroyed) {
                    SenseLog.e("Failed to apply vibration props", t);
                    notifyRootFailure(t);
                } else {
                    SenseLog.w("Vibration props update failed during stop, ignored: %s", t.getMessage());
                }
            }
        });
    }

    // ==============================
    // Enable
    // ==============================

    private void applyVibrationEnableProps() {
        // Essential props
        exec("setprop sys.haptic.motor linear");
        exec("setprop sys.haptic.infinitelevel true");
        exec("setprop sys.haptic.dynamiceffect true");
        exec("setprop sys.haptic.dynamiceffect.richtap true");

        // Levels
        exec("setprop sys.haptic.down.weak 0");
        exec("setprop sys.haptic.down.normal 2");
        exec("setprop sys.haptic.down.strong 5");
        exec("setprop sys.haptic.down \"5,2\"");

        exec("setprop sys.haptic.tap.normal \"3,2\"");
        exec("setprop sys.haptic.tap.light \"5,2\"");

        exec("setprop sys.haptic.flick \"5,2\"");
        exec("setprop sys.haptic.flick.light \"7,2\"");

        exec("setprop sys.haptic.switch \"9,2\"");

        exec("setprop sys.haptic.mesh.heavy \"8,2\"");
        exec("setprop sys.haptic.mesh.normal \"5,2\"");
        exec("setprop sys.haptic.mesh.light \"5,1\"");

        exec("setprop sys.haptic.long.press \"0,1\"");

        exec("setprop sys.haptic.popup.normal \"6,2\"");
        exec("setprop sys.haptic.popup.light \"6,1\"");

        exec("setprop sys.haptic.pickup \"2,2\"");
        exec("setprop sys.haptic.scroll.edge \"7,0\"");
        exec("setprop sys.haptic.trigger.drawer \"2,0\"");
        exec("setprop sys.haptic.hold \"4,0\"");

        exec("setprop sys.haptic.runin 13");

        exec("setprop sys.haptic.intensityforkeyboard true");
        exec("setprop sys.haptic.ignoreWhenCamera true");
        exec("setprop sys.haptic.onetrack true");

        exec("setprop sys.haptic.boundary_spatial \"0,2\"");
        exec("setprop sys.haptic.boundary_time \"1,2\"");

        exec("setprop sys.haptic.button_large \"2,2\"");
        exec("setprop sys.haptic.button_middle \"3,2\"");
        exec("setprop sys.haptic.button_small \"4,2\"");

        exec("setprop sys.haptic.gear_light \"5,2\"");
        exec("setprop sys.haptic.gear_heavy \"6,2\"");

        exec("setprop sys.haptic.keyboard \"7,2\"");
        exec("setprop sys.haptic.alert \"8,2\"");
        exec("setprop sys.haptic.zaxis_switch \"9,2\"");
    }

    // ==============================
    // Disable
    // ==============================

    private void applyVibrationDisableProps() {
        exec("setprop sys.haptic.motor \"\"");
        exec("setprop sys.haptic.infinitelevel \"\"");
        exec("setprop sys.haptic.dynamiceffect \"\"");
        exec("setprop sys.haptic.dynamiceffect.richtap \"\"");

        exec("setprop sys.haptic.down.weak \"\"");
        exec("setprop sys.haptic.down.normal \"\"");
        exec("setprop sys.haptic.down.strong \"\"");
        exec("setprop sys.haptic.down \"\"");

        exec("setprop sys.haptic.tap.normal \"\"");
        exec("setprop sys.haptic.tap.light \"\"");

        exec("setprop sys.haptic.flick \"\"");
        exec("setprop sys.haptic.flick.light \"\"");

        exec("setprop sys.haptic.switch \"\"");

        exec("setprop sys.haptic.mesh.heavy \"\"");
        exec("setprop sys.haptic.mesh.normal \"\"");
        exec("setprop sys.haptic.mesh.light \"\"");

        exec("setprop sys.haptic.long.press \"\"");

        exec("setprop sys.haptic.popup.normal \"\"");
        exec("setprop sys.haptic.popup.light \"\"");

        exec("setprop sys.haptic.pickup \"\"");
        exec("setprop sys.haptic.scroll.edge \"\"");
        exec("setprop sys.haptic.trigger.drawer \"\"");
        exec("setprop sys.haptic.hold \"\"");

        exec("setprop sys.haptic.runin \"\"");

        exec("setprop sys.haptic.intensityforkeyboard \"\"");
        exec("setprop sys.haptic.ignoreWhenCamera \"\"");
        exec("setprop sys.haptic.onetrack \"\"");

        exec("setprop sys.haptic.boundary_spatial \"\"");
        exec("setprop sys.haptic.boundary_time \"\"");

        exec("setprop sys.haptic.button_large \"\"");
        exec("setprop sys.haptic.button_middle \"\"");
        exec("setprop sys.haptic.button_small \"\"");

        exec("setprop sys.haptic.gear_light \"\"");
        exec("setprop sys.haptic.gear_heavy \"\"");

        exec("setprop sys.haptic.keyboard \"\"");
        exec("setprop sys.haptic.alert \"\"");
        exec("setprop sys.haptic.zaxis_switch \"\"");
    }

    // ==============================
    // Root Wrapper
    // ==============================

    private void exec(String cmd) {
        attemptExec(cmd, 0);
    }

    private void attemptExec(String cmd, int retryCount) {
        if (isDestroyed) return;
        RootUtils.execAsync(cmd, result -> {
            if (result != null && result.isSuccess()) return;
            if (isDestroyed) return;

            if (retryCount < 10) {
                SenseLog.w("Failed to execute vibration cmd, retrying (" + (retryCount + 1) + "/10): " + cmd);
                executor.execute(() -> {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    attemptExec(cmd, retryCount + 1);
                });
            } else {
                SenseLog.e("Failed to execute vibration cmd after retries: " + cmd);
                if (rootFailureListener != null) {
                    rootFailureListener.onRootFailure();
                }
            }
        });
    }


    private void notifyRootFailure(Throwable t) {
        if (!isEnabled) return;
        SenseLog.e("Root failure in VibrationManager", t);

        if (rootFailureListener != null) {
            rootFailureListener.onRootFailure();
        }
    }
}

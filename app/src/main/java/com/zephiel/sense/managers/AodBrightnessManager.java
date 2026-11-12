package com.zephiel.sense.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.zephiel.sense.service.SenseService;
import com.zephiel.sense.util.RootUtils;
import com.zephiel.sense.util.SenseLog;

public class AodBrightnessManager {

    private final Context context;
    private final SenseService.RootFailureListener rootFailureListener;

    private boolean debugLoggingEnabled = false;
    private Boolean lastEnabled = null;

    public AodBrightnessManager(Context context,
                                 SenseService.RootFailureListener listener) {
        this.context = context.getApplicationContext();
        this.rootFailureListener = listener;
    }

    public void setDebugLogging(boolean enabled) {
        this.debugLoggingEnabled = enabled;
    }

    public void setEnabled(boolean enabled) {
        if (lastEnabled != null && lastEnabled == enabled) return;

        String cmd = "echo " + (enabled ? "1" : "0") + " > /sys/kernel/oplus_display/aod_light_mode_set";
        if (debugLoggingEnabled) {
            SenseLog.dRaw("Applying AOD brightness mode: %b", enabled);
        }

        attemptSetEnabled(enabled, cmd, 0);
    }

    private void attemptSetEnabled(boolean enabled, String cmd, int retryCount) {
        RootUtils.execAsync(cmd, result -> {
            if (result != null && result.isSuccess()) {
                lastEnabled = enabled;
                return;
            }

            if (retryCount < 10) {
                SenseLog.w("Failed to apply AOD brightness mode, retrying (" + (retryCount + 1) + "/10)...");
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        attemptSetEnabled(enabled, cmd, retryCount + 1), 1000);
            } else {
                SenseLog.e("Failed to apply AOD brightness mode after retries");
            }
        });
    }


    public void resetCache() {
        lastEnabled = null;
    }
}

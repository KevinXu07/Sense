package com.zephiel.sense.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.zephiel.sense.service.SenseService;
import com.zephiel.sense.util.RootUtils;
import com.zephiel.sense.util.SenseLog;

public class DisplayColorModeManager {

    private final Context context;
    private final SenseService.RootFailureListener rootFailureListener;

    private boolean debugLoggingEnabled = false;
    private int lastAppliedMode = Integer.MIN_VALUE;

    public DisplayColorModeManager(Context context,
                                   SenseService.RootFailureListener listener) {
        this.context = context.getApplicationContext();
        this.rootFailureListener = listener;
    }

    public void setDebugLogging(boolean enabled) {
        this.debugLoggingEnabled = enabled;
    }

    public void applyMode(int mode) {
        int safeMode = sanitizeMode(mode);
        if (safeMode == lastAppliedMode) return;

        int rate = safeMode == 1 ? 60 : 120;
        String cmd = "settings put secure user_refresh_rate " + rate;
        if (debugLoggingEnabled) {
            SenseLog.dRaw("Applying display refresh rate: %d Hz", rate);
        }

        attemptApplyMode(safeMode, cmd, 0);
    }

    private void attemptApplyMode(int safeMode, String cmd, int retryCount) {
        RootUtils.execAsync(cmd, result -> {
            if (result != null && result.isSuccess()) {
                lastAppliedMode = safeMode;
                return;
            }

            if (retryCount < 10) {
                SenseLog.w("Failed to apply display refresh rate, retrying (" + (retryCount + 1) + "/10)...");
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        attemptApplyMode(safeMode, cmd, retryCount + 1), 1000);
            } else {
                SenseLog.e("Failed to apply display refresh rate after retries");
                if (rootFailureListener != null) {
                    rootFailureListener.onRootFailure();
                }
            }
        });
    }


    public void resetCache() {
        lastAppliedMode = Integer.MIN_VALUE;
    }

    private int sanitizeMode(int mode) {
        return mode == 1 ? 1 : 0;
    }
}

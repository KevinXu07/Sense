package com.zephiel.sense.managers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;

import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;

import com.zephiel.sense.service.SenseService;
import com.zephiel.sense.util.RootUtils;
import com.zephiel.sense.util.SenseLog;

public class TouchEnhanceManager {

    private final Context context;
    private final SenseService.RootFailureListener rootFailureListener;

    private volatile boolean isScreenOn = true;
    private float touchRate = 120f;
    private float lastAppliedRate = -1f;
    private boolean isDebugLoggingEnabled = false;
    private volatile boolean isReceiverRegistered = false;

    private boolean gameModeEnabled = false;
    private float gameTouchRate = 120f;
    private java.util.Set<String> gamePackages = new java.util.HashSet<>();
    private String currentPackage = "";
    private boolean isInGame = false;

    public TouchEnhanceManager(Context context,
                               SenseService.RootFailureListener listener) {
        this.context = context.getApplicationContext();
        this.rootFailureListener = listener;
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                setScreenState(false);
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                setScreenState(true);
            } else if ("com.zephiel.sense.ACTION_FOREGROUND_APP_CHANGED".equals(action)) {
                String pkg = intent.getStringExtra("package_name");
                if (pkg != null) {
                    onForegroundAppChanged(pkg);
                }
            }
        }
    };

    public void setDebugLogging(boolean enabled) {
        this.isDebugLoggingEnabled = enabled;
    }

    public void updateGameSettings(boolean enabled, float gameRate, java.util.Set<String> packages) {
        this.gameModeEnabled = enabled;
        this.gameTouchRate = gameRate;
        this.gamePackages = packages != null ? packages : new java.util.HashSet<>();

        if (isDebugLoggingEnabled) {
            SenseLog.i("Updating game settings: enabled=%b, rate=%.1f, apps=%d", enabled, gameRate, gamePackages.size());
        }

        // Re-check current package if enabled
        if (gameModeEnabled && !currentPackage.isEmpty()) {
            onForegroundAppChanged(currentPackage);
        } else if (!gameModeEnabled && isInGame) {
            isInGame = false;
            applyCurrentState();
        }
    }

    private void onForegroundAppChanged(String packageName) {
        if (packageName == null || packageName.isEmpty()) return;
        
        // 过滤系统组件和输入法，避免它们干扰游戏检测
        if (isTransientSystemPackage(packageName)) {
            return;
        }

        this.currentPackage = packageName;
        boolean nowInGame = gameModeEnabled && gamePackages.contains(packageName);

        if (nowInGame != isInGame) {
            isInGame = nowInGame;
            SenseLog.dRaw("Game state transition: isInGame=%b (target=%s)", isInGame, packageName);
            applyCurrentState();
        }
    }

    /**
     * 判断是否为瞬态系统包名（如状态栏、通知中心、输入法等）
     */
    private boolean isTransientSystemPackage(String pkg) {
        return pkg.equals("com.android.systemui") ||
                pkg.equals("com.miui.securitycenter") ||
                pkg.contains("inputmethod") ||
               pkg.equals("android") ||
               pkg.contains("launcher"); // 可选：如果是启动器，是否立即切回日常模式？通常需要。
    }

    /**
     * 更新触控设置
     * @param rate 目标采样率。
     *             - 120Hz: 不启用动态切换功能，但应用 120Hz 采样率
     *             - 70Hz: 固定 70Hz（不检测息屏）
     *             - >120Hz: 动态切换（亮屏 rate，息屏 70Hz）
     */
    public synchronized void updateSettings(float rate) {
        if (isDebugLoggingEnabled) {
            SenseLog.i("Updating touch settings: rate=%.1f", rate);
        }

        this.touchRate = rate;

        if (rate == 120f && !gameModeEnabled) {
            if (isDebugLoggingEnabled) SenseLog.dRaw("TouchEnhance set to 120Hz, applying once and disabling dynamic logic");
            unregisterScreenReceiver();
            applyRate(120f);
        } else if (rate == 70f && !gameModeEnabled) {
            if (isDebugLoggingEnabled) SenseLog.dRaw("TouchEnhance set to 70Hz (Static mode)");
            unregisterScreenReceiver();
            applyRate(70f);
        } else {
            if (isDebugLoggingEnabled) SenseLog.dRaw("TouchEnhance dynamic mode enabled (Daily=%.1f, GameEnabled=%b)", rate, gameModeEnabled);
            registerScreenReceiver();
            applyCurrentState();
        }
    }

    private void setScreenState(boolean isOn) {
        if (this.isScreenOn == isOn) return;

        this.isScreenOn = isOn;

        // 在动态模式下响应屏幕状态
        if (touchRate > 120f || gameModeEnabled) {
            applyCurrentState();
        }
    }

    private void applyCurrentState() {
        if (!isScreenOn) {
            if (isDebugLoggingEnabled) SenseLog.vRaw("Screen OFF, applying idle rate (70)");
            applyRate(70f);
            return;
        }

        float targetRate;
        if (isInGame) {
            targetRate = gameTouchRate;
            if (isDebugLoggingEnabled) SenseLog.vRaw("In Game, applying game rate (%.1f)", targetRate);
        } else {
            targetRate = touchRate;
            if (isDebugLoggingEnabled) SenseLog.vRaw("Not in game, applying daily rate (%.1f)", targetRate);
        }

        applyRate(targetRate);
    }

    private synchronized void registerScreenReceiver() {
        if (isReceiverRegistered) return;

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction("com.zephiel.sense.ACTION_FOREGROUND_APP_CHANGED");

            ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) this.isScreenOn = pm.isInteractive();

            isReceiverRegistered = true;
            if (isDebugLoggingEnabled) SenseLog.dRaw("Screen and App receiver registered");
        } catch (Exception e) {
            SenseLog.e("Receiver register failed", e);
        }
    }

    private synchronized void unregisterScreenReceiver() {
        if (!isReceiverRegistered) return;

        try {
            context.unregisterReceiver(screenReceiver);
            if (isDebugLoggingEnabled) SenseLog.dRaw("Screen receiver unregistered");
        } catch (Exception e) {
            SenseLog.e("Receiver unregister failed", e);
        }

        isReceiverRegistered = false;
    }

    private synchronized void applyRate(float rate) {
        if (Math.abs(rate - lastAppliedRate) < 0.1f) return;

        // Sense owns the touch node while this manager is active. Mirror the
        // effective rate into the KSU module key so the module's screen-on/off
        // watcher does not fight this manager, and so Sense can display it.
        RootUtils.execAsync("settings put global op13_fix_touch_owner sense; "
                + "settings put global op13_fix_touch_sampling_rate " + (int) rate);

        String cmd = "/odm/bin/touchHidlTest -c wo 0 182 " + (int) rate;
        if (isDebugLoggingEnabled) SenseLog.vRaw("Executing rate update: %s", cmd);

        attemptApplyRate(rate, cmd, 0);
    }

    private void attemptApplyRate(float rate, String cmd, int retryCount) {
        RootUtils.execAsync(cmd, result -> {
            if (result != null && result.isSuccess()) {
                lastAppliedRate = rate;
                return;
            }

            if (retryCount < 10) {
                SenseLog.w("Failed to apply touch rate, retrying (" + (retryCount + 1) + "/10)...");
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        attemptApplyRate(rate, cmd, retryCount + 1), 1000);
            } else {
                SenseLog.e("Failed to apply touch rate after retries");
                if (rootFailureListener != null) {
                    rootFailureListener.onRootFailure();
                }
            }
        });
    }

    public synchronized void onDestroy() {
        unregisterScreenReceiver();
    }
}

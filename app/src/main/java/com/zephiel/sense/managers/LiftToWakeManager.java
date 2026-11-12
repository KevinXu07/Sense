package com.zephiel.sense.managers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import com.zephiel.sense.service.SenseService;
import com.zephiel.sense.util.RootUtils;
import com.zephiel.sense.util.SenseLog;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LiftToWakeManager implements SensorEventListener {

    private final Context context;
    private final SensorManager sensorManager;
    private final PowerManager powerManager;

    private final Sensor accelerometer;
    private final SenseService.RootFailureListener rootFailureListener;

    private final ExecutorService actionExecutor = Executors.newSingleThreadExecutor();

    private volatile boolean isEnabled = false;
    private volatile boolean isListening = false;
    private volatile boolean isDebugLoggingEnabled = false;

    private int sensitivityLevel = 3;
    private int sensorDelay = SensorManager.SENSOR_DELAY_NORMAL;

    private float tY, tZ, fXY;

    private long lastFlatTime = 0;
    private static final long FLAT_MEMORY_MS = 1200;
    private static final String PICKUP_SETTING = "pick_up_gesture_wakeup_mode";

    private volatile boolean receiverRegistered = false;

    private final ContentObserver pickupObserver;

    // ==============================
    // 状态机（唯一触发保障）
    // ==============================

    private enum State {
        IDLE,      // 未准备
        READY,     // 已平放，可触发
        LOCKED     // 已触发，等待再次平放
    }

    private State state = State.IDLE;

    // ==============================
    // Constructor
    // ==============================

    public LiftToWakeManager(Context context,
                             SenseService.RootFailureListener listener) {
        this.context = context.getApplicationContext();
        this.rootFailureListener = listener;

        this.sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);

        this.accelerometer = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                : null;

        this.pickupObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                refreshPickupGate();
            }
        };
        try {
            this.context.getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(PICKUP_SETTING), false, pickupObserver);
        } catch (Exception e) {
            SenseLog.e("Failed to observe pickup setting", e);
        }

        if (accelerometer == null) {
            SenseLog.w("Accelerometer not found, LiftToWake will not work");
        }
    }

    private boolean isSystemPickupEnabled() {
        return Settings.System.getInt(
                context.getContentResolver(), PICKUP_SETTING, 0) == 1;
    }

    /**
     * HyperOS 的“抬起亮屏”总开关关闭时，Sense 的独立传感器路径也必须停止；
     * 否则系统开关关闭后仍然能被加速度计唤醒。
     */
    private synchronized void refreshPickupGate() {
        if (!isEnabled) return;
        if (!isSystemPickupEnabled()) {
            stop();
            return;
        }
        if (powerManager != null && !powerManager.isInteractive()) {
            start();
        }
    }

    // ==============================
    // Screen Receiver
    // ==============================

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isEnabled) return;

            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                SenseLog.vRaw("Screen OFF detected, starting sensor monitor");
                start();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                SenseLog.vRaw("Screen ON detected, stopping sensor monitor");
                stop();
            }
        }
    };

    private synchronized void registerScreenReceiver() {
        if (receiverRegistered) return;

        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);

            context.registerReceiver(screenReceiver, f);
            receiverRegistered = true;

            if (powerManager != null) {
                if (powerManager.isInteractive()) stop();
                else refreshPickupGate();
            }
            SenseLog.d("LiftToWake screen receiver registered");
        } catch (Exception e) {
            SenseLog.e("Failed to register screen receiver", e);
        }
    }

    private synchronized void unregisterScreenReceiver() {
        if (!receiverRegistered) return;

        try {
            context.unregisterReceiver(screenReceiver);
            SenseLog.d("LiftToWake screen receiver unregistered");
        } catch (Exception e) {
            SenseLog.e("Failed to unregister screen receiver", e);
        }

        receiverRegistered = false;
    }

    // ==============================
    // Public API
    // ==============================

    public synchronized void setEnabled(boolean enabled) {
        if (this.isEnabled == enabled) return;

        this.isEnabled = enabled;
        SenseLog.i("LiftToWake enabled: %b", enabled);

        if (enabled) {
            registerScreenReceiver();
            refreshPickupGate();
        } else {
            stop();
            unregisterScreenReceiver();
        }
    }

    public synchronized void onDestroy() {
        isEnabled = false;
        stop();
        unregisterScreenReceiver();
        try {
            context.getContentResolver().unregisterContentObserver(pickupObserver);
        } catch (Exception ignored) {
        }
        try {
            actionExecutor.shutdownNow();
        } catch (Exception e) {
            SenseLog.e("Error shutting down LiftToWake executor", e);
        }
    }

    public void setDebugLogging(boolean enabled) {
        this.isDebugLoggingEnabled = enabled;
    }

    public void setSensitivity(int sensitivity) {
        this.sensitivityLevel = Math.max(1, Math.min(5, sensitivity));
        updateThresholds();
        SenseLog.dRaw("LiftToWake sensitivity set to: %d", sensitivityLevel);
    }

    private void updateThresholds() {
        this.tY = 6.8f - sensitivityLevel;
        this.tZ = 8.2f + (sensitivityLevel * 0.25f);
        this.fXY = Math.min(tY - 0.6f, 1.2f);
    }

    public void setSensorDelay(int delay) {
        if (this.sensorDelay == delay) return;
        this.sensorDelay = delay;
        SenseLog.dRaw("LiftToWake sensor delay set to: %d", sensorDelay);
        if (isListening) {
            stop();
            start();
        }
    }

    // ==============================
    // Sensor Control（低功耗）
    // ==============================

    private synchronized void start() {
        if (!isEnabled || isListening || accelerometer == null || sensorManager == null) return;
        if (!isSystemPickupEnabled()) return;

        sensorManager.registerListener(this, accelerometer, sensorDelay);

        isListening = true;
        state = State.IDLE;

        SenseLog.d("Sensor monitor STARTED (State=IDLE, Delay=%d)", sensorDelay);
    }

    private synchronized void stop() {
        if (!isListening) return;

        sensorManager.unregisterListener(this);
        isListening = false;
        SenseLog.d("Sensor monitor STOPPED");
    }

    // ==============================
    // Sensor Events（纯状态机）
    // ==============================

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isEnabled || !isSystemPickupEnabled()) return;

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];

        long now = SystemClock.elapsedRealtime();

        boolean isFlat = (z > 8.8f && Math.abs(x) < fXY && Math.abs(y) < fXY);

        // ==========================
        // 1. 平放 → READY
        // ==========================
        if (isFlat) {
            lastFlatTime = now;

            if (state != State.READY) {
                state = State.READY;

                if (isDebugLoggingEnabled) {
                    SenseLog.vRaw("Device is FLAT -> State READY");
                }
            }
            return;
        }

        // ==========================
        // 2. 非 READY 直接跳过（关键省电）
        // ==========================
        if (state != State.READY) return;

        // ==========================
        // 3. READY 超时失效
        // ==========================
        if (now - lastFlatTime > FLAT_MEMORY_MS) {
            state = State.IDLE;
            if (isDebugLoggingEnabled) {
                SenseLog.vRaw("Flat timeout, State READY -> IDLE");
            }
            return;
        }

        // ==========================
        // 4. 抬起检测
        // ==========================
        if (Math.abs(x) > 3.5f) return;

        if (y > tY && z < tZ) {
            SenseLog.i("Lift detected! Thresholds: y>%.1f, z<%.1f | Current: y=%.1f, z=%.1f", tY, tZ, y, z);
            
            // 唯一触发点
            wakeScreen();

            // 锁死直到再次平放
            state = State.LOCKED;

            if (isDebugLoggingEnabled) {
                SenseLog.dRaw("Wake triggered -> State LOCKED");
            }
        }
    }

    // ==============================
    // Execution
    // ==============================

    private void wakeScreen() {
        if (powerManager != null) {
            if (isDebugLoggingEnabled) SenseLog.vRaw("Waking screen via PowerManager...");
            try {
                PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                        "Sense:LiftToWake"
                );
                wakeLock.acquire(1000);
            } catch (Exception e) {
                SenseLog.e("Failed to wake screen via PowerManager", e);
            }
        }
    }

    private void execRoot(String cmd) {
        try {
            RootUtils.execAsync(cmd);
        } catch (Throwable t) {
            if (isEnabled) {
                SenseLog.e("LiftToWake root command failed: %s", t, cmd);
                notifyRootFailure(t);
            }
        }
    }

    private void notifyRootFailure(Throwable t) {
        if (!isEnabled) return;
        SenseLog.e("Root failure in LiftToWakeManager", t);
        if (rootFailureListener != null) {
            rootFailureListener.onRootFailure();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}

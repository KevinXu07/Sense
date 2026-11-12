package com.zephiel.sense.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.zephiel.sense.MainActivity;
import com.zephiel.sense.R;
import com.zephiel.sense.managers.*;
import com.zephiel.sense.util.RootUtils;
import com.zephiel.sense.util.SenseLog;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SenseService extends Service {

    private static final String CHANNEL_ID = "sense_service_channel_v2"; // 更换 ID 以强制更新配置
    private static final int NOTIFICATION_ID = 1001;

    // ==============================
    // Managers
    // ==============================

    private AodBrightnessManager aodBrightnessManager;
    private CpuGovernorManager cpuGovernorManager;

    // ==============================
    // Threading
    // ==============================

    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ==============================
    // State
    // ==============================

    private volatile boolean isDestroyed = false;
    private volatile boolean hasRoot = false;
    private Settings currentSettings = null;
    private ContentObserver accessibilityObserver;

    // ==============================
    // Root Failure Callback
    // ==============================

    public interface RootFailureListener {
        void onRootFailure();
    }

    private final RootFailureListener rootFailureListener = this::onRootFailure;

    // ==============================
    // SharedPreferences Listener
    // ==============================

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (prefs, key) -> {
                if ("pref_last_known_root".equals(key)) return;

                // 如果是日志开关变更，同步更新 SenseLog
                if ("pref_debug_logging_enabled".equals(key)) {
                    SenseLog.setLoggingEnabled(prefs.getBoolean(key, false));
                }

                if ("pref_foreground_service".equals(key)) {
                    updateForegroundState(prefs.getBoolean(key, false));
                }

                executor.execute(() -> applySettingsInternal(false));
            };

    // ==============================
    // Lifecycle
    // ==============================

    @Override
    public void onCreate() {
        super.onCreate();

        SharedPreferences prefs = getPrefs();

        // 1. 尽可能早地处理前台服务状态，避免 Android 12+ 的启动超时或延迟
        updateForegroundState(prefs.getBoolean("pref_foreground_service", false));

        // 2. 初始化日志
        SenseLog.setLoggingEnabled(prefs.getBoolean("pref_debug_logging_enabled", false));
        SenseLog.i("SenseService onCreate");

        executor = Executors.newSingleThreadExecutor();

        initManagers();

        hasRoot = prefs.getBoolean("pref_last_known_root", false);

        prefs.registerOnSharedPreferenceChangeListener(prefListener);

        // 注册系统无障碍设置监听，确保状态被意外改变时能自动恢复
        accessibilityObserver = new ContentObserver(mainHandler) {
            @Override
            public void onChange(boolean selfChange) {
                executor.execute(() -> applySettingsInternal(true));
            }
        };
        getContentResolver().registerContentObserver(
                android.provider.Settings.Secure.getUriFor(android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, accessibilityObserver);

        executor.execute(() -> applySettingsInternal(false));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String reason = (intent != null && intent.getAction() != null) ? intent.getAction() : "start_command";
        SenseLog.d("SenseService onStartCommand, reason: %s", reason);

        // 确保在每次 startCommand 时都检查前台服务状态
        // 主要是为了解决 startForegroundService 启动已在运行的后台服务时，必须显式调用 startForeground 的问题
        updateForegroundState(getPrefs().getBoolean("pref_foreground_service", false));

        executor.execute(() -> applySettingsInternal(false));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isDestroyed = true;
        SenseLog.i("SenseService onDestroy");

        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (Throwable ignored) {}
        }

        try {
            getPrefs().unregisterOnSharedPreferenceChangeListener(prefListener);
        } catch (Throwable ignored) {}

        if (accessibilityObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(accessibilityObserver);
            } catch (Throwable ignored) {}
        }

        stopAllManagers();

        RootUtils.closeShell();

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ==============================
    // Foreground Service
    // ==============================

    private void updateForegroundState(boolean enabled) {
        if (enabled) {
            startForegroundServiceInternal();
        } else {
            stopForeground(true);
        }
    }

    private void startForegroundServiceInternal() {
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_content))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                // 关键修复：强制通知立即显示，不触发 Android 12+ 的 10 秒延迟行为
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Using specialUse for broad system enhancement features (API 34+)
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            SenseLog.i("Foreground service started/updated with ID: " + NOTIFICATION_ID);
        } catch (Exception e) {
            SenseLog.e("Failed to start foreground service: %s", e.getMessage());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT // 提升到 DEFAULT
            );
            serviceChannel.setDescription(getString(R.string.notification_content));
            serviceChannel.setShowBadge(false);
            serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    // ==============================
    // Core Logic (Background Thread)
    // ==============================

    private void applySettingsInternal(boolean forceCheck) {
        if (isDestroyed) return;

        SharedPreferences prefs = getPrefs();

        Settings newSettings = Settings.from(prefs);

        // 优化：计算配置是否真实发生变化
        boolean settingsChanged = (currentSettings == null || !newSettings.equals(currentSettings));

        // 核心优化：仅在 Root 状态未知、关键配置（如游戏模式）变化或强制同步（来自 Observer）时才同步 Root/无障碍状态
        boolean rootValid = ensureRoot(newSettings, forceCheck);

        if (!settingsChanged && !forceCheck) {
            if (!rootValid) stopAllManagers();
            return;
        }

        SenseLog.i("Settings changed, applying to managers...");
        // boolean rootValid = ensureRoot(newSettings.gameModeEnabled); // 已移至上方

        if (!rootValid) {
            SenseLog.e("Root access not valid, stopping all managers");
            stopAllManagers();
            currentSettings = newSettings;

            return;
        }

        applyToManagers(newSettings);
        currentSettings = newSettings;
        SenseLog.i("All settings applied successfully");
    }

    // ==============================
    // Root Lazy Validation (Background Safe)
    // ==============================

    private boolean ensureRoot(Settings settings, boolean forceSync) {
        // 核心优化：如果之前已经确认过 Root，直接复用状态，避免每次变更配置都执行耗时的 shell 校验 (id -u)
        if (hasRoot) {
            // 如果是强制同步（来自 Observer），或者游戏模式开关状态真正发生变化，才同步无障碍服务的状态
            if (forceSync || currentSettings == null || settings.gameModeEnabled != currentSettings.gameModeEnabled) {
                updateAccessibilityServiceState(settings.gameModeEnabled);
            }
            return true;
        }

        // 仅在初始启动或 Root 权限被丢弃时才尝试重新获取和校验
        SenseLog.w("Root access check needed...");
        boolean result = RootUtils.checkRootAccessFresh();

        if (result) {
            updateAccessibilityServiceState(settings.gameModeEnabled);
        }

        if (result != hasRoot) {
            hasRoot = result;
            saveRootState(result);
            SenseLog.i("Root state updated: %b", result);
        }

        return result;
    }

    private void updateAccessibilityServiceState(boolean gameModeEnabled) {
        try {
            String myService = getPackageName() + "/" + SenseAccessibilityService.class.getName();
            RootUtils.CommandResult result = RootUtils.exec("settings get secure enabled_accessibility_services");
            String currentSetting = (result != null && result.isSuccess()) ? result.output.trim() : "";

            if (currentSetting.isEmpty() || "null".equals(currentSetting)) {
                currentSetting = "";
            }

            List<String> services = new ArrayList<>();
            if (!currentSetting.isEmpty()) {
                for (String s : currentSetting.split(":")) {
                    String trimmed = s.trim();
                    if (!trimmed.isEmpty()) {
                        services.add(trimmed);
                    }
                }
            }

            boolean changed = false;
            boolean isActuallyPresent = false;

            // 健壮性检查：识别并清理所有属于本包名的变体（长类名/短类名）
            Iterator<String> it = services.iterator();
            while (it.hasNext()) {
                String s = it.next();
                if (s.equals(myService) || (s.contains(getPackageName()) && s.contains("SenseAccessibilityService"))) {
                    if (!gameModeEnabled) {
                        it.remove();
                        changed = true;
                        SenseLog.i("Removing Sense Accessibility Service (Game Mode Off)");
                    } else {
                        isActuallyPresent = true;
                    }
                }
            }

            if (gameModeEnabled && !isActuallyPresent) {
                services.add(myService);
                changed = true;
                SenseLog.i("Enabling Sense Accessibility Service (Game Mode On)");
            }

            if (changed) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < services.size(); i++) {
                    sb.append(services.get(i));
                    if (i < services.size() - 1) sb.append(":");
                }
                String newValue = sb.toString();
                RootUtils.exec("settings put secure enabled_accessibility_services " + (newValue.isEmpty() ? "\"\"" : newValue));

                // 发送广播通知 UI
                Intent intent = new Intent("com.zephiel.sense.ACCESSIBILITY_STATE_CHANGED");
                intent.putExtra("enabled", gameModeEnabled);
                sendBroadcast(intent);
            }
        } catch (Exception e) {
            SenseLog.e("Error updating accessibility state: %s", e.getMessage());
        }
    }

    // ==============================
    // Root Failure Handling
    // ==============================

    private void onRootFailure() {
        SenseLog.e("Global root failure detected!");

        Runnable stopTask = () -> {
            hasRoot = false;
            saveRootState(false);
            stopAllManagers();
        };

        if (executor != null && !executor.isShutdown()) {
            try {
                executor.execute(stopTask);
            } catch (Exception e) {
                stopTask.run();
            }
        } else {
            stopTask.run();
        }
    }

    // ==============================
    // Apply Settings
    // ==============================

    private void applyToManagers(Settings s) {
        RootUtils.setDebugLogging(s.debug);

        aodBrightnessManager.setDebugLogging(s.debug);
        aodBrightnessManager.setEnabled(s.aodBrightnessEnabled);

        cpuGovernorManager.setDebugLogging(s.debug);
        cpuGovernorManager.setEnabled(s.cpuGovernorEnabled);
        if (s.cpuGovernorEnabled) {
            // 获取核心信息以确保初始化
            cpuGovernorManager.getPolicies();
            cpuGovernorManager.updateSettings(s.gameModeEnabled, s.gamePackages, s.cpuGovernorModes, s.gameCpuGovernorModes);
        }
    }

    // ==============================
    // Managers
    // ==============================

    private void initManagers() {
        aodBrightnessManager = new AodBrightnessManager(this, rootFailureListener);
        cpuGovernorManager = new CpuGovernorManager(this, rootFailureListener);
    }

    private void stopAllManagers() {
        SenseLog.w("Stopping all managers due to disable or root failure");
        try { aodBrightnessManager.resetCache(); } catch (Throwable ignored) {}
        try { cpuGovernorManager.onDestroy(); } catch (Throwable ignored) {}
    }

    // ==============================
    // SharedPreferences
    // ==============================

    private SharedPreferences getPrefs() {
        return createDeviceProtectedStorageContext()
                .getSharedPreferences(getPackageName() + "_preferences", MODE_PRIVATE);
    }

    private void saveRootState(boolean root) {
        getPrefs().edit().putBoolean("pref_last_known_root", root).apply();
    }

    // ==============================
    // Settings Model
    // ==============================

    static class Settings {
        boolean debug;
        boolean foreground;
        boolean fpEnabled;
        float touchRate;
        boolean gameModeEnabled;
        float gameTouchRate;
        java.util.Set<String> gamePackages;
        boolean shortcutEnabled;
        String shortcutHwType;
        int shortcutLongPress;
        String shortcutClickAction, shortcutLongAction;
        String shortcutClickPkg, shortcutLongPkg;
        String shortcutSlider1Action, shortcutSlider2Action, shortcutSlider3Action;
        String shortcutSlider1Pkg, shortcutSlider2Pkg, shortcutSlider3Pkg;
        boolean vibrationEnabled;
        int displayColorMode;
        boolean aodBrightnessEnabled;
        boolean cpuGovernorEnabled;
        Map<Integer, String> cpuGovernorModes;
        Map<Integer, String> gameCpuGovernorModes;
        boolean liftEnabled;
        int liftSensitivity;
        int liftSensorDelay;

        static Settings from(SharedPreferences p) {
            Settings s = new Settings();
            s.debug = p.getBoolean("pref_debug_logging_enabled", false);
            s.foreground = p.getBoolean("pref_foreground_service", false);
            s.fpEnabled = p.getBoolean("pref_fp_fix_enabled", false);
            s.touchRate = p.getFloat("pref_touch_rate", 120f);
            s.gameModeEnabled = p.getBoolean("pref_game_mode_enabled", false);
            s.gameTouchRate = p.getFloat("pref_game_touch_rate", 240f);
            s.gamePackages = p.getStringSet("pref_game_packages", new java.util.HashSet<>());
            s.shortcutEnabled = p.getBoolean("pref_shortcut_enabled", false);
            s.shortcutHwType = p.getString("pref_shortcut_hw_type", "key");
            s.shortcutLongPress = p.getInt("pref_shortcut_long_press_timeout", 400);
            s.shortcutClickAction = p.getString("pref_shortcut_click_action", "none");
            s.shortcutLongAction = p.getString("pref_shortcut_long_press_action", "none");
            s.shortcutClickPkg = p.getString("pref_shortcut_click_package", "");
            s.shortcutLongPkg = p.getString("pref_shortcut_long_press_package", "");
            s.shortcutSlider1Action = p.getString("pref_shortcut_slider_1_action", "none");
            s.shortcutSlider2Action = p.getString("pref_shortcut_slider_2_action", "none");
            s.shortcutSlider3Action = p.getString("pref_shortcut_slider_3_action", "none");
            s.shortcutSlider1Pkg = p.getString("pref_shortcut_slider_1_package", "");
            s.shortcutSlider2Pkg = p.getString("pref_shortcut_slider_2_package", "");
            s.shortcutSlider3Pkg = p.getString("pref_shortcut_slider_3_package", "");
            s.vibrationEnabled = p.getBoolean("pref_vibration_enabled", false);
            s.displayColorMode = p.getInt("pref_display_color_mode", 0);
            s.aodBrightnessEnabled = p.getBoolean("pref_aod_brightness_enabled", false);
            s.cpuGovernorEnabled = p.getBoolean("pref_cpu_governor_enabled", false);

            s.cpuGovernorModes = new HashMap<>();
            s.gameCpuGovernorModes = new HashMap<>();
            // 解析所有存储的 policy 设置
            for (String key : p.getAll().keySet()) {
                if (key.startsWith("pref_cpu_governor_mode_")) {
                    try {
                        int idx = Integer.parseInt(key.substring("pref_cpu_governor_mode_".length()));
                        s.cpuGovernorModes.put(idx, p.getString(key, "scx"));
                    } catch (Exception ignored) {}
                } else if (key.startsWith("pref_game_cpu_governor_mode_")) {
                    try {
                        int idx = Integer.parseInt(key.substring("pref_game_cpu_governor_mode_".length()));
                        s.gameCpuGovernorModes.put(idx, p.getString(key, "schedutil"));
                    } catch (Exception ignored) {}
                }
            }

            s.liftEnabled = p.getBoolean("pref_lift_to_wake_enabled", false);
            s.liftSensitivity = p.getInt("pref_lift_sensitivity", 3);
            s.liftSensorDelay = p.getInt("pref_lift_sensor_delay", SensorManager.SENSOR_DELAY_NORMAL);
            return s;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Settings)) return false;
            Settings s = (Settings) o;
            return debug == s.debug &&
                    foreground == s.foreground &&
                    fpEnabled == s.fpEnabled &&
                    Math.abs(touchRate - s.touchRate) < 0.01f &&
                    gameModeEnabled == s.gameModeEnabled &&
                    Math.abs(gameTouchRate - s.gameTouchRate) < 0.01f &&
                    Objects.equals(gamePackages, s.gamePackages) &&
                    shortcutEnabled == s.shortcutEnabled &&
                    Objects.equals(shortcutHwType, s.shortcutHwType) &&
                    shortcutLongPress == s.shortcutLongPress &&
                    Objects.equals(shortcutClickAction, s.shortcutClickAction) &&
                    Objects.equals(shortcutLongAction, s.shortcutLongAction) &&
                    Objects.equals(shortcutClickPkg, s.shortcutClickPkg) &&
                    Objects.equals(shortcutLongPkg, s.shortcutLongPkg) &&
                    Objects.equals(shortcutSlider1Action, s.shortcutSlider1Action) &&
                    Objects.equals(shortcutSlider2Action, s.shortcutSlider2Action) &&
                    Objects.equals(shortcutSlider3Action, s.shortcutSlider3Action) &&
                    Objects.equals(shortcutSlider1Pkg, s.shortcutSlider1Pkg) &&
                    Objects.equals(shortcutSlider2Pkg, s.shortcutSlider2Pkg) &&
                    Objects.equals(shortcutSlider3Pkg, s.shortcutSlider3Pkg) &&
                    vibrationEnabled == s.vibrationEnabled &&
                    displayColorMode == s.displayColorMode &&
                    aodBrightnessEnabled == s.aodBrightnessEnabled &&
                    cpuGovernorEnabled == s.cpuGovernorEnabled &&
                    Objects.equals(cpuGovernorModes, s.cpuGovernorModes) &&
                    Objects.equals(gameCpuGovernorModes, s.gameCpuGovernorModes) &&
                    liftEnabled == s.liftEnabled &&
                    liftSensitivity == s.liftSensitivity &&
                    liftSensorDelay == s.liftSensorDelay;
        }
    }
}

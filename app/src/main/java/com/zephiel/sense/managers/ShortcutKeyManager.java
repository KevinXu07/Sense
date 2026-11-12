package com.zephiel.sense.managers;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import com.zephiel.sense.service.SenseService;
import com.zephiel.sense.util.RootUtils;
import com.zephiel.sense.util.SenseLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShortcutKeyManager {

    private final Context context;
    private final SenseService.RootFailureListener rootFailureListener;

    private volatile boolean isEnabled = false;
    private volatile boolean isRunning = false;
    private volatile boolean isDebug = false;

    private volatile Thread thread;
    private volatile Process process;

    private final ExecutorService rootExecutor = Executors.newSingleThreadExecutor();

    // ===== 配置 =====
    private volatile String eventPath = "/dev/input/event0";
    private volatile int targetCode = 0x02df;
    private volatile String hwType = "key";

    private volatile String clickAction = "none";
    private volatile String longPressAction = "none";
    private volatile String slider1Action = "none";
    private volatile String slider2Action = "none";
    private volatile String slider3Action = "none";

    private volatile String clickPackage = "";
    private volatile String longPressPackage = "";
    private volatile String slider1Package = "";
    private volatile String slider2Package = "";
    private volatile String slider3Package = "";

    private int lastMediaVolume = 7;

    private volatile long longPressTimeout = 300;

    // ===== 状态 =====
    private boolean isKeyDown = false;
    private long downTime = 0;
    private boolean isLongPressTriggered = false;

    // 防抖（只用于 DOWN）
    private long lastDownTime = 0;
    private static final long DEBOUNCE_MS = 80;

    private boolean isFlashlightOn = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sliderActionRunnable = () -> {
        rootExecutor.execute(() -> {
            int state = readTristateValue();
            if (state != -1) {
                handler.post(() -> handleTristate(state));
            }
        });
    };

    private final Runnable longPressRunnable = () -> {
        String action;
        String pkg;
        synchronized (this) {
            if (isKeyDown && !isLongPressTriggered) {
                isLongPressTriggered = true;
                action = longPressAction;
                pkg = longPressPackage;
            } else {
                return;
            }
        }
        SenseLog.i("Long press action triggered: %s", action);
        performAction(action, pkg);
        if (isDebug) SenseLog.dRaw("Long press timeout reached");
    };

    public ShortcutKeyManager(Context ctx,
                              SenseService.RootFailureListener listener) {
        this.context = ctx.getApplicationContext();
        this.rootFailureListener = listener;
    }

    // ==============================
    // API
    // ==============================

    public synchronized void setEnabled(boolean enabled) {
        if (this.isEnabled == enabled) return;
        this.isEnabled = enabled;
        SenseLog.i("ShortcutKey enabled: %b", enabled);
        if (enabled) start();
        else stop();
    }

    public void setDebug(boolean debug) {
        this.isDebug = debug;
    }

    public synchronized void setHwType(String type) {
        if (type == null || type.equals(this.hwType)) return;
        this.hwType = type;
        SenseLog.dRaw("HW type set to: %s", type);
        if (isEnabled) {
            stop();
            start();
        }
    }

    public synchronized void setEventPath(String path) {
        if (path == null || path.equals(this.eventPath)) return;
        this.eventPath = path;
        SenseLog.dRaw("Event path set to: %s", path);
        if (isEnabled) {
            stop();
            start();
        }
    }

    public synchronized void setTargetCode(int code) {
        if (this.targetCode == code) return;
        this.targetCode = code;
        SenseLog.dRaw("Target code set to: 0x%04x", code);
        if (isEnabled) {
            stop();
            start();
        }
    }

    public void setActions(String click, String longPress,
                           String clickPkg, String longPkg) {
        this.clickAction = click;
        this.longPressAction = longPress;
        this.clickPackage = clickPkg;
        this.longPressPackage = longPkg;
        SenseLog.dRaw("Actions updated: click=%s, long=%s", click, longPress);
    }

    public void setSliderActions(String s1, String p1,
                                 String s2, String p2,
                                 String s3, String p3) {
        this.slider1Action = s1;
        this.slider1Package = p1;
        this.slider2Action = s2;
        this.slider2Package = p2;
        this.slider3Action = s3;
        this.slider3Package = p3;
        SenseLog.dRaw("Slider actions updated: 1=%s, 2=%s, 3=%s", s1, s2, s3);
    }

    public void setLongPressTimeout(long timeout) {
        this.longPressTimeout = timeout;
        SenseLog.dRaw("Long press timeout set to: %dms", timeout);
    }

    // ==============================
    // Core
    // ==============================

    private synchronized void start() {
        if (isRunning || !isEnabled) return;

        isRunning = true;
        SenseLog.d("Starting ShortcutKey monitor thread...");

        thread = new Thread(() -> {
            final Thread myThread = Thread.currentThread();
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT);

            int retryCount = 0;
            int maxRetries = 10;

            while (isRunning && thread == myThread && retryCount < maxRetries) {
                try {
                    SenseLog.i("ShortcutKey monitor attempt " + (retryCount + 1));
                    if ("slider".equals(hwType)) {
                        runSliderLogic(myThread);
                    } else {
                        runKeyEventLogic(myThread);
                    }
                    
                    // 如果逻辑正常退出（如 reader 为 null），且没有外部停止，则可能是环境暂时失败
                    if (isRunning && thread == myThread) {
                        SenseLog.w("ShortcutKey logic exited unexpectedly, checking retry...");
                    }
                } catch (Exception e) {
                    if (isRunning && thread == myThread) {
                        retryCount++;
                        SenseLog.e("ShortcutKey monitor error (Retry " + retryCount + "/" + maxRetries + ")", e);
                        if (retryCount >= maxRetries) {
                            notifyRootFailure(e);
                        } else {
                            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                        }
                    }
                } finally {
                    if (process != null) {
                        process.destroy();
                        process = null;
                    }
                }
            }

            synchronized (this) {
                if (thread == myThread) {
                    isRunning = false;
                    thread = null;
                    isKeyDown = false;
                }
            }
            SenseLog.dRaw("ShortcutKey thread exiting");

        }, "KeyEventThread");

        thread.start();
    }


    private void runKeyEventLogic(Thread myThread) throws Exception {
        process = new ProcessBuilder("su", "-c", "getevent " + eventPath).start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()), 64);
        String line;
        SenseLog.i("ShortcutKey monitor STARTED with cmd: getevent %s", eventPath);
        while (isRunning && thread == myThread && (line = reader.readLine()) != null) {
            handleLine(line);
        }
    }

    private void runSliderLogic(Thread myThread) {
        String dev = "/dev/input/event5";

        try {
            SenseLog.i("Slider monitor: Event-driven mode (%s)", dev);
            process = new ProcessBuilder("su", "-c", "getevent -l " + dev).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while (isRunning && thread == myThread && (line = reader.readLine()) != null) {
                handleSliderLine(line);
            }
        } catch (Exception e) {
            synchronized (this) {
                if (isRunning && thread == myThread) {
                    SenseLog.e("Slider logic error", e);
                }
            }
        }
    }

    private void handleSliderLine(String line) {
        if (line.contains("KEY_F3")) {
            long now = SystemClock.elapsedRealtime();
            if (line.contains("UP")) {
                onUp(now);
            }
        }
    }

    private int readTristateValue() {
        int retryCount = 0;
        int maxRetries = 10;
        while (retryCount < maxRetries) {
            try {
                RootUtils.CommandResult result = RootUtils.exec("cat /proc/tristatekey/tri_state");
                if (result != null && result.isSuccess()) {
                    String out = result.output.trim();
                    if (!out.isEmpty()) {
                        return Integer.parseInt(out);
                    }
                }
            } catch (Exception e) {
                SenseLog.w("Tristate read attempt " + (retryCount + 1) + " failed: " + e.getMessage());
            }
            retryCount++;
            if (retryCount < maxRetries) {
                SystemClock.sleep(1000);
            }
        }
        SenseLog.e("Tristate read failed after " + maxRetries + " attempts");
        return -1;
    }

    private void handleTristate(int state) {
        String action;
        String pkg;
        switch (state) {
            case 1: action = slider1Action; pkg = slider1Package; break;
            case 2: action = slider2Action; pkg = slider2Package; break;
            case 3: action = slider3Action; pkg = slider3Package; break;
            default: return;
        }

        SenseLog.i("Tristate position: %d, action: %s", state, action);

        if ("none".equals(action)) {
            applyTristateLogic(state);
        } else {
            performAction(action, pkg);
        }
    }

    private void applyTristateLogic(int state) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (am == null || nm == null) return;

        // Ensure permission for DND
        if (!nm.isNotificationPolicyAccessGranted()) {
            SenseLog.w("Cannot apply Tristate logic: Missing DND access permission");
            return;
        }

        int currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);

        switch (state) {
            case 1: // Top: DND + Media Mute
                if (currentVol > 0) lastMediaVolume = currentVol;
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI);
                SenseLog.dRaw("Slider State 1: DND ON, Media 0");
                break;
            case 2: // Middle: Silent Mode + Media Mute
                if (currentVol > 0) lastMediaVolume = currentVol;
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI);
                SenseLog.dRaw("Slider State 2: Silent Mode, Media 0");
                break;
            case 3: // Bottom: Normal + Restore
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
                am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, lastMediaVolume, AudioManager.FLAG_SHOW_UI);
                SenseLog.dRaw("Slider State 3: Normal Mode, Media Restore (%d)", lastMediaVolume);
                break;
        }
    }

    private synchronized void stop() {
        if (!isRunning && process == null && thread == null) return;

        isRunning = false;
        SenseLog.d("Stopping ShortcutKey monitor...");

        if (process != null) {
            process.destroy();
            process = null;
        }

        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        SenseLog.i("ShortcutKey monitor STOPPED");
    }

    private void cleanup() {
        isRunning = false;
        process = null;
        thread = null;
        isKeyDown = false;
    }

    // ==============================
    // 解析 (Standard KeyEvents)
    // ==============================

    private void handleLine(String line) {
        try {
            // 格式：0001 02df 00000001
            if (line.length() < 14) return;

            // type
            int type =
                    (hex(line.charAt(0)) << 12) |
                            (hex(line.charAt(1)) << 8) |
                            (hex(line.charAt(2)) << 4) |
                            hex(line.charAt(3));

            if (type != 0x01) return;

            // code
            int code =
                    (hex(line.charAt(5)) << 12) |
                            (hex(line.charAt(6)) << 8) |
                            (hex(line.charAt(7)) << 4) |
                            hex(line.charAt(8));

            if (code != targetCode) return;

            // value（最后一位就够）
            char v = line.charAt(line.length() - 1);

            long now = SystemClock.elapsedRealtime();

            if (v == '2') return; // REPEAT

            if (v == '1') {
                onDown(now);
            } else if (v == '0') {
                onUp(now);
            }

        } catch (Exception e) {
            if (isDebug) SenseLog.vRaw("Error parsing line: %s", line);
        }
    }

    private int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return 0;
    }

    // ==============================
    // 状态机 (Standard KeyEvents)
    // ==============================

    private void onDown(long now) {
        synchronized (this) {
            if (isKeyDown) return;

            // 防抖
            if (now - lastDownTime < DEBOUNCE_MS) return;
            lastDownTime = now;

            isKeyDown = true;
            downTime = now;
            isLongPressTriggered = false;

            if ("slider".equals(hwType)) {
                // Slider operations handled by onUp only for event-driven logic
            } else {
                handler.postDelayed(longPressRunnable, longPressTimeout);
            }
        }

        if (isDebug) SenseLog.dRaw("Key DOWN");
    }

    private void onUp(long now) {
        boolean shouldClick = false;
        boolean shouldSlider = false;
        long duration;

        synchronized (this) {
            if (!isKeyDown) {
                // For event-driven slider, KEY_F3 UP might arrive without a tracked DOWN in this instance
                if ("slider".equals(hwType)) {
                    shouldSlider = true;
                } else {
                    return;
                }
            } else {
                isKeyDown = false;
                handler.removeCallbacks(longPressRunnable);

                duration = now - downTime;

                if ("slider".equals(hwType)) {
                    shouldSlider = true;
                } else if (!isLongPressTriggered) {
                    shouldClick = true;
                }

                downTime = 0;
                isLongPressTriggered = false;
            }
        }

        if (shouldSlider) {
            handler.removeCallbacks(sliderActionRunnable);
            handler.postDelayed(sliderActionRunnable, 80);
        }

        if (shouldClick) {
            handler.post(() -> {
                SenseLog.i("Click action triggered: %s", clickAction);
                performAction(clickAction, clickPackage);
            });
        }

        if (isDebug && !shouldSlider) SenseLog.dRaw("Key UP");
    }

    // ==============================
    // Actions
    // ==============================

    private void performAction(String action, String pkg) {

        if (action == null || action.equals("none")) return;

        try {
            switch (action) {
                case "toggle_ringer":
                    toggleRinger();
                    break;
                case "toggle_dnd":
                    toggleDnD();
                    break;
                case "launch_app":
                    launch(pkg);
                    break;
                case "toggle_flashlight":
                    toggleFlashlight();
                    break;
                case "screenshot":
                    rootExecutor.execute(() -> {
                        SenseLog.vRaw("Executing custom screenshot sequence...");
                        String cmd = "sendevent /dev/input/event1 1 114 1 && sendevent /dev/input/event1 0 0 0 && " +
                                "sendevent /dev/input/event2 1 116 1 && sendevent /dev/input/event2 0 0 0 && " +
                                "usleep 80000 && " +
                                "sendevent /dev/input/event1 1 114 0 && sendevent /dev/input/event1 0 0 0 && " +
                                "sendevent /dev/input/event2 1 116 0 && sendevent /dev/input/event2 0 0 0";
                        execRoot(cmd);
                    });
                    break;
                case "wechat_pay":
                    rootExecutor.execute(() -> execRoot("am start -n com.tencent.mm/.plugin.offline.ui.WalletOfflineCoinPurseUI"));
                    break;
                case "alipay_pay":
                    rootExecutor.execute(() -> execRoot("am start -a android.intent.action.VIEW -d \"alipays://platformapi/startapp?appId=20000056\""));
                    break;
                case "alipay_scan":
                    rootExecutor.execute(() -> execRoot("am start -n com.eg.android.AlipayGphone/com.alipay.mobile.scan.as.main.MainCaptureActivity"));
                    break;
                case "wechat_scan":
                    rootExecutor.execute(() -> execRoot("am start -n com.tencent.mm/com.tencent.mm.plugin.scanner.ui.BaseScanUI"));
                    break;
            }
        } catch (Throwable t) {
            SenseLog.e("Action %s failed", t, action);
        }
    }

    private void toggleRinger() {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (am == null || nm == null) return;

        // Ensure permission for DND
        if (!nm.isNotificationPolicyAccessGranted()) {
            SenseLog.w("Cannot toggle ringer: Missing DND access permission");
            return;
        }

        int mode = am.getRingerMode();
        int nextMode;
        if (mode == AudioManager.RINGER_MODE_NORMAL) {
            nextMode = AudioManager.RINGER_MODE_SILENT;
        } else {
            nextMode = AudioManager.RINGER_MODE_NORMAL;
        }

        // Standard Android behavior: Normal -> Silent -> Normal (Skipping Vibrate)
        am.setRingerMode(nextMode);
        SenseLog.dRaw("Ringer mode toggled: %d -> %d", mode, nextMode);
    }

    private void toggleDnD() {
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm == null) return;

        if (!nm.isNotificationPolicyAccessGranted()) {
            SenseLog.w("Missing DND access permission");
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return;
        }

        int f = nm.getCurrentInterruptionFilter();
        int nextFilter = f == NotificationManager.INTERRUPTION_FILTER_ALL
                ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL;

        nm.setInterruptionFilter(nextFilter);
        SenseLog.dRaw("DND filter toggled: %d -> %d", f, nextFilter);
    }

    private void launch(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            SenseLog.w("Cannot launch app: package name is empty");
            return;
        }

        SenseLog.dRaw("Launching app: %s", pkg);
        Intent i = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } else {
            SenseLog.e("Launch intent not found for package: %s", pkg);
        }
    }

    private void toggleFlashlight() {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        try {
            String[] ids = cm.getCameraIdList();
            if (ids.length == 0) return;
            String id = ids[0];
            isFlashlightOn = !isFlashlightOn;
            cm.setTorchMode(id, isFlashlightOn);
            SenseLog.dRaw("Flashlight toggled: %b", isFlashlightOn);
        } catch (CameraAccessException e) {
            SenseLog.e("Flashlight toggle failed", e);
        }
    }

    // ==============================
    // Root
    // ==============================

    private void execRoot(String cmd) {
        try {
            RootUtils.execAsync(cmd);
        } catch (Throwable t) {
            if (isEnabled) {
                notifyRootFailure(t);
            }
        }
    }

    private void notifyRootFailure(Throwable t) {
        if (!isEnabled || !isRunning) return;
        SenseLog.e("Root failure in ShortcutKeyManager", t);
        if (rootFailureListener != null) {
            rootFailureListener.onRootFailure();
        }
    }

    public void onDestroy() {
        setEnabled(false);
        rootExecutor.shutdownNow();
    }
}

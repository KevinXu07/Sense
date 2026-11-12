package com.zephiel.sense.managers;

import android.content.Context;
import android.os.PowerManager;

import com.zephiel.sense.service.SenseService;
import com.zephiel.sense.util.RootUtils;
import com.zephiel.sense.util.SenseLog;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class FingerprintFixManager {

    public static final int MODE_DEFAULT = 0;
    public static final int MODE_AOD = 1;

    private static final int WHAT_BEGIN = 5;
    private static final int WHAT_STOP = 6;
    private static final int WHAT_EVENT_TP_TOUCHDOWN = 2004;
    private static final int WHAT_EVENT_TP_TOUCHUP = 2005;

    private final Context context;
    private final PowerManager powerManager;
    private final SenseService.RootFailureListener rootFailureListener;

    private volatile boolean isEnabled = false;
    private volatile boolean isRunning = false;
    private volatile boolean debug = false;

    private final String nodePath = "/sys/kernel/oplus_display/hbm";

    private Process process;
    private Thread thread;

    private volatile boolean touching = false;
    private volatile int lastNodeValue = -1;
    private volatile boolean isFlooding = false;

    public FingerprintFixManager(Context ctx,
                                 SenseService.RootFailureListener listener) {
        this.context = ctx.getApplicationContext();
        this.rootFailureListener = listener;
        this.powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    public synchronized void setEnabled(boolean enabled) {
        if (this.isEnabled == enabled) return;
        this.isEnabled = enabled;
        SenseLog.i("FingerprintFix enabled: %b", enabled);
        if (enabled) start();
        else stop();
    }

    public void onDestroy() {
        isEnabled = false;
        stop();
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }


    private synchronized void start() {
        if (isRunning) return;

        isRunning = true;
        SenseLog.d("Starting FingerprintFix thread...");

        thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            int retryCount = 0;
            int maxRetries = 10;

            while (isRunning && retryCount < maxRetries) {
                try {
                    process = new ProcessBuilder(
                            "su",
                            "-c",
                            "logcat -b main -v brief -T 1 -s \"[FP_HAL][UffMessageQueue]\""
                    ).start();

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()),
                            4096
                    );

                    String line;
                    SenseLog.i("FingerprintFix logcat monitor started (Attempt " + (retryCount + 1) + ")");

                    // 成功启动并开始读取后，在 handleLine 中如果能正常解析，可视为稳定
                    // 这里为了简单，一旦进入循环就重置计数（或者在 handleLine 成功后重置）
                    retryCount = 0;

                    while (isRunning && (line = reader.readLine()) != null) {
                        handleLine(line);
                    }
                } catch (Exception e) {
                    if (isRunning) {
                        retryCount++;
                        SenseLog.e("FingerprintFix monitor error (Retry " + retryCount + "/" + maxRetries + ")", e);
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
                    SenseLog.d("FingerprintFix logcat process cleanup");
                }
            }
            SenseLog.d("FingerprintFix thread exiting");
            isRunning = false;
        }, "FPFixThread");

        thread.start();
    }

    private synchronized void stop() {
        if (!isRunning && process == null && thread == null) return;

        isRunning = false;
        SenseLog.d("Stopping FingerprintFix monitor...");

        stopFlooding();

        if (process != null) {
            process.destroy();
            process = null;
        }

        if (thread != null) {
            thread.interrupt();
            thread = null;
        }

        touching = false;
        lastNodeValue = -1;
        SenseLog.i("FingerprintFix monitor stopped");
    }

    private void handleLine(String line) {
        if (line == null || line.length() < 20) return;

        int idx = indexOfWhat(line);
        if (idx == -1) return;

        int what = parseWhat(line, idx);
        if (what == -1) return;

        if (what == WHAT_BEGIN) {
            if (!touching) {
                touching = true;
                startFlooding();
                if (debug) SenseLog.dRaw("Event: WHAT_BEGIN -> startFlooding()");
            }
        } else if (what == WHAT_EVENT_TP_TOUCHDOWN) {
            wake();
            if (debug) SenseLog.dRaw("Event: WHAT_EVENT_TP_TOUCHDOWN -> wake()");
        } else if (what == WHAT_STOP || what == WHAT_EVENT_TP_TOUCHUP) {
            if (touching) {
                touching = false;
                stopFlooding();
                writeNode(0);
                if (debug) SenseLog.dRaw("Event: STOP/UP -> echo 0");
            }
        }
    }

    private void startFlooding() {
        if (isFlooding) return;
        isFlooding = true;
        floodNext();
    }

    private void floodNext() {
        if (!isFlooding || !isRunning) return;
        lastNodeValue = 1;
        RootUtils.execAsync("echo 1 > " + nodePath, (result) -> {
            floodNext();
        });
    }

    private void stopFlooding() {
        isFlooding = false;
    }

    private int indexOfWhat(String line) {
        for (int i = 0; i < line.length() - 5; i++) {
            if (line.charAt(i) == 'w'
                    && line.charAt(i + 1) == 'h'
                    && line.charAt(i + 2) == 'a'
                    && line.charAt(i + 3) == 't'
                    && line.charAt(i + 4) == ':') {
                return i + 5;
            }
        }
        return -1;
    }

    private int parseWhat(String line, int start) {
        int value = 0;
        boolean found = false;

        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c >= '0' && c <= '9') {
                value = value * 10 + (c - '0');
                found = true;
            } else {
                break;
            }
        }

        return found ? value : -1;
    }

    private void writeNode(int value) {
        writeNode(value, false);
    }

    private void writeNode(int value, boolean force) {
        if (!force && lastNodeValue == value) return;
        lastNodeValue = value;

        if (debug) SenseLog.vRaw("Writing HBM node: %s = %d (force=%b)", nodePath, value, force);
        execRoot("echo " + value + " > " + nodePath);
    }

    private void execRoot(String cmd) {
        try {
            RootUtils.execAsync(cmd);
        } catch (Throwable t) {
            SenseLog.e("Failed to execute root command: %s", t, cmd);
            notifyRootFailure(t);
        }
    }

    private void wake() {
        if (powerManager != null) {
            if (debug) SenseLog.vRaw("Waking screen via PowerManager...");
            try {
                PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                | PowerManager.ACQUIRE_CAUSES_WAKEUP
                                | PowerManager.ON_AFTER_RELEASE,
                        "Sense:FingerprintWake"
                );
                wakeLock.acquire(1000);
            } catch (Exception e) {
                SenseLog.e("Failed to wake screen via PowerManager", e);
            }
        }
    }

    private void notifyRootFailure(Throwable t) {
        if (!isEnabled || !isRunning) {
            SenseLog.w("Root failure ignored because manager is stopping/stopped: %s", t.getMessage());
            return;
        }

        SenseLog.e("Root failure in FingerprintFixManager", t);

        if (rootFailureListener != null) {
            rootFailureListener.onRootFailure();
        }
    }
}

package com.zephiel.sense.managers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import com.zephiel.sense.service.SenseService;
import com.zephiel.sense.util.RootUtils;
import com.zephiel.sense.util.SenseLog;

import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CpuGovernorManager {

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SenseService.RootFailureListener rootFailureListener;

    private boolean isDebugLoggingEnabled = false;
    private volatile boolean isEnabled = false;
    private volatile boolean isReceiverRegistered = false;

    private boolean gameModeEnabled = false;
    private java.util.Set<String> gamePackages = new java.util.HashSet<>();
    private Map<Integer, String> dailyModes = new HashMap<>();
    private Map<Integer, String> gameModes = new HashMap<>();
    private boolean isInGame = false;
    private String currentPackage = "";

    public static class CpuPolicy {
        public int index;
        public String cpuRange;
        public List<String> availableGovernors;

        public CpuPolicy(int index, String cpuRange, List<String> availableGovernors) {
            this.index = index;
            this.cpuRange = cpuRange;
            this.availableGovernors = availableGovernors;
        }
    }

    public CpuGovernorManager(Context context, SenseService.RootFailureListener listener) {
        this.context = context.getApplicationContext();
        this.rootFailureListener = listener;
    }

    public void setDebugLogging(boolean enabled) {
        this.isDebugLoggingEnabled = enabled;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        if (!enabled) {
            unregisterReceiver();
        }
    }

    public void updateSettings(boolean gameEnabled, java.util.Set<String> packages,
                               Map<Integer, String> daily, Map<Integer, String> game) {
        this.gameModeEnabled = gameEnabled;
        this.gamePackages = packages != null ? packages : new java.util.HashSet<>();
        this.dailyModes = daily != null ? daily : new HashMap<>();
        this.gameModes = game != null ? game : new HashMap<>();

        if (isEnabled && gameModeEnabled) {
            registerReceiver();
            // Re-check current package
            if (!currentPackage.isEmpty()) {
                onForegroundAppChanged(currentPackage);
            } else {
                applyCurrentState();
            }
        } else {
            unregisterReceiver();
            isInGame = false;
            applyCurrentState();
        }
    }

    private final BroadcastReceiver appReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.zephiel.sense.ACTION_FOREGROUND_APP_CHANGED".equals(intent.getAction())) {
                String pkg = intent.getStringExtra("package_name");
                if (pkg != null) {
                    onForegroundAppChanged(pkg);
                }
            }
        }
    };

    private void onForegroundAppChanged(String packageName) {
        if (isTransientSystemPackage(packageName)) return;

        this.currentPackage = packageName;
        boolean nowInGame = gameModeEnabled && gamePackages.contains(packageName);

        if (nowInGame != isInGame) {
            isInGame = nowInGame;
            if (isDebugLoggingEnabled) SenseLog.d("CPU Game state transition: isInGame=%b", isInGame);
            applyCurrentState();
        }
    }

    private boolean isTransientSystemPackage(String pkg) {
        return pkg == null || pkg.equals("com.android.systemui") || pkg.equals("com.miui.securitycenter") ||
                pkg.contains("inputmethod") || pkg.equals("android") || pkg.contains("launcher");
    }

    private void applyCurrentState() {
        if (!isEnabled) return;
        
        Map<Integer, String> targetModes = isInGame ? gameModes : dailyModes;
        if (targetModes.isEmpty()) return;

        for (Map.Entry<Integer, String> entry : targetModes.entrySet()) {
            applyGovernor(entry.getKey(), entry.getValue());
        }
    }

    private synchronized void registerReceiver() {
        if (isReceiverRegistered) return;
        IntentFilter filter = new IntentFilter("com.zephiel.sense.ACTION_FOREGROUND_APP_CHANGED");
        ContextCompat.registerReceiver(context, appReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        isReceiverRegistered = true;
    }

    private synchronized void unregisterReceiver() {
        if (!isReceiverRegistered) return;
        try {
            context.unregisterReceiver(appReceiver);
        } catch (Exception ignored) {}
        isReceiverRegistered = false;
    }

    public void onDestroy() {
        isEnabled = false;
        unregisterReceiver();
        try {
            executor.shutdownNow();
        } catch (Exception e) {
            SenseLog.e("Error shutting down CpuGovernorManager executor", e);
        }
    }

    public void applyGovernor(int policyIndex, String governor) {
        if (!isEnabled) return;
        
        String path = "/sys/devices/system/cpu/cpufreq/policy" + policyIndex + "/scaling_governor";
        // 尝试修改权限并写入，增加成功率
        String cmd = String.format("chmod 644 %s && echo %s > %s", path, governor, path);
        if (isDebugLoggingEnabled) SenseLog.vRaw("Executing CPU governor update: %s", cmd);

        attemptApplyGovernor(policyIndex, governor, cmd, 0);
    }

    private void attemptApplyGovernor(int policyIndex, String governor, String cmd, int retryCount) {
        RootUtils.execAsync(cmd, result -> {
            if (result != null && result.isSuccess()) {
                SenseLog.i("Successfully applied %s to policy%d", governor, policyIndex);
                return;
            }

            if (retryCount < 10) {
                SenseLog.w("Failed to apply CPU governor to policy%d, retrying (%d/10)...",
                        policyIndex, retryCount + 1);
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        attemptApplyGovernor(policyIndex, governor, cmd, retryCount + 1), 1000);
            } else {
                SenseLog.e("Failed to apply CPU governor after retries");
                if (rootFailureListener != null) {
                    rootFailureListener.onRootFailure();
                }
            }
        });
    }


    public List<CpuPolicy> getPolicies() {
        List<CpuPolicy> policies = new ArrayList<>();
        int retries = 10;
        while (retries > 0) {
            try {
                // 获取所有 policy 文件夹名称
                RootUtils.CommandResult result = RootUtils.exec("ls -d /sys/devices/system/cpu/cpufreq/policy*", 2000);
                if (result != null && result.isSuccess()) {
                    String[] lines = result.output.split("\\n");
                    for (String path : lines) {
                        path = path.trim();
                        if (path.isEmpty()) continue;
                        
                        try {
                            int idx = Integer.parseInt(path.substring(path.lastIndexOf("policy") + 6).trim());
                            
                            // 获取该 policy 影响的 CPU 范围
                            RootUtils.CommandResult rangeRes = RootUtils.exec("cat " + path + "/affected_cpus", 1000);
                            String range = (rangeRes != null && rangeRes.isSuccess()) ? rangeRes.output.trim() : "";
                            
                            // 获取该 policy 可用的调度器
                            RootUtils.CommandResult availsRes = RootUtils.exec("cat " + path + "/scaling_available_governors", 1000);
                            String avails = (availsRes != null && availsRes.isSuccess()) ? availsRes.output.trim() : "";
                            
                            List<String> governorList = Arrays.asList(avails.split("\\s+"));
                            policies.add(new CpuPolicy(idx, range, governorList));
                        } catch (Exception e) {
                            SenseLog.e("Error parsing CPU policy at " + path, e);
                        }
                    }
                    if (!policies.isEmpty()) return policies;
                }
            } catch (Exception e) {
                SenseLog.e("Failed to get CPU policies, retrying...", e);
            }
            retries--;
            if (retries > 0) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        return policies;
    }


    private void notifyRootFailure(Throwable t) {
        if (!isEnabled) return;
        if (rootFailureListener != null) {
            rootFailureListener.onRootFailure();
        }
    }
}

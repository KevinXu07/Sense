package com.zephiel.sense.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.zephiel.sense.util.SenseLog;

import java.util.List;

public class SenseAccessibilityService extends AccessibilityService {

    public static final String ACTION_FOREGROUND_APP_CHANGED = "com.zephiel.sense.ACTION_FOREGROUND_APP_CHANGED";
    public static final String EXTRA_PACKAGE_NAME = "package_name";

    private String lastPackageName = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int eventType = event.getEventType();
        CharSequence eventPkg = event.getPackageName();
        String packageName = (eventPkg != null) ? eventPkg.toString() : null;

        // --- 性能预筛选 (非常重要) ---
        // 1. 如果是普通 UI 事件且包名未变，直接跳过所有复杂逻辑。
        // 这解决了在游戏内操作（滑动、点击）导致的 CPU 占用问题。
        boolean isWindowChangeEvent = (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED);

        if (!isWindowChangeEvent && packageName != null && packageName.equals(lastPackageName)) {
            return;
        }

        // 2. 如果包名是系统界面或发生了窗口变化，我们进行深度探测以确认真实的前台应用。
        if (isWindowChangeEvent || isTransientSystemPackage(packageName)) {
            String deepPkg = null;

            // A. 遍历所有窗口 (注意：必须手动 recycle 每个 AccessibilityWindowInfo 以防内存泄漏)
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                boolean foundActive = false;
                for (AccessibilityWindowInfo window : windows) {
                    // 如果还没找到活跃窗口，继续探测
                    if (!foundActive && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root != null) {
                            CharSequence p = root.getPackageName();
                            if (p != null) {
                                String foundPkg = p.toString();
                                // 优先选择活跃或有焦点的应用窗口
                                if (window.isActive() || window.isFocused()) {
                                    deepPkg = foundPkg;
                                    foundActive = true;
                                } else if (deepPkg == null && !isTransientSystemPackage(foundPkg)) {
                                    // 兜底：记录第一个找到的非系统应用包名
                                    deepPkg = foundPkg;
                                }
                            }
                            root.recycle(); // 显式回收 Node
                        }
                    }
                    window.recycle(); // 关键：必须回收 WindowInfo，否则在部分 Android 版本上会造成内存泄漏
                }
            }

            // B. 如果遍历没结果，尝试获取活跃窗口根节点
            if (deepPkg == null) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    CharSequence p = root.getPackageName();
                    if (p != null) deepPkg = p.toString();
                    root.recycle();
                }
            }

            if (deepPkg != null) {
                packageName = deepPkg;
            }
        }

        // --- 广播分发 ---
        if (packageName != null && !packageName.isEmpty() &&
                !packageName.equals(getPackageName()) && !packageName.equals(lastPackageName)) {

            lastPackageName = packageName;

            Intent intent = new Intent(ACTION_FOREGROUND_APP_CHANGED);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_PACKAGE_NAME, packageName);
            sendBroadcast(intent);
        }
    }

    private boolean isTransientSystemPackage(String pkg) {
        return pkg == null || pkg.equals("com.android.systemui") || pkg.equals("android");
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            // 关键：必须保留此标志以探测分屏/小窗
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            // 关键：不限制 eventTypes，保证灵敏度；性能优化移至 onAccessibilityEvent 内部。
            setServiceInfo(info);
        }
        SenseLog.i("SenseAccessibilityService connected (balanced mode)");
    }

    @Override
    public void onDestroy() {
        SenseLog.i("SenseAccessibilityService onDestroy");
        // 显式清理配置，辅助系统回收 Binder 引用
        try {
            setServiceInfo(new AccessibilityServiceInfo());
        } catch (Exception ignored) {}
        lastPackageName = "";
        super.onDestroy();
    }
}

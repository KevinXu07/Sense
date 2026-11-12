package com.zephiel.sense.util;

import android.util.Log;

import java.util.Locale;

public class SenseLog {

    private static final String TAG = "Sense";
    private static volatile boolean isRuntimeLoggingEnabled = false;

    public static void setLoggingEnabled(boolean enabled) {
        isRuntimeLoggingEnabled = enabled;
    }

    private static boolean shouldLog(int priority) {
        // 级别高于 INFO 的（W, E）始终打印，INFO 及以下的受开关控制
        return isRuntimeLoggingEnabled || priority >= Log.INFO;
    }

    // ==============================
    // 标准方法 (带调用者信息)
    // ==============================

    public static void v(String msg, Object... args) {
        if (isRuntimeLoggingEnabled) log(Log.VERBOSE, true, msg, args);
    }

    public static void d(String msg, Object... args) {
        if (isRuntimeLoggingEnabled) log(Log.DEBUG, true, msg, args);
    }

    public static void i(String msg, Object... args) {
        log(Log.INFO, true, msg, args);
    }

    public static void w(String msg, Object... args) {
        log(Log.WARN, true, msg, args);
    }

    public static void e(String msg, Object... args) {
        log(Log.ERROR, true, msg, args);
    }

    public static void e(String msg, Throwable t, Object... args) {
        if (shouldLog(Log.ERROR)) {
            Log.e(TAG, "[" + getCallerInfo() + "] " + format(msg, args), t);
        }
    }

    // ==============================
    // 原始方法 (高性能，不带调用者信息)
    // ==============================

    public static void vRaw(String msg, Object... args) {
        if (isRuntimeLoggingEnabled) log(Log.VERBOSE, false, msg, args);
    }

    public static void dRaw(String msg, Object... args) {
        if (isRuntimeLoggingEnabled) log(Log.DEBUG, false, msg, args);
    }

    // ==============================
    // 核心实现
    // ==============================

    private static void log(int priority, boolean includeCaller, String msg, Object... args) {
        if (!shouldLog(priority)) return;

        // 优化：仅在明确要求包含调用者，且（开启了运行时调试 或 属于警告/错误级别）时才生成堆栈
        String prefix = "";
        if (includeCaller && (isRuntimeLoggingEnabled || priority >= Log.WARN)) {
            prefix = "[" + getCallerInfo() + "] ";
        }
        
        String content = format(msg, args);
        Log.println(priority, TAG, prefix + content);
    }

    // ==============================
    // 子标签支持 (用于 BootReceiver 等特定场景)
    // ==============================

    public static void subV(String subTag, String msg, Object... args) {
        if (isRuntimeLoggingEnabled) Log.v(TAG + "_" + subTag, format(msg, args));
    }

    public static void subD(String subTag, String msg, Object... args) {
        if (isRuntimeLoggingEnabled) Log.d(TAG + "_" + subTag, format(msg, args));
    }

    public static void subI(String subTag, String msg, Object... args) {
        Log.i(TAG + "_" + subTag, format(msg, args));
    }

    public static void subW(String subTag, String msg, Object... args) {
        Log.w(TAG + "_" + subTag, format(msg, args));
    }

    public static void subE(String subTag, String msg, Object... args) {
        Log.e(TAG + "_" + subTag, format(msg, args));
    }

    public static void subE(String subTag, String msg, Throwable t) {
        Log.e(TAG + "_" + subTag, msg, t);
    }

    // ==============================
    // 性能优化工具
    // ==============================

    private static String format(String msg, Object... args) {
        if (args == null || args.length == 0) {
            return msg;
        }
        try {
            return String.format(Locale.US, msg, args);
        } catch (Exception e) {
            return msg + " (format error)";
        }
    }

    /**
     * 高性能获取调用者信息。
     * 相比 Thread.currentThread().getStackTrace()，
     * new Throwable().getStackTrace() 在 Android 上快得多。
     */
    private static String getCallerInfo() {
        StackTraceElement[] stackTrace = new Throwable().getStackTrace();
        if (stackTrace == null || stackTrace.length < 4) {
            return "Unknown";
        }

        // 栈深度说明：
        // [0] getCallerInfo
        // [1] log
        // [2] v/d/i/w/e
        // [3] 真正的调用者
        StackTraceElement caller = stackTrace[3];
        String className = caller.getClassName();
        
        // 简写类名
        int lastDot = className.lastIndexOf('.');
        if (lastDot != -1) {
            className = className.substring(lastDot + 1);
        }

        return className + "." + caller.getMethodName() + ":" + caller.getLineNumber();
    }
}

package com.zephiel.sense;

import android.app.Application;
import android.content.Context;

import com.google.android.material.color.DynamicColors;

/**
 * Application 入口类
 *
 * <p>主要职责：
 * 1. 应用启动时初始化全局配置
 * 2. 启用 Material You 动态配色（如果系统支持）
 * 3. 将默认 SharedPreferences 迁移到设备保护存储（Device Protected Storage）
 *
 * <p>注意：
 * Device Protected Storage 允许在用户未解锁设备时访问数据（如开机后）
 */
public class SenseApplication extends Application {

    /**
     * 应用创建时调用（进程启动入口）
     */
    @Override
    public void onCreate() {
        super.onCreate();

        // 将默认 SharedPreferences 迁移到设备保护存储
        migrateDefaultPreferencesToDeviceProtectedStorage();

        // 应用 Material You 动态颜色（仅在支持的设备上生效）
        DynamicColors.applyToActivitiesIfAvailable(this);
    }

    /**
     * 将默认 SharedPreferences 从 Credential Protected Storage
     * 迁移到 Device Protected Storage
     *
     * <p>使用场景：
     * - 需要在设备未解锁时访问数据（例如开机广播、后台服务）
     *
     * <p>实现方式：
     * - 获取 Device Protected Context
     * - 调用 moveSharedPreferencesFrom 进行迁移
     *
     * <p>异常处理：
     * - 捕获所有异常（兼容低版本设备或迁移失败）
     * - 失败时继续使用原存储，不影响应用运行
     */
    private void migrateDefaultPreferencesToDeviceProtectedStorage() {
        try {
            // 获取设备保护存储上下文
            Context dp = createDeviceProtectedStorageContext();

            // 默认 SharedPreferences 文件名
            String defaultPrefsName = getPackageName() + "_preferences";

            // 执行迁移
            dp.moveSharedPreferencesFrom(this, defaultPrefsName);

        } catch (Throwable ignored) {
            // 某些设备/系统版本不支持或迁移失败
            // 忽略异常，继续使用原有存储以保证应用稳定运行
        }
    }
}
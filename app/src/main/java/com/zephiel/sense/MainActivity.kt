package com.zephiel.sense

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.zephiel.sense.service.SenseService
import com.zephiel.sense.ui.SettingsScreen
import com.zephiel.sense.ui.theme.SenseTheme

/**
 * 应用主界面 Activity
 *
 * <p>主要职责：
 * 1. 启动并确保后台服务 [SenseService] 运行
 * 2. 读取用户主题配置（动态颜色 / 强调色）
 * 3. 初始化 Jetpack Compose UI
 *
 * <p>存储说明：
 * - 使用 Device Protected Storage（设备保护存储）
 * - 确保在设备未解锁时也可访问配置数据
 */
class MainActivity : ComponentActivity() {

    /**
     * 确保 SenseService 正在运行
     *
     * <p>实现方式：
     * - 根据用户配置启动前台或后台服务
     *
     * <p>异常处理：
     * - 捕获所有异常（如系统限制、后台启动限制等）
     * - 避免因服务启动失败导致应用崩溃
     */
    private fun ensureSenseServiceRunning() {
        val sharedPreferences = createDeviceProtectedStorageContext()
            .getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)
        val useForeground = sharedPreferences.getBoolean("pref_foreground_service", false)
        
        try {
            val intent = Intent(this, SenseService::class.java)
            if (useForeground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {
            // 忽略异常，保证主流程不中断
        }
    }

    /**
     * Activity 创建入口
     *
     * @param savedInstanceState 之前保存的状态（可能为 null）
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 启用边到边显示（Edge-to-Edge UI）
        enableEdgeToEdge()

        // 启动服务
        ensureSenseServiceRunning()

        // 从设备保护存储中读取 SharedPreferences
        val sharedPreferences = createDeviceProtectedStorageContext()
            .getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)

        // 初始化 Compose UI
        setContent {

            /**
             * 是否启用动态颜色（Material You）
             *
             * 默认值：true
             */
            var dynamicColor by remember {
                mutableStateOf(
                    sharedPreferences.getBoolean("pref_dynamic_color", true)
                )
            }

            /**
             * 用户自定义强调色（Accent Color）
             *
             * - 存储为 Int（ARGB）
             * - -1 表示未设置（使用系统或默认主题）
             */
            var accentColor by remember {
                val colorInt = sharedPreferences.getInt("theme_accent_color", -1)
                mutableStateOf(
                    if (colorInt == -1) null else Color(colorInt)
                )
            }

            // 应用主题
            SenseTheme(
                dynamicColor = dynamicColor,
                seedColor = accentColor
            ) {

                /**
                 * 设置界面
                 *
                 * 提供：
                 * - 动态颜色开关
                 * - 强调色选择
                 */
                SettingsScreen(
                    onDynamicColorChange = { dynamicColor = it },
                    onAccentColorChange = { accentColor = it }
                )
            }
        }
    }

    /**
     * Activity 恢复到前台时调用
     *
     * <p>作用：
     * - 再次确保服务存活（防止被系统回收）
     */
    override fun onResume() {
        super.onResume()
        ensureSenseServiceRunning()
    }
}
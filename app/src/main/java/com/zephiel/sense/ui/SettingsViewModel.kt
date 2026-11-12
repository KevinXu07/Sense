package com.zephiel.sense.ui

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings as AndroidSettings
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zephiel.sense.R
import com.zephiel.sense.managers.CpuGovernorManager
import com.zephiel.sense.managers.ModuleControlManager
import com.zephiel.sense.service.SenseService
import com.zephiel.sense.util.RootUtils
import com.zephiel.sense.util.SenseLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * 处理 UI 文本的封装类，支持动态字符串和资源 ID。
 * 允许 ViewModel 在不持有 Context 的情况下管理 UI 消息。
 */
sealed class UiText {
    data class DynamicString(val value: String) : UiText()
    class StringResource(@StringRes val resId: Int, vararg val args: Any) : UiText()

    @Composable
    fun asString(): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> androidx.compose.ui.res.stringResource(resId, *args)
        }
    }

    /**
     * 在非 Composable 环境（如 LaunchedEffect）下解析字符串
     */
    fun asString(context: Context): String {
        return when (this) {
            is DynamicString -> value
            is StringResource -> context.getString(resId, *args)
        }
    }
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext: Context get() = getApplication<Application>().applicationContext
    
    private val sharedPreferences: SharedPreferences = run {
        val dp = appContext.createDeviceProtectedStorageContext()
        dp.getSharedPreferences(appContext.packageName + "_preferences", Context.MODE_PRIVATE)
    }

    private val _snackbarMessage = MutableSharedFlow<SnackbarEvent>()
    val snackbarMessage = _snackbarMessage.asSharedFlow()

    sealed class SnackbarEvent {
        data class Message(
            val message: UiText, 
            val actionLabel: UiText? = null, 
            val onAction: (() -> Unit)? = null
        ) : SnackbarEvent()
    }

    private val TOUCH_SAMPLING_RATE_STOPS = floatArrayOf(120f, 180f, 240f, 360f)

    private fun Float.snapToTouchSamplingRateStop(): Float =
        TOUCH_SAMPLING_RATE_STOPS.minByOrNull { abs(it - this) } ?: 120f

    fun rateToIndex(rate: Float): Float {
        val snapped = rate.snapToTouchSamplingRateStop()
        val idx = TOUCH_SAMPLING_RATE_STOPS.indexOfFirst { abs(it - snapped) < 0.5f }
        return (if (idx >= 0) idx else 1).toFloat()
    }

    fun touchRateToIndex(): Float = rateToIndex(touchRate)
    fun gameTouchRateToIndex(): Float = rateToIndex(gameTouchRate)

    fun touchSamplingIndexToRate(index: Float): Float {
        val i = index.toInt().coerceIn(0, TOUCH_SAMPLING_RATE_STOPS.size - 1)
        return TOUCH_SAMPLING_RATE_STOPS[i]
    }

    // --- 设置状态 ---
    var fpFixEnabled by mutableStateOf(sharedPreferences.getBoolean("pref_fp_fix_enabled", false))
    var liftToWakeEnabled by mutableStateOf(
        AndroidSettings.System.getInt(
            appContext.contentResolver, "pick_up_gesture_wakeup_mode", 0
        ) == 1
    )
    var liftSensitivity by mutableFloatStateOf(sharedPreferences.getInt("pref_lift_sensitivity", 3).toFloat())
    var liftSensorDelay by mutableIntStateOf(sharedPreferences.getInt("pref_lift_sensor_delay", SensorManager.SENSOR_DELAY_NORMAL))


    var shortcutEnabled by mutableStateOf(sharedPreferences.getBoolean("pref_shortcut_enabled", false))
    var shortcutHwType by mutableStateOf(sharedPreferences.getString("pref_shortcut_hw_type", "key") ?: "key")
    var shortcutClickAction by mutableStateOf(sharedPreferences.getString("pref_shortcut_click_action", "none") ?: "none")
    var shortcutClickPackage by mutableStateOf(sharedPreferences.getString("pref_shortcut_click_package", "") ?: "")
    var shortcutLongAction by mutableStateOf(sharedPreferences.getString("pref_shortcut_long_press_action", "none") ?: "none")
    var shortcutLongPackage by mutableStateOf(sharedPreferences.getString("pref_shortcut_long_press_package", "") ?: "")
    var shortcutSlider1Action by mutableStateOf(sharedPreferences.getString("pref_shortcut_slider_1_action", "none") ?: "none")
    var shortcutSlider2Action by mutableStateOf(sharedPreferences.getString("pref_shortcut_slider_2_action", "none") ?: "none")
    var shortcutSlider3Action by mutableStateOf(sharedPreferences.getString("pref_shortcut_slider_3_action", "none") ?: "none")
    var shortcutSlider1Package by mutableStateOf(sharedPreferences.getString("pref_shortcut_slider_1_package", "") ?: "")
    var shortcutSlider2Package by mutableStateOf(sharedPreferences.getString("pref_shortcut_slider_2_package", "") ?: "")
    var shortcutSlider3Package by mutableStateOf(sharedPreferences.getString("pref_shortcut_slider_3_package", "") ?: "")
    var shortcutLongPressTimeout by mutableFloatStateOf(sharedPreferences.getInt("pref_shortcut_long_press_timeout", 300).toFloat())

    var touchRate by mutableFloatStateOf(sharedPreferences.getFloat("pref_touch_rate", 120f).snapToTouchSamplingRateStop())
    var gameModeEnabled by mutableStateOf(sharedPreferences.getBoolean("pref_game_mode_enabled", false))
    var gameTouchRate by mutableFloatStateOf(sharedPreferences.getFloat("pref_game_touch_rate", 240f).snapToTouchSamplingRateStop())
    var gamePackages by mutableStateOf(sharedPreferences.getStringSet("pref_game_packages", emptySet()) ?: emptySet())

    var vibrationEnabled by mutableStateOf(sharedPreferences.getBoolean("pref_vibration_enabled", false))
    var hapticScale by mutableFloatStateOf(sharedPreferences.getFloat("pref_haptic_scale", 1.0f))
    var ifaaModel by mutableStateOf(sharedPreferences.getString("pref_ifaa_model", "ONEPLUS-R23821") ?: "ONEPLUS-R23821")
    var moduleStatus by mutableStateOf<ModuleControlManager.Status?>(null)
    var moduleStatusText by mutableStateOf("正在读取模块状态…")
    var moduleBusy by mutableStateOf(false)
    var performanceProfile by mutableStateOf(
        sharedPreferences.getString("pref_performance_profile", "balanced") ?: "balanced"
    )
    var touchDriverStatus by mutableStateOf("正在读取触控驱动…")
    var cpuGovernorEnabled by mutableStateOf(sharedPreferences.getBoolean("pref_cpu_governor_enabled", false))
    var cpuPolicies by mutableStateOf(emptyList<CpuGovernorManager.CpuPolicy>())
    val cpuGovernorModes = mutableStateMapOf<Int, String>()
    val gameCpuGovernorModes = mutableStateMapOf<Int, String>()
    var displayColorMode by mutableIntStateOf(sharedPreferences.getInt("pref_display_color_mode", 0))
    var aodBrightnessEnabled by mutableStateOf(sharedPreferences.getBoolean("pref_aod_brightness_enabled", false))
    var foregroundServiceEnabled by mutableStateOf(sharedPreferences.getBoolean("pref_foreground_service", false))
    var debugLoggingEnabled by mutableStateOf(sharedPreferences.getBoolean("pref_debug_logging_enabled", false))
    var dynamicColorEnabled by mutableStateOf(sharedPreferences.getBoolean("pref_dynamic_color", true))
    var followDynamicColor by mutableStateOf(sharedPreferences.getBoolean("pref_follow_dynamic_color", false))
    var showAodRequirementPrompt by mutableStateOf(sharedPreferences.getBoolean("pref_show_aod_requirement_prompt", true))


    // 会话级标志：仅在应用运行期间有效，重启后重置
    var hasShownRootHighlight = false

    // --- 颜色配置 ---
    var globalAccentColor by mutableStateOf(getStoredColor("theme_accent_color", Color(0xFF1A73E8)))
    var systemEnhanceColor by mutableStateOf(getStoredColor("theme_system_enhance_color", Color(0xFF1A73E8)))
    var interactionColor by mutableStateOf(getStoredColor("theme_interaction_color", Color(0xFF188038)))
    var personalizationColor by mutableStateOf(getStoredColor("theme_personalization_color", Color(0xFFF9AB00)))
    var systemSettingsColor by mutableStateOf(getStoredColor("theme_system_settings_color", Color(0xFFD93025)))

    var hasRoot by mutableStateOf(sharedPreferences.getBoolean("pref_last_known_root", false))
    var hasNotificationPolicyAccess by mutableStateOf(true)
    var hasPostNotificationPermission by mutableStateOf(true)
    var isAccessibilityServiceEnabled by mutableStateOf(false)
    var isKSUInstalled by mutableStateOf(false)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "pref_last_known_root") {
            val newVal = prefs.getBoolean(key, false)
            if (hasRoot && !newVal) {
                viewModelScope.launch {
                    _snackbarMessage.emit(SnackbarEvent.Message(UiText.StringResource(R.string.toast_root_failure)))
                }
            }
            hasRoot = newVal
        }
    }

    private val accessibilityReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.zephiel.sense.ACCESSIBILITY_STATE_CHANGED") {
                val enabled = intent.getBooleanExtra("enabled", false)
                viewModelScope.launch {
                    _snackbarMessage.emit(
                        SnackbarEvent.Message(
                            UiText.StringResource(
                                if (enabled) R.string.accessibility_service_enabled 
                                else R.string.accessibility_service_disabled
                            )
                        )
                    )
                }
            }
        }
    }

    init {
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefListener)
        
        val filter = android.content.IntentFilter("com.zephiel.sense.ACCESSIBILITY_STATE_CHANGED")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(accessibilityReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(accessibilityReceiver, filter)
            }
        } catch (e: Exception) {
            SenseLog.e("Failed to register accessibility receiver", e)
        }
        
        refreshSystemStatus()
        loadAvailableGovernors()
    }

    private fun loadAvailableGovernors() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val manager = CpuGovernorManager(appContext, null)
                val policies = manager.getPolicies()
                
                withContext(Dispatchers.Main) {
                    cpuPolicies = policies
                    policies.forEach { policy ->
                        val key = "pref_cpu_governor_mode_${policy.index}"
                        val gameKey = "pref_game_cpu_governor_mode_${policy.index}"
                        
                        // 尝试从磁盘读取，如果没有则初始化默认值
                        if (!sharedPreferences.contains(key)) {
                            val current = RootUtils.exec("cat /sys/devices/system/cpu/cpufreq/policy${policy.index}/scaling_governor").output.trim()
                            val defaultMode = if (policy.availableGovernors.contains("scx")) "scx" else current
                            
                            // 立即保存到磁盘，以便 Service 能监听到
                            sharedPreferences.edit(commit = true) { putString(key, defaultMode) }
                            cpuGovernorModes[policy.index] = defaultMode
                        } else {
                            cpuGovernorModes[policy.index] = sharedPreferences.getString(key, "scx") ?: "scx"
                        }

                        if (!sharedPreferences.contains(gameKey)) {
                            val defaultGameMode = if (policy.availableGovernors.contains("schedutil")) "schedutil" else (cpuGovernorModes[policy.index] ?: "scx")
                            sharedPreferences.edit(commit = true) { putString(gameKey, defaultGameMode) }
                            gameCpuGovernorModes[policy.index] = defaultGameMode
                        } else {
                            gameCpuGovernorModes[policy.index] = sharedPreferences.getString(gameKey, "schedutil") ?: "schedutil"
                        }
                    }
                }
            } catch (e: Exception) {
                SenseLog.e("Failed to load CPU policies", e)
            }
        }
    }

    fun updateCpuGovernor(policyIndex: Int, mode: String) {
        cpuGovernorModes[policyIndex] = mode
        updatePreference("pref_cpu_governor_mode_$policyIndex", mode)
        updateService()
    }

    fun updateGameCpuGovernor(policyIndex: Int, mode: String) {
        gameCpuGovernorModes[policyIndex] = mode
        updatePreference("pref_game_cpu_governor_mode_$policyIndex", mode)
        updateService()
    }

    override fun onCleared() {
        super.onCleared()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefListener)
        try {
            appContext.unregisterReceiver(accessibilityReceiver)
        } catch (e: Exception) {
            // 忽略未注册或重复注销的异常
        }
    }

    private fun getStoredColor(key: String, default: Color): Color {
        val argb = sharedPreferences.getInt(key, -1)
        return if (argb == -1) default else Color(argb)
    }

    fun refreshSystemStatus() {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        hasNotificationPolicyAccess = nm.isNotificationPolicyAccessGranted

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPostNotificationPermission = ContextCompat.checkSelfPermission(
                appContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            hasPostNotificationPermission = true
        }

        isAccessibilityServiceEnabled = isAccessibilityServiceEnabled(appContext, com.zephiel.sense.service.SenseAccessibilityService::class.java)

        isKSUInstalled = try {
            appContext.packageManager.getPackageInfo("me.weishu.kernelsu", 0)
            true
        } catch (e: Exception) {
            false
        }

        viewModelScope.launch {
            val rootAvailable = withContext(Dispatchers.IO) { RootUtils.isRootAvailable() }
            if (rootAvailable != hasRoot) {
                hasRoot = rootAvailable
                sharedPreferences.edit { putBoolean("pref_last_known_root", rootAvailable) }
                updateService()
            }
        }
        liftToWakeEnabled = AndroidSettings.System.getInt(
            appContext.contentResolver, "pick_up_gesture_wakeup_mode", 0
        ) == 1
        refreshTouchDiagnostics()
        refreshModuleStatus()
    }

    fun refreshTouchDiagnostics() {
        viewModelScope.launch {
            touchDriverStatus = withContext(Dispatchers.IO) {
                val supported = RootUtils.exec(
                    "grep -q 'report_rate_white_list_support' " +
                            "/data/vendor/touchconfig/sys_touch_function_feature_config.xml " +
                            "&& grep -q 'game_mode_divided_report_rate_support' " +
                            "/data/vendor/touchconfig/sys_touch_function_feature_config.xml " +
                            "&& echo 1",
                    3000
                )
                val register = RootUtils.exec(
                    "grep 'report rate' /proc/touchpanel/debug_info/main_register 2>/dev/null",
                    3000
                )
                val field = register?.output?.trim()?.substringAfterLast(':')?.trim()
                    ?.ifEmpty { "未知" } ?: "未知"
                if (supported != null && "1" == supported.output.trim()) {
                    "report_rate 已启用（驱动档位 $field），可调节 120/180/240/360Hz"
                } else {
                    "report_rate 未启用；重启模块后会应用 touchDaemon 配置"
                }
            }
        }
    }

    fun setTouchSamplingRate(rate: Int) {
        if (rate !in intArrayOf(120, 180, 240, 360)) return
        touchRate = rate.toFloat()
        updatePreference("pref_touch_rate", touchRate)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val write = RootUtils.exec(
                    "settings put global op13_fix_touch_sampling_rate $rate; " +
                            "settings put global op13_fix_touch_owner module; " +
                            "/odm/bin/touchHidlTest -c wo 0 182 $rate",
                    5000
                )
                write?.isSuccess == true
            }
            refreshModuleStatus()
            refreshTouchDiagnostics()
            _snackbarMessage.emit(
                SnackbarEvent.Message(
                    UiText.DynamicString(
                        if (ok) "触控采样率已切换为 ${rate}Hz"
                        else "触控采样率写入失败"
                    )
                )
            )
        }
    }

    fun commitLiftToWake(enabled: Boolean) {
        liftToWakeEnabled = enabled
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                RootUtils.exec(
                    "settings put system pick_up_gesture_wakeup_mode " +
                            (if (enabled) "1" else "0"), 3000
                )?.isSuccess == true
            }
            if (!ok) {
                liftToWakeEnabled = !enabled
                _snackbarMessage.emit(
                    SnackbarEvent.Message(UiText.DynamicString("写入系统抬起亮屏开关失败"))
                )
            }
        }
    }

    fun applyPerformanceProfile(profile: String) {
        val governor = when (profile) {
            "powersave" -> "powersave"
            "performance" -> "performance"
            else -> "schedutil"
        }
        performanceProfile = profile
        updatePreference("pref_performance_profile", profile)
        cpuGovernorEnabled = true
        updatePreference("pref_cpu_governor_enabled", true)
        cpuPolicies.forEach { policy ->
            cpuGovernorModes[policy.index] = governor
            updatePreference("pref_cpu_governor_mode_${policy.index}", governor)
        }
        updateService()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                RootUtils.exec(
                    "for p in /sys/devices/system/cpu/cpufreq/policy*; " +
                            "do echo $governor > \$p/scaling_governor; done",
                    5000
                )
            }
            _snackbarMessage.emit(
                SnackbarEvent.Message(
                    UiText.DynamicString(
                        when (profile) {
                            "powersave" -> "性能调度已切换为省电"
                            "performance" -> "性能调度已切换为性能"
                            else -> "性能调度已切换为均衡"
                        }
                    )
                )
            )
        }
    }

    fun commitGameMode(enabled: Boolean) {
        gameModeEnabled = enabled
        updatePreference("pref_game_mode_enabled", enabled)
        if (enabled) {
            cpuGovernorEnabled = true
            updatePreference("pref_cpu_governor_enabled", true)
            cpuPolicies.forEach { policy ->
                gameCpuGovernorModes[policy.index] = "performance"
                updatePreference("pref_game_cpu_governor_mode_${policy.index}", "performance")
            }
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    RootUtils.exec(
                        "for p in /sys/devices/system/cpu/cpufreq/policy*; " +
                                "do echo performance > \$p/scaling_governor; done",
                        5000
                    )
                }
            }
        }
        updateService()
    }

    fun refreshModuleStatus() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { ModuleControlManager.load() }.getOrNull()
            }
            moduleStatus = status
            if (status == null) {
                moduleStatusText = "无法读取 KSU 模块状态"
                return@launch
            }
            moduleStatusText = buildModuleStatusText(status)
            if (status.hapticScale.isNotEmpty()) {
                hapticScale = status.hapticScale.toFloatOrNull() ?: hapticScale
            }
            if (status.ifaaModel.isNotEmpty()) {
                ifaaModel = status.ifaaModel
            }
            if (status.touchSupported) {
                status.touchRate.toFloatOrNull()?.let { touchRate = it }
            }
        }
    }

    private fun buildModuleStatusText(status: ModuleControlManager.Status): String {
        if (!status.moduleInstalled) {
            return "未检测到 KSU 模块 rmx5010_fod_bridge"
        }
        return buildString {
            append("KSU ").append(status.moduleVersion.ifEmpty { "?" })
            append(" · LSP ").append(if (status.lspActive) "已激活" else "未激活")
            append(10.toChar()).append("钱包 ").append(if (status.walletInstalled) "已安装" else "未安装")
            append(" · 刷卡").append(if (status.walletAppop == "allow") "已授权" else "未授权")
        }
    }

    fun applyHapticScale() {
        val value = hapticScale
        updatePreference("pref_haptic_scale", value)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                ModuleControlManager.setHapticScale(value)
            }
            _snackbarMessage.emit(
                SnackbarEvent.Message(
                    UiText.DynamicString(
                        if (ok) "触感强度已更新为 ${"%.1f".format(value)}×"
                        else "触感强度写入失败"
                    )
                )
            )
        }
    }

    fun testHaptic() {
        val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(80L, 255))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(45L)
        }
    }

    fun applyIfaaModel(value: String) {
        val model = value.trim()
        if (!model.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            viewModelScope.launch {
                _snackbarMessage.emit(
                    SnackbarEvent.Message(UiText.DynamicString("IFAA 型号格式无效"))
                )
            }
            return
        }
        ifaaModel = model
        updatePreference("pref_ifaa_model", model)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val written = ModuleControlManager.setIfaaModel(model)
                if (written) ModuleControlManager.restartIfaa()
                written
            }
            _snackbarMessage.emit(
                SnackbarEvent.Message(
                    UiText.DynamicString(
                        if (ok) "IFAA 型号已更新，下次绑定生效"
                        else "IFAA 型号写入失败"
                    )
                )
            )
        }
    }

    fun testWalletSwipe() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                ModuleControlManager.grantWalletShowWhenLocked()
                        && ModuleControlManager.testWalletSwipe()
            }
            _snackbarMessage.emit(
                SnackbarEvent.Message(
                    UiText.DynamicString(
                        if (ok) "已启动钱包刷卡页"
                        else "启动失败：请确认已安装 FinShell 钱包"
                    )
                )
            )
        }
    }

    fun reapplyModule() {
        if (moduleBusy) return
        moduleBusy = true
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                ModuleControlManager.reapply()
            }
            val status = withContext(Dispatchers.IO) {
                runCatching { ModuleControlManager.load() }.getOrNull()
            }
            moduleStatus = status
            if (status != null) moduleStatusText = buildModuleStatusText(status)
            moduleBusy = false
            _snackbarMessage.emit(
                SnackbarEvent.Message(
                    UiText.DynamicString(
                        if (ok) "模块配置已重新应用"
                        else "模块配置应用失败"
                    )
                )
            )
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
        val expectedComponentName = android.content.ComponentName(context, service)
        val enabledServicesSetting = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) return true
        }
        return false
    }

    fun updateService() {
        val intent = Intent(appContext, SenseService::class.java).apply { action = "UPDATE_SETTINGS" }
        viewModelScope.launch {
            // 移除 120ms 的人为延迟，配置更改应当立即可见
            try {
                if (foregroundServiceEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) { /* ignore */ }
        }
    }

    fun restartService() {
        try {
            appContext.stopService(Intent(appContext, SenseService::class.java))
            viewModelScope.launch {
                delay(500)
                val intent = Intent(appContext, SenseService::class.java)
                if (foregroundServiceEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                _snackbarMessage.emit(SnackbarEvent.Message(UiText.StringResource(R.string.service_running_desc)))
            }
        } catch (e: Exception) { /* ignore */ }
    }

    fun resetSettings(onDynamicColorChange: (Boolean) -> Unit, onAccentColorChange: (Color?) -> Unit) {
        sharedPreferences.edit { clear() }
        fpFixEnabled = false
        liftToWakeEnabled = AndroidSettings.System.getInt(
            appContext.contentResolver, "pick_up_gesture_wakeup_mode", 0
        ) == 1
        liftSensitivity = 3f
        liftSensorDelay = SensorManager.SENSOR_DELAY_NORMAL
        shortcutEnabled = false
        shortcutHwType = "key"
        shortcutClickAction = "none"
        shortcutClickPackage = ""
        shortcutLongAction = "none"
        shortcutLongPackage = ""
        shortcutSlider1Action = "none"
        shortcutSlider2Action = "none"
        shortcutSlider3Action = "none"
        shortcutSlider1Package = ""
        shortcutSlider2Package = ""
        shortcutSlider3Package = ""
        shortcutLongPressTimeout = 300f

        touchRate = 120f
        gameModeEnabled = false
        gameTouchRate = 240f
        gamePackages = emptySet()
        vibrationEnabled = false
        hapticScale = 1.0f
        ifaaModel = "ONEPLUS-R23821"
        cpuGovernorEnabled = false
        cpuGovernorModes.clear()
        gameCpuGovernorModes.clear()
        performanceProfile = "balanced"
        displayColorMode = 0
        aodBrightnessEnabled = false
        foregroundServiceEnabled = false
        debugLoggingEnabled = false
        dynamicColorEnabled = true
        followDynamicColor = false
        showAodRequirementPrompt = true
        globalAccentColor = Color(0xFF1A73E8)
        systemEnhanceColor = Color(0xFF1A73E8)
        interactionColor = Color(0xFF188038)
        personalizationColor = Color(0xFFF9AB00)
        systemSettingsColor = Color(0xFFD93025)

        hasShownRootHighlight = false
        loadAvailableGovernors()
        updateService()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ModuleControlManager.setHapticScale(1.0f)
                ModuleControlManager.setIfaaModel("ONEPLUS-R23821")
            }
            refreshModuleStatus()
        }
        onDynamicColorChange(true)
        onAccentColorChange(Color(0xFF1A73E8))
        viewModelScope.launch {
            _snackbarMessage.emit(SnackbarEvent.Message(UiText.StringResource(R.string.reset_success)))
        }
    }

    fun showVibrationPrompt() {
        viewModelScope.launch {
            _snackbarMessage.emit(SnackbarEvent.Message(
                UiText.StringResource(R.string.linear_vibration_restart_prompt),
                UiText.StringResource(R.string.action_ok)
            ))
        }
    }

    fun showNotificationPermissionRequiredHint() {
        viewModelScope.launch {
            _snackbarMessage.emit(SnackbarEvent.Message(
                UiText.StringResource(R.string.notification_permission_required)
            ))
        }
    }

    fun showAodRequirementPrompt() {
        if (!showAodRequirementPrompt) return
        viewModelScope.launch {
            _snackbarMessage.emit(SnackbarEvent.Message(
                UiText.StringResource(R.string.aod_requirement_prompt),
                UiText.StringResource(R.string.action_dont_show_again),
                onAction = {
                    showAodRequirementPrompt = false
                    updatePreference("pref_show_aod_requirement_prompt", false)
                }
            ))
        }
    }

    fun updatePreference(key: String, value: Any) {
        val isForegroundKey = "pref_foreground_service" == key
        sharedPreferences.edit(commit = isForegroundKey) {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    putStringSet(key, value as Set<String>)
                }
            }
        }
    }


    fun saveColor(key: String, color: Color) {
        sharedPreferences.edit { putInt(key, color.toArgb()) }
    }

    fun installKernelSU(context: Context) {
        try {
            val ksuPkg = "me.weishu.kernelsu"
            val intent = context.packageManager.getLaunchIntentForPackage(ksuPkg)
            if (intent != null) {
                context.startActivity(intent)
            } else {
                val apkFile = File(context.cacheDir, "KernelSU.apk")
                context.assets.open("KernelSU.apk").use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }
        } catch (e: Exception) {
            viewModelScope.launch {
                _snackbarMessage.emit(SnackbarEvent.Message(UiText.DynamicString("Error: ${e.message}")))
            }
        }
    }
}

package com.zephiel.sense.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.AppShortcut
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.AvTimer
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Brightness4
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.FilterTiltShift
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PanToolAlt
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SettingsInputComponent
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zephiel.sense.R
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import android.provider.Settings as AndroidSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onDynamicColorChange: (Boolean) -> Unit = {},
    onAccentColorChange: (Color?) -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    var rootHighlightTrigger by remember { mutableIntStateOf(0) }

    // Scroll and Highlight when Root Warning appears
    val showRootBanner = !viewModel.hasRoot
    LaunchedEffect(showRootBanner) {
        if (showRootBanner) {
            // Wait slightly for visibility animation to start so scroll is smoother
            delay(100)
            listState.animateScrollToItem(0)
            if (!viewModel.hasShownRootHighlight) {
                rootHighlightTrigger++
                viewModel.hasShownRootHighlight = true
                // Wait for animation to finish then reset trigger to avoid repeat on scroll
                delay(2000)
                rootHighlightTrigger = 0
            }
        }
    }

    // Observe snackbar events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { event ->
            // Use launch to avoid blocking the collection of new messages
            launch {
                when (event) {
                    is SettingsViewModel.SnackbarEvent.Message -> {
                        // Dismiss current snackbar before showing a new one
                        snackbarHostState.currentSnackbarData?.dismiss()

                        // 使用 asString(context) 在非 Composable 环境下转换 UiText
                        val message = event.message.asString(context)
                        val actionLabel = event.actionLabel?.asString(context)

                        val result = snackbarHostState.showSnackbar(
                            message = message,
                            actionLabel = actionLabel,
                            duration = SnackbarDuration.Short
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            event.onAction?.invoke()
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSystemStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val effectiveColor = @Composable { custom: Color ->
        if (viewModel.followDynamicColor) {
            if (viewModel.dynamicColorEnabled) MaterialTheme.colorScheme.primary else viewModel.globalAccentColor
        } else {
            custom
        }
    }

    val systemEnhanceEff = effectiveColor(viewModel.systemEnhanceColor)
    val interactionEff = effectiveColor(viewModel.interactionColor)
    val personalizationEff = effectiveColor(viewModel.personalizationColor)
    val systemSettingsEff = effectiveColor(viewModel.systemSettingsColor)

    var showResetConfirmation by remember { mutableStateOf(false) }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            title = { Text(stringResource(R.string.reset_defaults_title)) },
            text = { Text(stringResource(R.string.reset_defaults_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resetSettings(onDynamicColorChange, onAccentColorChange)
                        showResetConfirmation = false
                    }
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(32.dp)
        )
    }

    Scaffold(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) },
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                modifier = Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeDefaults.style(
                        backgroundColor = Color.Transparent,
                        tint = HazeDefaults.tint(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = scrollBehavior.state.collapsedFraction * 0.7f))
                    )
                ),
                title = {
                    val collapsedFraction = scrollBehavior.state.collapsedFraction
                    val expandedStyle = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, lineHeight = 48.sp)
                    val collapsedStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp)
                    Text(text = stringResource(R.string.app_name), style = lerp(expandedStyle, collapsedStyle, collapsedFraction))
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        snackbarHost = { SettingsSnackbarHost(snackbarHostState, hazeState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 48.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    AnimatedVisibility(
                        visible = !viewModel.hasRoot,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        RootWarningBanner(
                            modifier = Modifier.highlightAnimation(rootHighlightTrigger, MaterialTheme.colorScheme.error, cornerRadius = 28.dp),
                            isKSUInstalled = viewModel.isKSUInstalled
                        ) {
                            viewModel.installKernelSU(context)
                        }
                    }
                }

                item {
                    SettingsSection(title = stringResource(R.string.system_enhance_title), titleColor = systemEnhanceEff) {
                        SystemEnhanceSection(viewModel, systemEnhanceEff)
                    }
                }

                item {
                    SettingsSection(title = stringResource(R.string.module_fix_title), titleColor = interactionEff) {
                        ModuleFixSection(viewModel, interactionEff)
                    }
                }

                item {
                    SettingsSection(title = stringResource(R.string.interaction_gestures_title), titleColor = interactionEff) {
                        InteractionGesturesSection(viewModel, interactionEff)
                    }
                }

                item {
                    SettingsSection(title = stringResource(R.string.personalization_title), titleColor = personalizationEff) {
                        PersonalizationSection(viewModel, personalizationEff, onDynamicColorChange, onAccentColorChange)
                    }
                }

                item {
                    SettingsSection(title = stringResource(R.string.system_about_title), titleColor = systemSettingsEff) {
                        AboutSection(viewModel, systemSettingsEff, onShowReset = { showResetConfirmation = true })
                    }
                }
            }
        }
    }
}

@Composable
fun SystemEnhanceSection(
    viewModel: SettingsViewModel,
    accentColor: Color
) {
    AccentSliderPreferenceRow(
        title = stringResource(R.string.touch_daily_rate_title),
        valueLabel = "${viewModel.touchRate.toInt()}Hz",
        icon = Icons.Rounded.PanToolAlt,
        accentColor = accentColor,
        value = viewModel.touchRateToIndex(),
        onValueChange = {
            viewModel.touchRate = viewModel.touchSamplingIndexToRate(it)
        },
        onValueChangeFinished = {
            viewModel.setTouchSamplingRate(viewModel.touchRate.toInt())
        },
        enabled = viewModel.hasRoot && viewModel.moduleStatus?.touchSupported == true,
        valueRange = 0f..3f,
        steps = 2
    )

    InsetDivider()

    SwitchPreference(
        title = stringResource(R.string.aod_brightness_title),
        summary = stringResource(R.string.aod_brightness_summary),
        icon = Icons.Rounded.Brightness4,
        iconColor = accentColor,
        controlColor = accentColor,
        checked = viewModel.aodBrightnessEnabled,
        enabled = viewModel.hasRoot,
        onCheckedChange = {
            viewModel.aodBrightnessEnabled = it
            viewModel.updatePreference("pref_aod_brightness_enabled", it)
            viewModel.updateService()
        }
    )

    InsetDivider()

    ListPreference(
        title = stringResource(R.string.performance_profile_title),
        icon = Icons.Rounded.Speed,
        iconColor = accentColor,
        entries = listOf(
            stringResource(R.string.performance_profile_powersave),
            stringResource(R.string.performance_profile_balanced),
            stringResource(R.string.performance_profile_performance)
        ),
        values = listOf("powersave", "balanced", "performance"),
        currentValue = viewModel.performanceProfile,
        enabled = viewModel.hasRoot,
        onValueChange = { viewModel.applyPerformanceProfile(it) }
    )

    InsetDivider()

    SwitchPreference(
        title = stringResource(R.string.touch_game_enhance_title),
        summary = stringResource(R.string.touch_enhance_summary),
        icon = Icons.Rounded.SportsEsports,
        iconColor = accentColor,
        controlColor = accentColor,
        checked = viewModel.gameModeEnabled,
        enabled = viewModel.hasRoot,
        onCheckedChange = { viewModel.commitGameMode(it) }
    )

    AnimatedVisibility(visible = viewModel.hasRoot && viewModel.gameModeEnabled, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column {
            InsetDivider()
            MultiAppSelectorPreference(
                title = stringResource(R.string.touch_game_apps_title),
                iconColor = accentColor,
                selectedPackages = viewModel.gamePackages,
                enabled = viewModel.hasRoot && viewModel.gameModeEnabled,
                onAppsSelected = {
                    viewModel.gamePackages = it
                    viewModel.updatePreference("pref_game_packages", it)
                    viewModel.updateService()
                }
            )
        }
    }
}

@Composable
fun ModuleFixSection(viewModel: SettingsViewModel, accentColor: Color) {
    val status = viewModel.moduleStatus
    val moduleReady = status?.moduleInstalled == true

    PreferenceItem(
        title = stringResource(R.string.module_status_title),
        summary = viewModel.moduleStatusText,
        icon = Icons.Rounded.Tune,
        iconColor = accentColor,
        enabled = viewModel.hasRoot,
        onClick = { viewModel.refreshModuleStatus() }
    )

    InsetDivider()

    AccentSliderPreferenceRow(
        title = stringResource(R.string.haptic_strength_title),
        valueLabel = String.format(Locale.US, "%.1f×", viewModel.hapticScale),
        icon = Icons.Rounded.Vibration,
        accentColor = accentColor,
        value = viewModel.hapticScale,
        onValueChange = { viewModel.hapticScale = it },
        onValueChangeFinished = { viewModel.applyHapticScale() },
        enabled = viewModel.hasRoot && moduleReady,
        valueRange = 0.3f..1.2f,
        steps = 8
    )

    InsetDivider()

    PreferenceItem(
        title = stringResource(R.string.haptic_test_title),
        summary = stringResource(R.string.haptic_test_summary),
        icon = Icons.Rounded.Vibration,
        iconColor = accentColor,
        enabled = viewModel.hasRoot && moduleReady,
        onClick = { viewModel.testHaptic() }
    )

    InsetDivider()

    EditTextPreference(
        title = stringResource(R.string.ifaa_model_title),
        summary = viewModel.ifaaModel,
        icon = Icons.Rounded.Fingerprint,
        iconColor = accentColor,
        value = viewModel.ifaaModel,
        onValueChange = { viewModel.applyIfaaModel(it) },
        enabled = viewModel.hasRoot && moduleReady,
        suggestions = listOf(
            "ONEPLUS-R23821" to "OnePlus 13",
            "PJZ110" to "OnePlus 13 ColorOS",
            "CPH2653" to "OPPO"
        )
    )

    InsetDivider()

    PreferenceItem(
        title = stringResource(R.string.wallet_swipe_test_title),
        summary = stringResource(R.string.wallet_swipe_test_summary),
        icon = Icons.Rounded.Radar,
        iconColor = accentColor,
        enabled = viewModel.hasRoot && status?.walletInstalled == true,
        onClick = { viewModel.testWalletSwipe() }
    )

    InsetDivider()

    PreferenceItem(
        title = stringResource(R.string.module_reapply_title),
        summary = if (viewModel.moduleBusy) "正在应用…" else stringResource(R.string.module_reapply_summary),
        icon = Icons.Rounded.Refresh,
        iconColor = accentColor,
        enabled = viewModel.hasRoot && moduleReady && !viewModel.moduleBusy,
        onClick = { viewModel.reapplyModule() }
    )
}

@Composable
fun InteractionGesturesSection(viewModel: SettingsViewModel, accentColor: Color) {
    SwitchPreference(
        title = stringResource(R.string.lift_to_wake_title),
        summary = stringResource(R.string.lift_to_wake_summary),
        icon = Icons.Rounded.Smartphone,
        iconColor = accentColor,
        controlColor = accentColor,
        checked = viewModel.liftToWakeEnabled,
        enabled = viewModel.hasRoot,
        onCheckedChange = { viewModel.commitLiftToWake(it) }
    )
}

@Composable
fun PersonalizationSection(
    viewModel: SettingsViewModel,
    accentColor: Color,
    onDynamicColorChange: (Boolean) -> Unit,
    onAccentColorChange: (Color?) -> Unit
) {
    ColorPreference(
        title = stringResource(R.string.theme_accent_color),
        icon = Icons.Rounded.Palette,
        iconColor = accentColor,
        currentColor = viewModel.globalAccentColor,
        isDynamicColorEnabled = viewModel.dynamicColorEnabled,
        onDynamicColorToggle = {
            viewModel.dynamicColorEnabled = it
            viewModel.updatePreference("pref_dynamic_color", it)
            onDynamicColorChange(it)
        },
        onColorSelected = {
            viewModel.globalAccentColor = it
            viewModel.saveColor("theme_accent_color", it)
            onAccentColorChange(it)
        }
    )

    SwitchPreference(
        title = stringResource(R.string.theme_follow_accent),
        summary = stringResource(R.string.dynamic_color_summary),
        icon = Icons.Rounded.AutoFixHigh,
        iconColor = accentColor,
        controlColor = accentColor,
        checked = viewModel.followDynamicColor,
        onCheckedChange = {
            viewModel.followDynamicColor = it
            viewModel.updatePreference("pref_follow_dynamic_color", it)
        }
    )

    AnimatedVisibility(visible = !viewModel.followDynamicColor, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column {
            InsetDivider()
            GroupedColorPreference(
                title = stringResource(R.string.theme_edit_colors),
                icon = Icons.Rounded.Brush,
                iconColor = accentColor,
                items = listOf(
                    ColorItem(stringResource(R.string.system_enhance_title), viewModel.systemEnhanceColor),
                    ColorItem(stringResource(R.string.interaction_gestures_title), viewModel.interactionColor),
                    ColorItem(stringResource(R.string.personalization_title), viewModel.personalizationColor),
                    ColorItem(stringResource(R.string.system_about_title), viewModel.systemSettingsColor)
                ),
                enabled = !viewModel.followDynamicColor,
                onColorSelected = { index, color ->
                    when (index) {
                        0 -> { viewModel.systemEnhanceColor = color; viewModel.saveColor("theme_system_enhance_color", color) }
                        1 -> { viewModel.interactionColor = color; viewModel.saveColor("theme_interaction_color", color) }
                        2 -> { viewModel.personalizationColor = color; viewModel.saveColor("theme_personalization_color", color) }
                        3 -> { viewModel.systemSettingsColor = color; viewModel.saveColor("theme_system_settings_color", color) }
                    }
                }
            )
        }
    }
}

@Composable
fun AboutSection(viewModel: SettingsViewModel, accentColor: Color, onShowReset: () -> Unit) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            viewModel.hasPostNotificationPermission = granted
            if (granted) {
                viewModel.foregroundServiceEnabled = true
                viewModel.updatePreference("pref_foreground_service", true)
                viewModel.restartService()
            } else {
                viewModel.showNotificationPermissionRequiredHint()
            }
        }
    )

    SwitchPreference(
        title = stringResource(R.string.enable_foreground_service),
        summary = stringResource(R.string.enable_foreground_service_summary),
        icon = Icons.Rounded.Bolt,
        iconColor = accentColor,
        controlColor = accentColor,
        checked = viewModel.foregroundServiceEnabled,
        onCheckedChange = { enabled ->
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return@SwitchPreference
                }
            }
            viewModel.foregroundServiceEnabled = enabled
            viewModel.updatePreference("pref_foreground_service", enabled)
            viewModel.restartService()
        }
    )

    SwitchPreference(
        title = stringResource(R.string.enable_debug_logging),
        summary = stringResource(R.string.enable_debug_logging_summary),
        icon = Icons.Rounded.BugReport,
        iconColor = accentColor,
        controlColor = accentColor,
        checked = viewModel.debugLoggingEnabled,
        enabled = true,
        onCheckedChange = {
            viewModel.debugLoggingEnabled = it
            viewModel.updatePreference("pref_debug_logging_enabled", it)
            viewModel.updateService()
        }
    )

    AnimatedVisibility(
        visible = viewModel.debugLoggingEnabled,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column {
            InsetDivider()
            PreferenceItem(
                title = stringResource(R.string.view_debug_logs_title),
                summary = stringResource(R.string.view_debug_logs_summary),
                icon = Icons.AutoMirrored.Rounded.TextSnippet,
                iconColor = accentColor,
                onClick = {
                    context.startActivity(Intent(context, LogActivity::class.java))
                }
            )
        }
    }

    PreferenceItem(
        title = stringResource(R.string.restart_service_title),
        summary = stringResource(R.string.restart_service_summary),
        icon = Icons.Rounded.Refresh,
        iconColor = accentColor,
        onClick = { viewModel.restartService() }
    )

    PreferenceItem(
        title = stringResource(R.string.reset_defaults_title),
        summary = stringResource(R.string.reset_defaults_summary),
        icon = Icons.Rounded.RestartAlt,
        iconColor = accentColor,
        onClick = onShowReset
    )

    PreferenceItem(
        title = stringResource(R.string.about_title),
        summary = stringResource(R.string.about_summary),
        icon = Icons.Rounded.Info,
        iconColor = accentColor,
        onClick = { context.startActivity(Intent(context, AboutActivity::class.java)) }
    )
}

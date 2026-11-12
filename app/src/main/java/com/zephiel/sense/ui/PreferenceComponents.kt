package com.zephiel.sense.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zephiel.sense.R
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val COLOR_PALETTE = listOf(
    Color(0xFF1A73E8), Color(0xFFD93025), Color(0xFF188038), Color(0xFFF9AB00),
    Color(0xFF9C27B0), Color(0xFF00BCD4), Color(0xFFE91E63), Color(0xFF607D8B),
    Color(0xFF009688), Color(0xFF8BC34A), Color(0xFFCDDC39), Color(0xFFFFC107),
    Color(0xFFFF9800), Color(0xFFFF5722), Color(0xFF795548), Color(0xFF9E9E9E),
    Color(0xFF673AB7), Color(0xFF3F51B5), Color(0xFF2196F3), Color(0xFF03A9F4),
    Color(0xFF4CAF50), Color(0xFFFFEB3B), Color(0xFF6200EE), Color(0xFF3700B3),
    Color(0xFF03DAC6), Color(0xFF018786), Color(0xFFB00020), Color(0xFF000000)
)

@Composable
fun Modifier.highlightAnimation(trigger: Int, color: Color = MaterialTheme.colorScheme.primary, cornerRadius: Dp = 28.dp): Modifier = composed {
    if (trigger == 0) return@composed this

    val alpha = remember { Animatable(0f) }

    LaunchedEffect(trigger) {
        repeat(2) {
            alpha.animateTo(0.35f, animationSpec = tween(400))
            alpha.animateTo(0f, animationSpec = tween(400))
        }
    }

    this.drawWithContent {
        drawContent()
        drawRoundRect(
            color = color.copy(alpha = alpha.value),
            cornerRadius = CornerRadius(cornerRadius.toPx())
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    titleColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            color = titleColor,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp
        ) {
            Column { content() }
        }
    }
}

@Composable
fun InsetDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp, end = 16.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
fun AccentSliderPreferenceRow(
    title: String,
    valueLabel: String,
    icon: ImageVector,
    accentColor: Color,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveIconColor = if (enabled) accentColor else disabledColor

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(effectiveIconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = effectiveIconColor, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.Unspecified else disabledColor)
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) accentColor else disabledColor
            )
        }
        Slider(
            modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            colors = if (enabled) {
                SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = accentColor.copy(alpha = 0.24f),
                    activeTickColor = Color.White.copy(alpha = 0.6f),
                    inactiveTickColor = accentColor.copy(alpha = 0.4f)
                )
            } else {
                SliderDefaults.colors()
            }
        )
    }
}

@Composable
fun SwitchPreference(
    title: String,
    summary: String?,
    icon: ImageVector,
    iconColor: Color,
    controlColor: Color,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveIconColor = if (enabled) iconColor else disabledColor
    val effectiveControlColor = if (enabled) controlColor else disabledColor

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled) { onCheckedChange(!checked) },
        color = Color.Transparent
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(effectiveIconColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = effectiveIconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.Unspecified else disabledColor)
                if (summary != null) {
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Color.Unspecified else disabledColor)
                }
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                thumbContent = {
                    Icon(
                        imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = effectiveControlColor,
                    checkedIconColor = effectiveControlColor,
                    uncheckedThumbColor = if (enabled) effectiveControlColor.copy(alpha = 0.4f) else disabledColor.copy(alpha = 0.4f),
                    uncheckedTrackColor = if (enabled) effectiveControlColor.copy(alpha = 0.1f) else disabledColor.copy(alpha = 0.1f),
                    uncheckedBorderColor = if (enabled) effectiveControlColor.copy(alpha = 0.2f) else disabledColor.copy(alpha = 0.2f),
                    uncheckedIconColor = Color.White,
                    disabledCheckedThumbColor = Color.White.copy(alpha = 0.38f),
                    disabledCheckedTrackColor = disabledColor.copy(alpha = 0.12f),
                    disabledUncheckedThumbColor = disabledColor.copy(alpha = 0.12f),
                    disabledUncheckedTrackColor = disabledColor.copy(alpha = 0.05f)
                )
            )
        }
    }
}

@Composable
fun PreferenceItem(title: String, summary: String, icon: ImageVector, iconColor: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveIconColor = if (enabled) iconColor else disabledColor

    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled) { onClick() }, color = Color.Transparent) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(effectiveIconColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = effectiveIconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (enabled) Color.Unspecified else disabledColor)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Color.Unspecified else disabledColor)
            }
            Spacer(Modifier.width(16.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = if (enabled) MaterialTheme.colorScheme.outlineVariant else disabledColor, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun ListPreference(title: String, summary: String? = null, icon: ImageVector, iconColor: Color, entries: List<String>, values: List<String>, currentValue: String, enabled: Boolean = true, onValueChange: (String) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveIconColor = if (enabled) iconColor else disabledColor
    val currentEntry = values.indexOf(currentValue).let { if (it != -1) entries[it] else currentValue }

    Box {
        Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled) { showMenu = true }, color = Color.Transparent) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(effectiveIconColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = effectiveIconColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (enabled) Color.Unspecified else disabledColor)
                    Text(summary ?: currentEntry, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Color.Unspecified else disabledColor)
                }
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Rounded.UnfoldMore, null, tint = if (enabled) MaterialTheme.colorScheme.outlineVariant else disabledColor, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = showMenu && enabled, onDismissRequest = { showMenu = false }) {
            values.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(entries[index]) },
                    onClick = { onValueChange(value); showMenu = false },
                    trailingIcon = if (currentValue == value) { { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) } } else null
                )
            }
        }
    }
}

@Composable
fun ActionSelectorPreference(title: String, iconColor: Color, currentAction: String, currentPackage: String, enabled: Boolean = true, onActionSelected: (String, String?) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveIconColor = if (enabled) iconColor else disabledColor
    val actionEntries = stringArrayResource(R.array.shortcut_action_entries)
    val actionValues = stringArrayResource(R.array.shortcut_action_values)
    val currentEntry = actionValues.indexOf(currentAction).let { if (it != -1) actionEntries[it] else currentAction }
    val icon = when (currentAction) {
        "toggle_ringer" -> Icons.Rounded.NotificationsActive
        "toggle_dnd" -> Icons.Rounded.NightsStay
        "toggle_flashlight" -> Icons.Rounded.FlashlightOn
        "screenshot" -> Icons.Rounded.Screenshot
        "launch_app" -> Icons.Rounded.Apps
        "wechat_pay" -> Icons.Rounded.Payments
        "alipay_pay" -> Icons.Rounded.Payments
        "alipay_scan" -> Icons.Rounded.QrCodeScanner
        "wechat_scan" -> Icons.Rounded.QrCodeScanner
        else -> Icons.Rounded.RadioButtonUnchecked
    }

    Box {
        Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled) { showMenu = true }, color = Color.Transparent) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(effectiveIconColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = effectiveIconColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (enabled) Color.Unspecified else disabledColor)
                    val summaryText = if (currentAction == "launch_app" && currentPackage.isNotEmpty()) "$currentEntry ($currentPackage)" else currentEntry
                    Text(summaryText, style = MaterialTheme.typography.bodyMedium, color = if (enabled) Color.Unspecified else disabledColor)
                }
                Spacer(Modifier.width(16.dp))
                Icon(Icons.Rounded.UnfoldMore, null, tint = if (enabled) MaterialTheme.colorScheme.outlineVariant else disabledColor, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded = showMenu && enabled, onDismissRequest = { showMenu = false }) {
            actionValues.forEachIndexed { index, value ->
                DropdownMenuItem(
                    text = { Text(actionEntries[index]) },
                    onClick = { showMenu = false; if (value == "launch_app") showAppPicker = true else onActionSelected(value, null) },
                    trailingIcon = if (currentAction == value) { { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(16.dp)) } } else null
                )
            }
        }
    }
    if (showAppPicker && enabled) {
        AppPickerDialog(currentPackage = currentPackage, onDismiss = { showAppPicker = false }, onAppSelected = { pkg -> onActionSelected("launch_app", pkg); showAppPicker = false })
    }
}

@Composable
fun MultiAppSelectorPreference(title: String, iconColor: Color, selectedPackages: Set<String>, enabled: Boolean = true, onAppsSelected: (Set<String>) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveIconColor = if (enabled) iconColor else disabledColor

    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled) { showDialog = true }, color = Color.Transparent) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(effectiveIconColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Apps, null, tint = effectiveIconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (enabled) MaterialTheme.colorScheme.onSurface else disabledColor)
                val summaryText = if (selectedPackages.isEmpty()) stringResource(R.string.shortcut_package_none) else stringResource(R.string.selected_count, selectedPackages.size)
                Text(text = summaryText, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else disabledColor)
            }
            Spacer(Modifier.width(16.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = if (enabled) MaterialTheme.colorScheme.outlineVariant else disabledColor, modifier = Modifier.size(20.dp))
        }
    }
    if (showDialog && enabled) {
        MultiAppPickerDialog(initialSelected = selectedPackages, onDismiss = { showDialog = false }, onAppsSelected = { onAppsSelected(it); showDialog = false })
    }
}

@Composable
fun MultiAppPickerDialog(initialSelected: Set<String>, onDismiss: () -> Unit, onAppsSelected: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    var apps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(initialSelected) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .sortedWith(compareByDescending<ApplicationInfo> { initialSelected.contains(it.packageName) }
                    .thenBy { packageManager.getApplicationLabel(it).toString() })
            withContext(Dispatchers.Main) { apps = installedApps; isLoading = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.touch_game_apps_title)) },
        confirmButton = { TextButton(onClick = { onAppsSelected(selected) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(32.dp),
        text = {
            Column {
                TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text(stringResource(android.R.string.search_go)) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true)
                if (isLoading) Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else {
                    val filteredApps = apps.filter { packageManager.getApplicationLabel(it).toString().contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(filteredApps) { app ->
                            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { selected = if (selected.contains(app.packageName)) selected - app.packageName else selected + app.packageName }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(packageManager.getApplicationIcon(app)).crossfade(true).build(), contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) { Text(packageManager.getApplicationLabel(app).toString(), style = MaterialTheme.typography.bodyLarge); Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                Checkbox(checked = selected.contains(app.packageName), onCheckedChange = { isChecked -> selected = if (isChecked) selected + app.packageName else selected - app.packageName })
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun AppPickerDialog(currentPackage: String? = null, onDismiss: () -> Unit, onAppSelected: (String) -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    var apps by remember { mutableStateOf<List<ApplicationInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .sortedWith(compareByDescending<ApplicationInfo> { it.packageName == currentPackage }
                    .thenBy { packageManager.getApplicationLabel(it).toString() })
            withContext(Dispatchers.Main) { apps = installedApps; isLoading = false }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shortcut_click_package_title)) },
        confirmButton = { TextButton(onClick = { onDismiss() }) { Text(stringResource(android.R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(32.dp),
        text = {
            if (isLoading) Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else {
                Column {
                    TextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text(stringResource(android.R.string.search_go)) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true)
                    val filtered = apps.filter { packageManager.getApplicationLabel(it).toString().contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        items(filtered) { app ->
                            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp)).clickable { onAppSelected(app.packageName) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = ImageRequest.Builder(LocalContext.current).data(packageManager.getApplicationIcon(app)).crossfade(true).build(), contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) { Text(packageManager.getApplicationLabel(app).toString(), style = MaterialTheme.typography.bodyLarge); Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPreference(title: String, icon: ImageVector, iconColor: Color, currentColor: Color, isDynamicColorEnabled: Boolean, enabled: Boolean = true, onDynamicColorToggle: (Boolean) -> Unit, onColorSelected: (Color) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveIconColor = if (enabled) iconColor else disabledColor

    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled) { showDialog = true }, color = Color.Transparent) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(effectiveIconColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = effectiveIconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (enabled) Color.Unspecified else disabledColor)
                if (isDynamicColorEnabled) Text(stringResource(R.string.dynamic_color_title), style = MaterialTheme.typography.bodyMedium, color = if (enabled) Color.Unspecified else disabledColor)
            }
            Spacer(Modifier.width(16.dp))
            Box(modifier = Modifier.size(24.dp).clip(CircleShape).background((if (isDynamicColorEnabled) MaterialTheme.colorScheme.primary else currentColor).copy(alpha = if (enabled) 1f else 0.38f)).border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (enabled) 1f else 0.38f), CircleShape))
        }
    }
    if (showDialog && enabled) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(android.R.string.ok)) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(32.dp),
            text = {
                Column(modifier = Modifier.animateContentSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FlowRow(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).alpha(if (isDynamicColorEnabled) 0.5f else 1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        COLOR_PALETTE.forEach { color ->
                            Box(modifier = Modifier.size(44.dp).clip(CircleShape).background(color).clickable(!isDynamicColorEnabled) { onColorSelected(color) }.border(if (currentColor == color) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(if (currentColor == color) 3.dp else 0.dp).border(if (currentColor == color) 2.dp else 0.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) { if (currentColor == color) Icon(Icons.Rounded.Check, null, tint = Color.White) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onDynamicColorToggle(!isDynamicColorEnabled) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.dynamic_color_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.dynamic_color_summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = isDynamicColorEnabled,
                            onCheckedChange = onDynamicColorToggle,
                            enabled = enabled,
                            thumbContent = {
                                Icon(
                                    imageVector = if (isDynamicColorEnabled) Icons.Rounded.Check else Icons.Rounded.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedIconColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else disabledColor.copy(alpha = 0.4f),
                                uncheckedTrackColor = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else disabledColor.copy(alpha = 0.1f),
                                uncheckedBorderColor = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else disabledColor.copy(alpha = 0.2f),
                                uncheckedIconColor = Color.White,
                                disabledCheckedThumbColor = Color.White.copy(alpha = 0.38f),
                                disabledCheckedTrackColor = disabledColor.copy(alpha = 0.12f),
                                disabledUncheckedThumbColor = disabledColor.copy(alpha = 0.12f),
                                disabledUncheckedTrackColor = disabledColor.copy(alpha = 0.05f)
                            )
                        )
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupedColorPreference(title: String, icon: ImageVector, iconColor: Color, items: List<ColorItem>, enabled: Boolean = true, onColorSelected: (Int, Color) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveIconColor = if (enabled) iconColor else disabledColor

    Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled) { showDialog = true }, color = Color.Transparent) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(effectiveIconColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = effectiveIconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (enabled) Color.Unspecified else disabledColor) }
            Spacer(Modifier.width(16.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = if (enabled) MaterialTheme.colorScheme.outlineVariant else disabledColor, modifier = Modifier.size(20.dp))
        }
    }
    if (showDialog && enabled) {
        var selectedItemIndex by remember { mutableIntStateOf(0) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(android.R.string.ok)) } },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(32.dp),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                    SecondaryScrollableTabRow(
                        selectedTabIndex = selectedItemIndex,
                        containerColor = Color.Transparent,
                        edgePadding = 0.dp,
                        divider = {},
                        indicator = {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(selectedItemIndex),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    ) {
                        items.forEachIndexed { index, item ->
                            Tab(
                                selected = selectedItemIndex == index,
                                onClick = { selectedItemIndex = index },
                                text = {
                                    Text(
                                        item.label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (selectedItemIndex == index) {
                                            MaterialTheme.colorScheme.primary   // 选中颜色
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant // 未选中颜色
                                        }
                                    )
                                }
                            )
                        }
                    }

                    Column(modifier = Modifier.padding(top=8.dp)) {

                        val selectedItem = items[selectedItemIndex]

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            tonalElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = 16.dp,
                                    vertical = 12.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(selectedItem.color.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Rounded.Brush,
                                        contentDescription = null,
                                        tint = selectedItem.color,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Spacer(Modifier.width(16.dp))

                                Text(
                                    selectedItem.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )

                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(selectedItem.color)
                                )
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            COLOR_PALETTE.forEach { color ->

                                val isSelected = selectedItem.color == color

                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(color)
                                        .clickable {
                                            onColorSelected(selectedItemIndex, color)
                                        }
                                        .then(
                                            if (isSelected) {
                                                Modifier
                                                    .border(
                                                        3.dp,
                                                        MaterialTheme.colorScheme.primary,
                                                        CircleShape
                                                    )
                                                    .padding(3.dp)
                                                    .border(
                                                        2.dp,
                                                        Color.White,
                                                        CircleShape
                                                    )
                                            } else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@Composable
fun SettingsSnackbarHost(hostState: SnackbarHostState, hazeState: HazeState? = null) {
    val snackbarShape = RoundedCornerShape(28.dp)
    SnackbarHost(hostState) { data ->
        Box(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(snackbarShape)
                .hazeEffect(
                    state = hazeState ?: remember { HazeState() },
                    style = HazeDefaults.style(
                        backgroundColor = MaterialTheme.colorScheme.surfaceContainer,
                        tint = HazeDefaults.tint(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f)),
                        blurRadius = 24.dp
                    )
                )
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = snackbarShape,
                color = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            ) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(imageVector = Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(16.dp))
                    Text(text = data.visuals.message, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    if (data.visuals.actionLabel != null) {
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = { data.performAction() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(text = data.visuals.actionLabel!!, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@Composable
fun RootWarningBanner(modifier: Modifier = Modifier, isKSUInstalled: Boolean, onInstall: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Surface(modifier = modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(imageVector = Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text(text = stringResource(R.string.root_not_found_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(text = stringResource(R.string.root_not_found_summary), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f))
                Spacer(Modifier.height(16.dp))
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Icon(imageVector = if (isKSUInstalled) Icons.AutoMirrored.Rounded.Launch else Icons.Rounded.Download, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(text = stringResource(if (isKSUInstalled) R.string.launch_kernelsu else R.string.install_kernelsu), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun FpFixBanner(modifier: Modifier = Modifier, accentColor: Color, onDismiss: () -> Unit, onGoToSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp)) {
        Surface(modifier = modifier, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))) {
            Column {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(accentColor.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Info, null, tint = accentColor, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) { Text(text = stringResource(R.string.fp_fix_dialog_msg), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface) }
                }
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dont_show_again), color = accentColor) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onGoToSettings, shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = accentColor)) { Text(text = stringResource(R.string.action_go_to_settings), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

data class ColorItem(val label: String, val color: Color)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditTextPreference(
    title: String,
    summary: String? = null,
    icon: ImageVector,
    iconColor: Color,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    suggestions: List<Pair<String, String>> = emptyList()
) {
    var showDialog by remember { mutableStateOf(false) }
    var tempValue by remember(value) { mutableStateOf(value) }
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val effectiveIconColor = if (enabled) iconColor else disabledColor

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = enabled) { showDialog = true },
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(effectiveIconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = effectiveIconColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) Color.Unspecified else disabledColor
                )
                Text(
                    summary ?: value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) Color.Unspecified else disabledColor
                )
            }
            Spacer(Modifier.width(16.dp))
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.outlineVariant else disabledColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }

    if (showDialog && enabled) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = tempValue,
                        onValueChange = { tempValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    
                    if (suggestions.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.forEach { (label, suggestion) ->
                                SuggestionChip(
                                    onClick = { tempValue = suggestion },
                                    label = { Text(label) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onValueChange(tempValue)
                    showDialog = false
                }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(32.dp)
        )
    }
}

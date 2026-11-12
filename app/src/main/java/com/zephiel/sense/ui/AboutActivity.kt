package com.zephiel.sense.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zephiel.sense.BuildConfig
import com.zephiel.sense.R
import com.zephiel.sense.ui.theme.SenseTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedPreferences = createDeviceProtectedStorageContext()
            .getSharedPreferences(packageName + "_preferences", MODE_PRIVATE)

        val dynamicColor = sharedPreferences.getBoolean("pref_dynamic_color", true)
        val colorInt = sharedPreferences.getInt("theme_accent_color", -1)
        val accentColor = if (colorInt == -1) null else Color(colorInt)

        setContent {
            SenseTheme(
                dynamicColor = dynamicColor,
                seedColor = accentColor
            ) {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Upstream github.com/Zephiel/Sense was deleted; this fork lives on.
    val githubUrl = "https://github.com/KevinXu07/Sense"
    val kernelsuUrl = "https://kernelsu.org"
    val developerUrl = "https://www.coolapk.com/u/880964"
    val maintainerUrl = "https://github.com/KevinXu07"
    val contributors = listOf(
        "TUSB_5834" to "https://www.coolapk.com/u/26870780",
        "已跑路_勿扰" to "https://www.coolapk.com/u/35139340",
        "八月刷机" to "https://www.coolapk.com/u/39665236",
        "扬帆起航航航航航" to "https://www.coolapk.com/u/2129267"
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val hazeState = remember { HazeState() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                title = {
                    val collapsedFraction = scrollBehavior.state.collapsedFraction
                    val expandedStyle = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1.5).sp,
                        lineHeight = 48.sp
                    )
                    val collapsedStyle = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.sp
                    )

                    Text(
                        text = stringResource(R.string.about_title),
                        style = lerp(expandedStyle, collapsedStyle, collapsedFraction)
                    )
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
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 48.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // App Identity Area
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.size(96.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            tonalElevation = 2.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.round_gesture_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(72.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1).sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        val versionName = try {
                            context.packageManager.getPackageInfo(context.packageName, 0).versionName
                        } catch (e: Exception) { "1.0.0" }

                        Text(
                            text = stringResource(R.string.about_version_format, versionName ?: ""),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                // Description
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 28.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.about_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )
                    }
                }

                // Links Section
                item {
                    SettingsSection(
                        title = stringResource(R.string.about_links_title),
                        titleColor = MaterialTheme.colorScheme.primary
                    ) {
                        PreferenceItem(
                            title = stringResource(R.string.about_github_title),
                            summary = stringResource(R.string.about_github_summary),
                            icon = Icons.Rounded.Code,
                            iconColor = MaterialTheme.colorScheme.primary,
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                            }
                        )
                        InsetDivider()
                        PreferenceItem(
                            title = stringResource(R.string.about_kernelsu_title),
                            summary = stringResource(R.string.about_kernelsu_summary),
                            icon = Icons.Rounded.Security,
                            iconColor = Color(0xFF1B5E20),
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(kernelsuUrl)))
                            }
                        )
                    }
                }

                // Development Info Section
                item {
                    SettingsSection(
                        title = stringResource(R.string.other_settings_title),
                        titleColor = MaterialTheme.colorScheme.primary
                    ) {
                        AboutInfoPreference(
                            stringResource(R.string.about_developer_title),
                            stringResource(R.string.about_developer_value),
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(developerUrl)))
                            }
                        )
                        InsetDivider()
                        AboutInfoPreference(
                            stringResource(R.string.about_maintainer_title),
                            stringResource(R.string.about_maintainer_value),
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(maintainerUrl)))
                            }
                        )
                        InsetDivider()
                        contributors.forEachIndexed { index, (name, url) ->
                            AboutInfoPreference(
                                if (index == 0) stringResource(R.string.about_contributors_title) else "",
                                name,
                                onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            )
                            InsetDivider()
                        }
                        AboutInfoPreference(
                            stringResource(R.string.about_build_time),
                            BuildConfig.BUILD_TIME
                        )
                        InsetDivider()
                        AboutInfoPreference(
                            stringResource(R.string.about_license_title),
                            stringResource(R.string.about_license_value)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AboutInfoPreference(title: String, value: String, onClick: (() -> Unit)? = null) {
    Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

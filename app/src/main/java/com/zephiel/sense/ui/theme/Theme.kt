package com.zephiel.sense.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun SenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    seedColor: Color? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        seedColor != null -> {
            if (darkTheme) {
                val surfaceBase = Color(0xFF111418)
                darkColorScheme(
                    primary = seedColor,
                    onPrimary = Color.Black,
                    primaryContainer = seedColor.copy(alpha = 0.3f),
                    onPrimaryContainer = Color.White,
                    secondary = seedColor.copy(alpha = 0.8f),
                    secondaryContainer = seedColor.copy(alpha = 0.2f),
                    surface = seedColor.copy(alpha = 0.05f).compositeOver(surfaceBase),
                    surfaceContainerLow = seedColor.copy(alpha = 0.08f).compositeOver(surfaceBase),
                    surfaceContainer = seedColor.copy(alpha = 0.12f).compositeOver(surfaceBase),
                    surfaceContainerHigh = seedColor.copy(alpha = 0.16f).compositeOver(surfaceBase)
                )
            } else {
                val surfaceBase = Color(0xFFF8F9FF)
                lightColorScheme(
                    primary = seedColor,
                    onPrimary = Color.White,
                    primaryContainer = seedColor.copy(alpha = 0.15f),
                    onPrimaryContainer = seedColor,
                    secondary = seedColor.copy(alpha = 0.7f),
                    secondaryContainer = seedColor.copy(alpha = 0.1f),
                    surface = seedColor.copy(alpha = 0.02f).compositeOver(surfaceBase),
                    surfaceContainerLow = seedColor.copy(alpha = 0.04f).compositeOver(surfaceBase),
                    surfaceContainer = seedColor.copy(alpha = 0.06f).compositeOver(surfaceBase),
                    surfaceContainerHigh = seedColor.copy(alpha = 0.08f).compositeOver(surfaceBase)
                )
            }
        }
        darkTheme -> darkColorScheme(
            primary = Color(0xFF9ECAFF),
            onPrimary = Color(0xFF003258),
            primaryContainer = Color(0xFF00497D),
            onPrimaryContainer = Color(0xFFD1E4FF)
        )
        else -> lightColorScheme(
            primary = Color(0xFF0061A4),
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD1E4FF),
            onPrimaryContainer = Color(0xFF001D36)
        )
    }
    
    val typography = Typography()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context.findActivity())?.window
            window?.let {
                WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}

private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

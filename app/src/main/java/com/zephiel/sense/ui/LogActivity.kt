package com.zephiel.sense.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zephiel.sense.R
import com.zephiel.sense.ui.theme.SenseTheme
import dev.chrisbanes.haze.HazeDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedPreferences = createDeviceProtectedStorageContext()
            .getSharedPreferences(packageName + "_preferences", Context.MODE_PRIVATE)
        val dynamicColor = sharedPreferences.getBoolean("pref_dynamic_color", true)
        val accentColorInt = sharedPreferences.getInt("theme_accent_color", -1)
        val seedColor = if (accentColorInt == -1) null else Color(accentColorInt)

        setContent {
            SenseTheme(
                dynamicColor = dynamicColor,
                seedColor = seedColor
            ) {
                LogViewerScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val logLines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val hazeState = remember { HazeState() }
    
    // Zoom state
    var zoomScale by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                // 清理旧日志缓冲区
                Runtime.getRuntime().exec("logcat -c").waitFor()
                
                // 开启 logcat 进程
                val process = Runtime.getRuntime().exec("logcat Sense:V *:S")
                
                // 关键修复：当协程被取消时（例如 Activity 销毁），强制销毁进程以解除 readLine 的阻塞
                val destroyerJob = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        process.destroy()
                    }
                }

                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val batch = mutableListOf<String>()
                    var lastUpdateTime = System.currentTimeMillis()

                    while (isActive) {
                        val line = try {
                            reader.readLine()
                        } catch (e: Exception) {
                            null
                        } ?: break
                        
                        batch.add(line)

                        val now = System.currentTimeMillis()
                        // 批量更新 UI 以提高性能并减少主线程压力
                        if (now - lastUpdateTime > 100 || batch.size >= 50) {
                            val currentBatch = batch.toList()
                            batch.clear()
                            withContext(Dispatchers.Main) {
                                logLines.addAll(currentBatch)
                                if (logLines.size > 1000) {
                                    repeat(logLines.size - 1000) {
                                        logLines.removeAt(0)
                                    }
                                }
                            }
                            lastUpdateTime = now
                        }
                    }
                } finally {
                    destroyerJob.cancel()
                    process.destroy()
                }
            } catch (e: Exception) {
                if (e !is CancellationException && e !is java.io.IOException) {
                    withContext(Dispatchers.Main) {
                        logLines.add("Error reading logs: ${e.message}")
                    }
                }
            }
        }
    }

    // 自动滚动到底部
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty() && !listState.isScrollInProgress) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeDefaults.style(
                        backgroundColor = Color.Transparent,
                        tint = HazeDefaults.tint(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f))
                    )
                ),
                title = { 
                    Text(
                        stringResource(R.string.log_viewer_title),
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val fullLog = logLines.joinToString("\n")
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Sense Logs", fullLog))
                    }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.action_copy_logs))
                    }
                    IconButton(onClick = { logLines.clear() }) {
                        Icon(Icons.Rounded.ClearAll, contentDescription = stringResource(R.string.action_clear_logs))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                    navigationIconContentColor = MaterialTheme.colorScheme.primary,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .hazeSource(state = hazeState)
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    zoomScale = (zoomScale * zoom).coerceIn(0.5f, 3f)
                }
            }
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                    contentPadding = innerPadding
                ) {
                    items(logLines) { line ->
                        LogLineItem(line, zoomScale)
                    }
                }
            }
        }
    }
}

@Composable
fun LogLineItem(line: String, zoomScale: Float) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = when {
        line.contains(" E/") -> Color(0xFFFF5252)
        line.contains(" W/") -> Color(0xFFFFD740)
        line.contains(" I/") -> Color(0xFF40C4FF)
        line.contains(" D/") -> Color(0xFFBDBDBD)
        else -> if (isDark) Color.White else Color.Black
    }

    Text(
        text = line,
        color = color,
        fontSize = (11 * zoomScale).sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = (14 * zoomScale).sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
    )
}

package com.kaguya.comicsviewer.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kaguya.comicsviewer.domain.model.ReadingMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    @Suppress("UNUSED_PARAMETER") nav: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除所有缓存") },
            text = { Text("将删除所有已加载的漫画缓存文件，需要重新加载才能阅读。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllCache()
                    showClearDialog = false
                }) { Text("确定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("设置") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(title = "阅读", icon = Icons.Outlined.Style) {
                Text("默认阅读模式", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ReadingMode.PAGED to "左右翻页",
                        ReadingMode.CONTINUOUS to "上下滚动",
                        ReadingMode.WEBTOON to "条带"
                    ).forEach { (mode, label) ->
                        AssistChip(
                            onClick = { viewModel.setMode(mode) },
                            label = { Text(label) },
                            colors = if (state.settings.readingMode == mode)
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ) else AssistChipDefaults.assistChipColors()
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = "保持屏幕常亮",
                    subtitle = "阅读时禁止自动息屏",
                    checked = state.settings.keepScreenOn,
                    onChange = viewModel::setKeepScreenOn
                )
                ToggleRow(
                    title = "读完自动标记",
                    subtitle = "阅读到最后一页时自动标记已读完",
                    checked = state.settings.autoMarkRead,
                    onChange = viewModel::setAutoMarkRead
                )
            }

            SectionCard(title = "存储", icon = Icons.Outlined.CleaningServices) {
                Text("缓存：${state.cacheSize}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showClearDialog = true },
                    enabled = !state.isClearing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    if (state.isClearing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isClearing) "清理中..." else "清除所有缓存")
                }
            }

            SectionCard(title = "主题", icon = Icons.Outlined.Brightness6) {
                ToggleRow(
                    title = "跟随系统主题",
                    subtitle = "自动根据系统设置切换深浅色模式",
                    checked = state.settings.followSystemTheme,
                    onChange = viewModel::setFollowSystemTheme
                )
                ToggleRow(
                    title = "深色模式",
                    subtitle = "手动启用深色模式",
                    checked = state.settings.darkMode,
                    enabled = !state.settings.followSystemTheme,
                    onChange = viewModel::setDarkMode
                )
                ToggleRow(
                    title = "动态主题色",
                    subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        "使用系统壁纸颜色作为主题色（Android 12+）"
                    else
                        "需要 Android 12 及以上版本",
                    checked = state.settings.dynamicColor,
                    onChange = viewModel::setDynamicColor
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

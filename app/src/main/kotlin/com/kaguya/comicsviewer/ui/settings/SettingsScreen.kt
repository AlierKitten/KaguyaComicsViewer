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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kaguya.comicsviewer.R
import com.kaguya.comicsviewer.domain.model.ReadingMode
import com.kaguya.comicsviewer.util.LocaleHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    @Suppress("UNUSED_PARAMETER") nav: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }
    var showClearCoversDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.clear_all_cache)) },
            text = { Text(stringResource(R.string.clear_cache_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllCache()
                    showClearDialog = false
                }) { Text(stringResource(R.string.ok), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showClearCoversDialog) {
        AlertDialog(
            onDismissRequest = { showClearCoversDialog = false },
            title = { Text(stringResource(R.string.clear_all_covers)) },
            text = { Text(stringResource(R.string.clear_covers_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllCovers()
                    showClearCoversDialog = false
                }) { Text(stringResource(R.string.ok), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearCoversDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionCard(title = stringResource(R.string.section_reading), icon = Icons.Outlined.Style) {
                Text(stringResource(R.string.default_reading_mode), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ReadingMode.PAGED to stringResource(R.string.mode_paged),
                        ReadingMode.CONTINUOUS to stringResource(R.string.mode_continuous),
                        ReadingMode.WEBTOON to stringResource(R.string.mode_webtoon)
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
                    title = stringResource(R.string.keep_screen_on),
                    subtitle = stringResource(R.string.keep_screen_on_summary),
                    checked = state.settings.keepScreenOn,
                    onChange = viewModel::setKeepScreenOn
                )
            }

            SectionCard(title = stringResource(R.string.covers), icon = Icons.Outlined.Image) {
                ToggleRow(
                    title = stringResource(R.string.show_covers),
                    subtitle = stringResource(R.string.show_covers_summary),
                    checked = state.settings.showCovers,
                    onChange = viewModel::setShowCovers
                )
                Spacer(Modifier.height(12.dp))
                ToggleRow(
                    title = stringResource(R.string.index_covers),
                    subtitle = stringResource(R.string.index_covers_summary),
                    checked = state.settings.indexCoverOnScan,
                    onChange = viewModel::setIndexCoverOnScan
                )
            }

            SectionCard(
                title = stringResource(R.string.section_storage),
                icon = Icons.Outlined.CleaningServices,
                action = {
                    IconButton(
                        onClick = viewModel::refreshStorage,
                        enabled = !state.isRefreshing
                    ) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.cache_size, state.cacheSize), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
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
                    Text(if (state.isClearing) stringResource(R.string.cleaning) else stringResource(R.string.clear_all_cache))
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.covers_label) + state.coverSize, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showClearCoversDialog = true },
                    enabled = !state.isClearingCovers,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    if (state.isClearingCovers) {
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
                    Text(if (state.isClearingCovers) stringResource(R.string.cleaning) else stringResource(R.string.clear_all_covers))
                }
            }

            SectionCard(title = stringResource(R.string.section_theme), icon = Icons.Outlined.Brightness6) {
                ToggleRow(
                    title = stringResource(R.string.follow_system_theme),
                    subtitle = stringResource(R.string.follow_system_theme_summary),
                    checked = state.settings.followSystemTheme,
                    onChange = viewModel::setFollowSystemTheme
                )
                ToggleRow(
                    title = stringResource(R.string.dark_mode),
                    subtitle = stringResource(R.string.dark_mode_summary),
                    checked = state.settings.darkMode,
                    enabled = !state.settings.followSystemTheme,
                    onChange = viewModel::setDarkMode
                )
                ToggleRow(
                    title = stringResource(R.string.dynamic_theme_color),
                    subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        stringResource(R.string.dynamic_theme_color_summary)
                    else
                        stringResource(R.string.dynamic_theme_color_summary),
                    checked = state.settings.dynamicColor,
                    onChange = viewModel::setDynamicColor
                )
            }

            SectionCard(title = stringResource(R.string.section_privacy), icon = Icons.Outlined.VisibilityOff) {
                ToggleRow(
                    title = stringResource(R.string.hide_task_preview),
                    subtitle = stringResource(R.string.hide_task_preview_summary),
                    checked = state.settings.hideFromRecents,
                    onChange = viewModel::setHideFromRecents
                )
            }

            @Suppress("ComposeLocalActivityCast")
            val activity = LocalContext.current as? ComponentActivity
            LanguageCard(
                current = state.settings.language,
                onSelect = { code ->
                    viewModel.setLanguage(code)
                    activity?.recreate()
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageCard(current: String, onSelect: (String) -> Unit) {
    val options = listOf(
        LocaleHelper.LANG_ZH_CN to stringResource(R.string.language_zh_cn),
        LocaleHelper.LANG_EN_US to stringResource(R.string.language_en_us),
        LocaleHelper.LANG_JA_JP to stringResource(R.string.language_ja_jp)
    )
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title = stringResource(R.string.section_language), icon = Icons.Outlined.Language) {
        Text(stringResource(R.string.language_summary), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            val selectedLabel = options.firstOrNull { it.first == current }?.second ?: current
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (code, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            if (code != current) onSelect(code)
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                action?.invoke()
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

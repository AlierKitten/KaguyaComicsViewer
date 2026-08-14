package com.kaguya.comicsviewer.ui.sources

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kaguya.comicsviewer.data.source.smb.SmbClient
import com.kaguya.comicsviewer.domain.model.ComicSource
import com.kaguya.comicsviewer.domain.model.ComicSourceType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    @Suppress("UNUSED_PARAMETER") nav: NavController,
    viewModel: SourcesViewModel = hiltViewModel()
) {
    val smbClient = viewModel.smbClient
    val context = LocalContext.current
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val scanningIds by viewModel.scanningIds.collectAsStateWithLifecycle()
    val isIndexing by viewModel.isIndexing.collectAsStateWithLifecycle()
    val stopping by viewModel.stopping.collectAsStateWithLifecycle()
    var showSmb by remember { mutableStateOf(false) }
    var pendingLocalName by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<ComicSource?>(null) }

    // Toast 事件监听
    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    val localPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null && pendingLocalName != null) {
            // 持久化 SAF 权限
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            viewModel.addLocalSource(pendingLocalName!!, uri.toString())
            pendingLocalName = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件源") },
                actions = {
                    if (isIndexing) {
                        IconButton(
                            onClick = { viewModel.cancelIndexing() },
                            enabled = !stopping
                        ) {
                            if (stopping) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Outlined.Stop, contentDescription = "停止索引")
                            }
                        }
                    } else {
                        IconButton(onClick = { viewModel.scanAllEnabled() }) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "全部刷新")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExtendedFloatingActionButton(
                    onClick = {
                        pendingLocalName = "本地 ${sources.size + 1}"
                        localPicker.launch(null)
                    },
                    text = { Text("本地") },
                    icon = { Icon(Icons.Outlined.FolderOpen, null) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
                ExtendedFloatingActionButton(
                    onClick = { showSmb = true },
                    text = { Text("SMB") },
                    icon = { Icon(Icons.Outlined.Cloud, null) }
                )
            }
        }
    ) { padding ->
        if (sources.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Storage, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("没有文件源", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("点击右下角 + 添加本地或 SMB 源", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(padding)
            ) {
                items(sources, key = { it.source.id }) { row ->
                    SourceItem(
                        row = row,
                        isScanning = row.source.id in scanningIds || row.source.indexStatus == com.kaguya.comicsviewer.domain.model.IndexStatus.SCANNING,
                        onToggle = { viewModel.toggleEnabled(row.source, it) },
                        onScan = { viewModel.scan(row.source) },
                        onRename = { renameTarget = row.source },
                        onDelete = { viewModel.delete(row.source) }
                    )
                }
            }
        }
    }

    if (showSmb) {
        SmbDialog(
            smbClient = smbClient,
            onDismiss = { showSmb = false },
            onConfirm = { name, host, share, path, user, pass, domain ->
                viewModel.addSmbSource(name, host, share, path, user, pass, domain)
                showSmb = false
            }
        )
    }

    renameTarget?.let { source ->
        RenameSourceDialog(
            currentName = source.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameSource(source, newName)
                renameTarget = null
            }
        )
    }
}

@Composable
private fun SourceItem(
    row: SourceRow,
    isScanning: Boolean,
    onToggle: (Boolean) -> Unit,
    onScan: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (row.source.type == ComicSourceType.LOCAL) Icons.Outlined.FolderOpen else Icons.Outlined.Cloud,
                null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.source.name, style = MaterialTheme.typography.titleMedium)
                val sub = when (row.source.type) {
                    ComicSourceType.LOCAL -> "本地 · ${row.lastScanned}"
                    ComicSourceType.SMB -> {
                        val host = row.source.host.orEmpty()
                        val share = row.source.share.orEmpty()
                        "SMB $host/$share · ${row.lastScanned}"
                    }
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${row.comicCount} 个漫画",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                // 落库索引状态徽标：直观区分「真完成」与「未完成/失败」（进程被杀后也能恢复显示）
                val statusLabel = when (row.source.indexStatus) {
                    com.kaguya.comicsviewer.domain.model.IndexStatus.DONE ->
                        "已索引 ${row.source.indexCurrent}/${row.source.indexTotal}" to MaterialTheme.colorScheme.primary
                    com.kaguya.comicsviewer.domain.model.IndexStatus.FAILED ->
                        "索引失败" to MaterialTheme.colorScheme.error
                    com.kaguya.comicsviewer.domain.model.IndexStatus.CANCELLED ->
                        "已取消" to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> null
                }
                if (statusLabel != null) {
                    Text(
                        statusLabel.first,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusLabel.second
                    )
                }
            }
            Switch(checked = row.source.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onScan, enabled = !isScanning) {
                if (isScanning) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(Icons.Outlined.Refresh, null)
            }
            IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, null) }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RenameSourceDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名文件源") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotBlank() && name.trim() != currentName,
                onClick = { onConfirm(name) }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun SmbDialog(
    smbClient: SmbClient,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String?, String?, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("445") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    // 连接后状态
    var connected by remember { mutableStateOf(false) }
    var shares by remember { mutableStateOf<List<String>>(emptyList()) }
    var share by remember { mutableStateOf("") }
    // 当前浏览路径（在共享内的路径段列表）
    var currentPath by remember { mutableStateOf(listOf<String>()) }
    var dirEntries by remember { mutableStateOf<List<SmbClient.DirEntry>>(emptyList()) }

    var statusMsg by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val portNum = port.toIntOrNull() ?: 445

    // 辅助：加载共享内目录
    fun loadDir(shareName: String, pathSegments: List<String>) {
        scope.launch {
            isLoading = true
            statusMsg = null
            val pathStr = pathSegments.joinToString("/")
            val entries = smbClient.listDirEntries(host, portNum, user, pass, shareName, pathStr)
            dirEntries = entries.getOrDefault(emptyList())
            isLoading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 SMB 源") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // === 连接信息 ===
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it; connected = false },
                    label = { Text("主机 *") }, placeholder = { Text("192.168.1.10") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5); connected = false },
                    label = { Text("端口") }, placeholder = { Text("445") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it; connected = false },
                    label = { Text("用户名 *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it; connected = false },
                    label = { Text("密码") }, placeholder = { Text("留空表示无密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // === 测试连接按钮 ===
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            statusMsg = null
                            connected = false
                            shares = emptyList()
                            share = ""
                            currentPath = emptyList()
                            dirEntries = emptyList()

                            val result = smbClient.testConnection(host, portNum, user, pass)
                            if (result.isSuccess) {
                                statusMsg = "连接成功"
                                val sharesResult = smbClient.listShares(host, portNum, user, pass)
                                shares = sharesResult.getOrDefault(emptyList())
                                connected = true
                                if (shares.isEmpty()) {
                                    statusMsg = "连接成功，但未发现共享。请手动输入共享名。"
                                }
                            } else {
                                statusMsg = "连接失败：${result.exceptionOrNull()?.message}"
                            }
                            isLoading = false
                        }
                    },
                    enabled = host.isNotBlank() && user.isNotBlank() && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isLoading && !connected) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isLoading && !connected) "连接中..." else "连接并获取路径")
                }

                statusMsg?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.contains("失败") || msg.contains("未发现")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                    )
                }

                // === 连接成功后的文件浏览器 ===
                if (connected) {
                    HorizontalDivider()

                    // 共享名选择
                    if (shares.isNotEmpty()) {
                        Text("选择共享", style = MaterialTheme.typography.labelMedium)
                        LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                            items(shares) { shareName ->
                                val selected = share == shareName
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            share = shareName
                                            currentPath = emptyList()
                                            loadDir(shareName, emptyList())
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.FolderOpen, null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(shareName, modifier = Modifier.weight(1f),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }

                    // 手动输入共享名
                    if (shares.isEmpty()) {
                        OutlinedTextField(
                            value = share, onValueChange = { share = it },
                            label = { Text("共享名 *") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                currentPath = emptyList()
                                loadDir(share, emptyList())
                            },
                            enabled = share.isNotBlank() && !isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("浏览") }
                    }

                    // 目录浏览器（当选中共享后显示）
                    if (share.isNotBlank() && (dirEntries.isNotEmpty() || currentPath.isNotEmpty() || isLoading)) {
                        HorizontalDivider()

                        // 面包屑
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(
                                onClick = {
                                    currentPath = emptyList()
                                    loadDir(share, emptyList())
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) { Text(share, style = MaterialTheme.typography.labelSmall) }

                            currentPath.forEachIndexed { index, seg ->
                                Text("/", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(
                                    onClick = {
                                        currentPath = currentPath.take(index + 1)
                                        loadDir(share, currentPath)
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp)
                                ) { Text(seg, style = MaterialTheme.typography.labelSmall) }
                            }
                        }

                        // 当前路径已选中
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { /* 当前路径即选中 */ }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Check, null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "使用当前目录",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 子目录列表
                        if (isLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("加载中...", style = MaterialTheme.typography.bodySmall)
                            }
                        } else if (dirEntries.isNotEmpty()) {
                            LazyColumn(modifier = Modifier.heightIn(max = 150.dp)) {
                                items(dirEntries) { entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (entry.isDirectory) {
                                                    currentPath = currentPath + entry.name
                                                    loadDir(share, currentPath)
                                                }
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (entry.isDirectory) Icons.Outlined.FolderOpen else Icons.Outlined.Storage,
                                            null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(entry.name, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        } else if (!isLoading) {
                            Text(
                                "（此目录为空）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = connected && share.isNotBlank(),
                onClick = {
                    val fullHost = if (port.isNotBlank() && port != "445") "$host:$port" else host
                    val pathStr = currentPath.joinToString("/").ifBlank { null }
                    onConfirm(
                        name.ifBlank { "$host/$share" },
                        fullHost, share, pathStr,
                        user.ifBlank { null }, pass.ifBlank { null }, null
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

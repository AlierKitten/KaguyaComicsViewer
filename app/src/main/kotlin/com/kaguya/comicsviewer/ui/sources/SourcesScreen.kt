package com.kaguya.comicsviewer.ui.sources

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kaguya.comicsviewer.domain.model.ComicSourceType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    @Suppress("UNUSED_PARAMETER") nav: NavController,
    viewModel: SourcesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val scanning by viewModel.isScanning.collectAsStateWithLifecycle()
    var showSmb by remember { mutableStateOf(false) }
    var pendingLocalName by remember { mutableStateOf<String?>(null) }

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
        topBar = { TopAppBar(title = { Text("文件源") }) },
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
                        scanning = scanning,
                        onToggle = { viewModel.toggleEnabled(row.source, it) },
                        onScan = { viewModel.scan(row.source) },
                        onDelete = { viewModel.delete(row.source) }
                    )
                }
            }
        }
    }

    if (showSmb) {
        SmbDialog(
            onDismiss = { showSmb = false },
            onConfirm = { name, host, share, path, user, pass, domain ->
                viewModel.addSmbSource(name, host, share, path, user, pass, domain)
                showSmb = false
            }
        )
    }
}

@Composable
private fun SourceItem(
    row: SourceRow,
    scanning: Boolean,
    onToggle: (Boolean) -> Unit,
    onScan: () -> Unit,
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
            }
            Switch(checked = row.source.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onScan, enabled = !scanning) {
                if (scanning) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(Icons.Outlined.Refresh, null)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SmbDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String?, String?, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var share by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 SMB 源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it }, label = { Text("主机（含端口，可选）") }, placeholder = { Text("192.168.1.10:445") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(share, { share = it }, label = { Text("共享名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(path, { path = it }, label = { Text("子路径（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                HorizontalDivider()
                OutlinedTextField(user, { user = it }, label = { Text("用户名（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("密码（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(domain, { domain = it }, label = { Text("域（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank() && share.isNotBlank(),
                onClick = {
                    onConfirm(
                        name.ifBlank { "$host/$share" },
                        host, share,
                        path.ifBlank { null },
                        user.ifBlank { null },
                        pass.ifBlank { null },
                        domain.ifBlank { null }
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

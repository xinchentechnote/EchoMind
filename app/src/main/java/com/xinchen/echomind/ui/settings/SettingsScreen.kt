package com.xinchen.echomind.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinchen.echomind.asr.ModelFileSpec
import com.xinchen.echomind.asr.ModelStatus
import com.xinchen.echomind.ui.common.formatBytes

/**
 * 设置页 v1：目前只有「模型」一节（FR-510 / FR-901）。
 *
 * 文案一律来自 [ModelDisplay]（纯 Kotlin，JVM 有用例），页面只做排版与动作分发；
 * 就绪与否、差哪个文件、进度到哪——全部读 [ModelStatus]，这里一个判断都不另写
 * （FR-901：UI 只许读 `ModelStatus`/`ModelCatalog`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    // SAF 导入：两个包共用一个选择器，记住是哪个包发起的，结果按文件名对号入座（VM 里做）
    val context = LocalContext.current
    var pendingImportSpecId by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val specId = pendingImportSpecId
        pendingImportSpecId = null
        if (uri != null && specId != null) {
            viewModel.importPicked(specId, uri.toString(), displayNameOf(context, uri))
        }
    }
    var deleteTarget by remember { mutableStateOf<ModelStatus?>(null) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            item {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text("模型", style = MaterialTheme.typography.titleMedium)
                    Text(
                        ModelDisplay.SECTION_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            items(state.models, key = { it.spec.id }) { status ->
                ModelPackageCard(
                    status = status,
                    busy = state.isBusy,
                    onDownload = { viewModel.download(status.spec.id) },
                    onImport = {
                        pendingImportSpecId = status.spec.id
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    onDelete = { deleteTarget = status },
                )
            }
            item {
                Text(
                    "字号、深浅色、统计重算、按文件夹重新授权将在后续阶段加入",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除模型文件") },
            text = { Text(ModelDisplay.deleteConfirmText(target)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConfirmed(target.spec.id)
                        deleteTarget = null
                    },
                ) { Text(ModelDisplay.deleteButtonLabel(target)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("先不") }
            },
        )
    }
}

@Composable
private fun ModelPackageCard(
    status: ModelStatus,
    busy: Boolean,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.spec.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    ModelDisplay.statusLabel(status),
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        status.isReady -> MaterialTheme.colorScheme.primary
                        status.verification != null -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                ModelDisplay.roleLabel(status.spec.role),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )

            status.spec.files.forEach { fileSpec ->
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "${fileSpec.fileName}（${formatBytes(fileSpec.expectedBytes)}）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        ModelDisplay.fileStateLabel(status.verification, fileSpec.fileName),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.verification?.failures?.any { it.fileName == fileSpec.fileName } == true) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        "${ModelDisplay.checksumLabel(fileSpec)}；${ModelDisplay.sourceLabel(fileSpec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ModelDisplay.progressLabel(status)?.let { label ->
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                ModelDisplay.progressFraction(status)?.let { fraction ->
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }

            status.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                if (ModelDisplay.canDownload(status)) {
                    Button(onClick = onDownload, enabled = !busy) {
                        Text(ModelDisplay.downloadButtonLabel(status))
                    }
                }
                if (ModelDisplay.canImport(status)) {
                    FilledTonalButton(onClick = onImport, enabled = !busy) { Text("导入") }
                }
                if (ModelDisplay.canDelete(status)) {
                    TextButton(onClick = onDelete, enabled = !busy) {
                        Text(ModelDisplay.deleteButtonLabel(status))
                    }
                }
            }
        }
    }
}

/** SAF 文档的显示名；查不到就退回路径最后一段，再不行让 VM 按「名字对不上」如实拒绝 */
private fun displayNameOf(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.let { return it }
        }
    }
    return uri.lastPathSegment ?: ""
}

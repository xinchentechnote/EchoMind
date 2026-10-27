package com.xinchen.echomind.ui.device

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinchen.echomind.data.repo.stableIdFromUri
import com.xinchen.echomind.domain.importing.DeviceAudioEntry
import com.xinchen.echomind.domain.importing.DeviceAudioFolderRef
import com.xinchen.echomind.domain.importing.DeviceAudioListing
import com.xinchen.echomind.ui.common.formatBytes
import com.xinchen.echomind.ui.common.formatDuration

/**
 * 本机音频页：MediaStore 扫描 → 卷 → 文件夹逐层下钻（VidNest 的层级浏览方式），
 * 文件夹可整夹导入书架；已在书架的文件点行直达播放，未导入的先入库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceAudioScreen(
    onBack: () -> Unit,
    onOpenPlayer: (String) -> Unit,
    viewModel: DeviceAudioViewModel = viewModel(factory = DeviceAudioViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 用户可能去系统设置授完权再回来：ON_RESUME 时重查一次权限，拿到了就自动开扫
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermissionResult(granted)
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(screenTitle(state), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.permission == AudioPermissionState.Granted) {
                        TextButton(onClick = { viewModel.scan() }) { Text("重扫") }
                    }
                    if (state.isBusy || state.scanning) {
                        CircularProgressIndicator(modifier = Modifier.padding(horizontal = 10.dp), strokeWidth = 2.dp)
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.permission != AudioPermissionState.Granted -> PermissionGate(
                    state = state,
                    onRequest = {
                        if (state.permissionName.isNotEmpty()) permissionLauncher.launch(state.permissionName)
                    },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                )

                else -> AudioBrowser(
                    state = state,
                    onGoUp = viewModel::goUp,
                    onRetry = viewModel::scan,
                    onOpenFolder = viewModel::openFolder,
                    onImportFolder = viewModel::importFolder,
                    onFileClick = { entry ->
                        // 已在书架 → 直接进播放页；没导入过 → 先入库（导入是异步的，
                        // 播放页按 audioId 找不到还没落库的行，所以新文件不自动跳转）
                        if (entry.uri in state.importedUris) {
                            onOpenPlayer(stableIdFromUri(entry.uri))
                        } else {
                            viewModel.importFile(entry)
                        }
                    },
                )
            }
        }
    }
}

private fun screenTitle(state: DeviceAudioUiState): String = when {
    state.volume == null -> "本机音频"
    state.path.isNullOrEmpty() -> DeviceAudioFolderRef.volumeDisplayName(state.volume)
    else -> state.path!!.substringAfterLast('/')
}

/** 读不到本机音频时的整页说明：为什么、怎么给权限，拒绝两次后换「去系统设置」 */
@Composable
private fun PermissionGate(
    state: DeviceAudioUiState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val blocked = state.permission == AudioPermissionState.Blocked
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要「音乐和音频」读取权限", style = MaterialTheme.typography.titleMedium)
            Text(
                "扫描本机音频需要读取媒体库。EchoMind 不会上传任何文件，" +
                    "SAF 导入的文件夹不受影响，仍然不需要这个权限。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (blocked) {
                Text(
                    "授权请求已被拒绝，请在系统设置中手动开启「音乐和音频」。",
                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 16.dp)) { Text("去系统设置") }
            } else {
                Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text("授予读取权限") }
            }
        }
    }
}

@Composable
private fun AudioBrowser(
    state: DeviceAudioUiState,
    onGoUp: () -> Unit,
    onRetry: () -> Unit,
    onOpenFolder: (DeviceAudioFolderRef) -> Unit,
    onImportFolder: (DeviceAudioFolderRef) -> Unit,
    onFileClick: (DeviceAudioEntry) -> Unit,
) {
    // 系统返回键的语义跟随浏览层级：卷内先上退一层，选卷页才退出整个页面（交给 onBack）
    BackHandler(enabled = state.canGoUp, onBack = onGoUp)
    when {
        state.scanError != null -> ErrorPane(state.scanError, onRetry)
        state.scanning -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.listing.folders.isEmpty() && state.listing.files.isEmpty() -> EmptyPane()
        else -> Listing(state.listing, state.importedUris, onOpenFolder, onImportFolder, onFileClick)
    }
}

@Composable
private fun Listing(
    listing: DeviceAudioListing,
    importedUris: Set<String>,
    onOpenFolder: (DeviceAudioFolderRef) -> Unit,
    onImportFolder: (DeviceAudioFolderRef) -> Unit,
    onFileClick: (DeviceAudioEntry) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (listing.folders.isNotEmpty()) {
            item(key = "folders-header") { SectionLabel("文件夹") }
            items(listing.folders, key = { "f:${it.ref.volume}/${it.ref.path}" }) { folder ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFolder(folder.ref) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(folder.ref.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${folder.fileCount} 首",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onImportFolder(folder.ref) }) { Text("导入") }
                }
                HorizontalDivider()
            }
        }
        if (listing.files.isNotEmpty()) {
            item(key = "files-header") { SectionLabel("音频") }
            items(listing.files, key = { it.uri }) { entry ->
                val imported = entry.uri in importedUris
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFileClick(entry) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        entry.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            formatDuration(entry.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatBytes(entry.sizeBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (imported) {
                            Text(
                                "已在书架",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyPane() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            "这里没有扫描到支持的音频\n（支持 MP3 / M4A / AAC / FLAC / WAV / WMA）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

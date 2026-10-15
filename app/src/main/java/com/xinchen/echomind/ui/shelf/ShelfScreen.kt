package com.xinchen.echomind.ui.shelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinchen.echomind.data.db.AudioBookEntity
import com.xinchen.echomind.data.db.ShelfRow
import com.xinchen.echomind.data.repo.ShelfTab
import com.xinchen.echomind.ui.common.OpenPersistableDocumentTree
import com.xinchen.echomind.ui.common.formatDuration
import com.xinchen.echomind.ui.common.formatRelativeTime
import com.xinchen.echomind.ui.common.progressFraction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShelfScreen(
    onOpenPlayer: (String) -> Unit,
    onTranscribe: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShelfViewModel = viewModel(factory = ShelfViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<ShelfRow?>(null) }
    val treePicker = rememberLauncherForActivityResult(OpenPersistableDocumentTree()) { uri ->
        if (uri != null) viewModel.importFolder(uri)
    }

    state.message?.let { message ->
        androidx.compose.runtime.LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("书架") },
                actions = {
                    IconButton(onClick = { treePicker.launch(null) }) {
                        Text("导入", style = MaterialTheme.typography.labelLarge)
                    }
                    IconButton(onClick = { viewModel.rescanAll() }) {
                        Text("重扫", style = MaterialTheme.typography.labelLarge)
                    }
                    if (state.isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp).padding(6.dp), strokeWidth = 2.dp)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                placeholder = { Text("搜索标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
            val tabs = listOf(ShelfTab.All to "全部", ShelfTab.Recent to "最近播放", ShelfTab.Favorites to "收藏")
            TabRow(selectedTabIndex = tabs.indexOfFirst { it.first == state.tab }.coerceAtLeast(0)) {
                tabs.forEach { (tab, label) ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.onTabSelected(tab) },
                        text = { Text(label) },
                    )
                }
            }

            val lastPlayed = state.lastPlayed
            if (lastPlayed != null && state.query.isEmpty() && state.tab == ShelfTab.All) {
                ContinueCard(lastPlayed = lastPlayed, onClick = { onOpenPlayer(lastPlayed.id) })
            }

            if (state.rows.isEmpty()) {
                EmptyShelf(
                    hasFolders = state.folders.isNotEmpty(),
                    tab = state.tab,
                    hasQuery = state.query.isNotEmpty(),
                    onImport = { treePicker.launch(null) },
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.id }) { row ->
                        ShelfRowItem(
                            row = row,
                            onClick = { onOpenPlayer(row.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(row.id, !row.isFavorite) },
                            onRename = { renaming = row },
                            onRemove = { viewModel.removeBook(row.id) },
                            onTranscribe = { onTranscribe(row.id) },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { row ->
        var text by remember(row.id) { mutableStateOf(row.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(row.fileName) })
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.rename(row.id, text)
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ContinueCard(lastPlayed: ShelfRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("继续上次收听", style = MaterialTheme.typography.labelMedium)
                Text(
                    lastPlayed.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "已播到 ${formatDuration(lastPlayed.lastPositionMs)} / ${formatDuration(lastPlayed.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ShelfRowItem(
    row: ShelfRow,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onTranscribe: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val missing = row.fileStatus == AudioBookEntity.FileStatus.Missing
    Column(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !missing, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleFavorite) {
                // material-icons-core 1.7.8 的 Outlined.Star 与 Filled.Star 几何完全相同（实测渲染成实心），
                // 收藏/未收藏用字形区分才有可见差异
                Text(if (row.isFavorite) "★" else "☆", style = MaterialTheme.typography.titleMedium)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; onRename() })
                    // 转录不在这里入队，只把书带到转录页去确认：那一页才是唯一能发起转录的地方，
                    // 「同一时刻只跑一本」的说明也就只需要写一份
                    DropdownMenuItem(text = { Text("转录这本书") }, onClick = { menuOpen = false; onTranscribe() })
                    DropdownMenuItem(text = { Text("从书架移除") }, onClick = { menuOpen = false; onRemove() })
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatDuration(row.durationMs), style = MaterialTheme.typography.bodySmall)
            Text("·", style = MaterialTheme.typography.bodySmall)
            Text(
                formatRelativeTime(row.lastPlayedAtMs, System.currentTimeMillis()),
                style = MaterialTheme.typography.bodySmall,
            )
            Text("·", style = MaterialTheme.typography.bodySmall)
            Text(transcriptionLabel(row.transcriptionState), style = MaterialTheme.typography.bodySmall)
            if (missing) {
                Text("· 文件已不在", style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error))
            }
        }
        // 只有真播过的书才画进度轨道，否则满屏都是 0% 的空线
        if (row.lastPlayedAtMs > 0) {
            progressFraction(row.lastPositionMs, row.durationMs)?.let { fraction ->
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * 这一格说的是**转录任务**的状态，不是「这本书有没有字幕」：手工导入的测试字幕只写 `subtitle_segment`，
 * 不会动 `transcription_state`（AGENTS.md §5 的诚实性要求）。所以 NONE 写「未转录」而不是「无字幕」，
 * 免得读过测试字幕的书在书架上被标成什么都没有。
 */
private fun transcriptionLabel(state: String): String = when (state) {
    AudioBookEntity.TranscriptionState.Ready -> "已有字幕"
    AudioBookEntity.TranscriptionState.Running -> "转录中"
    AudioBookEntity.TranscriptionState.Queued -> "排队中"
    AudioBookEntity.TranscriptionState.Failed -> "转录失败"
    AudioBookEntity.TranscriptionState.Expired -> "字幕已过期"
    else -> "未转录"
}

@Composable
private fun EmptyShelf(hasFolders: Boolean, tab: ShelfTab, hasQuery: Boolean, onImport: () -> Unit) {
    // 「没有匹配」和「这个 tab 本来就空」是两件事：混成一句会让收藏页看起来像搜索坏了
    val (title, hint) = when {
        !hasFolders -> "书架还是空的" to "选择一个包含 MP3 的文件夹开始导入"
        hasQuery -> "没有匹配的音频" to "试试换个关键词，或重新扫描文件夹"
        tab == ShelfTab.Favorites -> "还没有收藏" to "点行上的 ☆ 把常听的书收进来"
        tab == ShelfTab.Recent -> "还没有播放记录" to "随便播一首，这里就会出现最近收听"
        else -> "书架里还没有音频" to "重新扫描文件夹，或导入其他文件夹"
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (!hasFolders) {
                Button(onClick = onImport, modifier = Modifier.padding(top = 16.dp)) { Text("导入文件夹") }
            }
        }
    }
}

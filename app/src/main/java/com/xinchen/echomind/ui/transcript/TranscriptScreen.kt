package com.xinchen.echomind.ui.transcript

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptJobStatus
import com.xinchen.echomind.ui.common.formatRelativeTime

/**
 * 转录页（FR-507 / FR-508）。
 *
 * 列表的每一行就是库里那一行任务——**不在内存里自造状态**：worker 是写入方，页面只读 `jobs()` 的流。
 * 两处诚实性要求落在这里：
 * 1. 分母未知时不画进度条也不说百分比，只说「已识别 N 段」（[TranscriptDisplay.progressLabel]）；
 * 2. 失败行给的是「你能做什么」，其中**没有**「补时间」这一项（AGENTS.md §5）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptScreen(
    onOpenShelf: () -> Unit,
    modifier: Modifier = Modifier,
    pendingAudioId: String? = null,
    viewModel: TranscriptViewModel = viewModel(factory = TranscriptViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 前台服务的通知在 Android 13+ 要运行时授权：没授权时任务照样跑，但通知栏一句都看不见（静默失败）
    val context = LocalContext.current
    val askNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(pendingAudioId) { viewModel.acceptTarget(pendingAudioId) }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("转录") }) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.pendingAudioId?.let { audioId ->
                StartCard(
                    title = state.titles[audioId] ?: "这本书",
                    existing = state.existingAsr[audioId] ?: ExistingAsr.None,
                    busy = state.isBusy,
                    selectedModel = state.modelChoice,
                    onModelSelected = viewModel::onModelSelected,
                    onStart = { force ->
                        viewModel.transcribe(audioId, force)
                        viewModel.clearTarget()
                    },
                    onDismiss = viewModel::clearTarget,
                )
            }

            if (state.jobs.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有转录任务", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "回书架选一本书，点行上的「⋮」→「转录这本书」",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Button(onClick = onOpenShelf, modifier = Modifier.padding(top = 16.dp)) { Text("去书架") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(state.jobs, key = { it.id }) { row ->
                        JobCard(
                            row = row,
                            title = state.titles[row.audioId],
                            onCancel = { viewModel.cancel(row.id) },
                            onRetry = { viewModel.retry(row.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 从书架带进来的目标：确认后入队。旧字幕会被整本替换这件事要说在前面（[TranscriptDisplay.startGuide] 按 READY/EXPIRED 分别说） */
@Composable
private fun StartCard(
    title: String,
    existing: ExistingAsr,
    busy: Boolean,
    selectedModel: ModelChoice,
    onModelSelected: (ModelChoice) -> Unit,
    onStart: (force: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("转录这本书", style = MaterialTheme.typography.labelMedium)
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                TranscriptDisplay.startGuide(existing),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "识别模型",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                ModelChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = selectedModel == choice,
                        onClick = { onModelSelected(choice) },
                        label = { Text(choice.label) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                Button(onClick = { onStart(TranscriptDisplay.startNeedsForce(existing)) }, enabled = !busy) {
                    Text(TranscriptDisplay.startButtonLabel(existing))
                }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("先不") }
            }
        }
    }
}

@Composable
private fun JobCard(
    row: TranscriptJobRow,
    title: String?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title ?: "（这本书已不在书架）",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    TranscriptDisplay.statusLabel(row.status),
                    style = MaterialTheme.typography.labelLarge,
                    color = when (row.status) {
                        TranscriptJobStatus.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    },
                )
            }
            Text(
                "${row.modelId} · 权重 ${row.modelVersion} · ${formatRelativeTime(row.createdAtMs, System.currentTimeMillis())}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            // 分母未知时 progressLabel 给的是「已识别 N 段」而不是百分比；进度条只在真有分母时才画
            TranscriptDisplay.progressLabel(row)?.let { label ->
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                row.progress?.let { fraction ->
                    if (row.status == TranscriptJobStatus.Running) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }

            row.failure?.let { failure ->
                Text(
                    TranscriptDisplay.failureGuide(failure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
                // 异常原文如实展示，不做二次加工（它是排查的第一手证据）
                row.errorMessage?.let { message ->
                    Text(
                        "原始信息：$message",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (TranscriptDisplay.cancellable(row.status)) {
                    FilledTonalButton(onClick = onCancel) { Text("取消") }
                }
                if (TranscriptDisplay.retryable(row.status)) {
                    Button(onClick = onRetry) { Text("重试") }
                }
            }
        }
    }
}

package com.xinchen.echomind.ui.player

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.data.repo.SubtitleSummary
import com.xinchen.echomind.domain.model.SubtitleSegment
import com.xinchen.echomind.domain.playback.PlaybackUiState
import com.xinchen.echomind.domain.subtitle.SubtitleWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.xinchen.echomind.ui.common.formatDuration
import com.xinchen.echomind.ui.common.formatListenDuration

/**
 * 播放页（FR-302/303/308/309）。进度条、倍速、±15s 的步长全部读 [PlaybackPolicy]，
 * 与通知栏、统计口径是同一套常量。字幕区在 Phase 5 接入前如实标注，不放假字幕。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    audioId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as EchoMindApp
    val viewModel: PlayerViewModel = viewModel(
        key = "player-$audioId",
        factory = PlayerViewModel.factory(app, audioId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val subtitles by viewModel.subtitles.collectAsStateWithLifecycle()
    val summary by viewModel.subtitleSummary.collectAsStateWithLifecycle()
    val notice by viewModel.subtitleNotice.collectAsStateWithLifecycle()

    // FR-302 的通知栏控制在 Android 13+ 需要运行时通知权限：没有它服务照样播，但用户看不到任何控制入口
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.loading) "载入中…" else state.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Text(if (state.favorite) "★" else "☆", style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = viewModel::stop) {
                        Text("停止", style = MaterialTheme.typography.labelLarge)
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            when {
                state.missing -> Text(
                    "这本书已不在书架，无法播放。",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 24.dp),
                )

                else -> PlayerBody(
                    playback = playback,
                    state = state,
                    viewModel = viewModel,
                    subtitles = subtitles,
                    summary = summary,
                    notice = notice,
                )
            }
        }
    }
}

@Composable
private fun PlayerBody(
    playback: PlaybackUiState,
    state: PlayerScreenState,
    viewModel: PlayerViewModel,
    subtitles: SubtitleWindow,
    summary: SubtitleSummary,
    notice: String?,
) {
    // 拖动期间用本地值：松手才 seek，否则每帧都在打断播放器并写库
    var dragTo by remember { mutableStateOf<Float?>(null) }
    val durationMs = playback.durationMs
    val canSeek = durationMs > 0
    val shownPosition = dragTo?.toLong() ?: playback.positionMs

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(playback.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        playback.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Slider(
            value = shownPosition.toFloat().coerceAtLeast(0f),
            onValueChange = { dragTo = it },
            onValueChangeFinished = {
                dragTo?.let { viewModel.seekTo(it.toLong()) }
                dragTo = null
            },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
            enabled = canSeek,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shownPosition), style = MaterialTheme.typography.bodySmall)
            Text(
                if (canSeek) "-${formatDuration(durationMs - shownPosition)}" else "时长未知",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButtonAction("上一首", enabled = playback.hasPrevious, onClick = viewModel::previous)
            TextButtonAction("-15s", enabled = canSeek, onClick = viewModel::skipBackward)
            FilledTonalButton(onClick = viewModel::playPause, modifier = Modifier.size(88.dp)) {
                Text(if (playback.isPlaying) "暂停" else "播放", style = MaterialTheme.typography.titleMedium)
            }
            TextButtonAction("+15s", enabled = canSeek, onClick = viewModel::skipForward)
            TextButtonAction("下一首", enabled = playback.hasNext, onClick = viewModel::next)
        }

        if (playback.buffering && !playback.isPlaying) {
            Text("缓冲中…", style = MaterialTheme.typography.bodySmall)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlaybackPolicy.SPEED_STEPS.forEach { speed ->
                FilterChip(
                    selected = state.speed == speed,
                    onClick = { viewModel.setSpeed(speed) },
                    label = { Text(formatSpeed(speed)) },
                )
            }
        }

        Text(
            "本首累计收听 ${formatListenDuration(state.listenedMsTotal)} · 有效播放 ${state.playCount} 次 · 完播 ${state.completedCount} 次",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )

        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            SubtitleSection(viewModel, subtitles, summary, notice)
        }
    }
}

/**
 * 字幕区（FR-701/702/706）。窗口只有最多 7 句，所以直接铺开，不用滚动列表。
 *
 * 没有字幕时如实说「还没有」，并给出真正的原因（本地 ASR 在 Phase 6），不显示任何占位文本。
 */
@Composable
private fun SubtitleSection(
    viewModel: PlayerViewModel,
    window: SubtitleWindow,
    summary: SubtitleSummary,
    notice: String?,
) {
    val context = LocalContext.current
    // 只给 debug 包装开：正式包里不能有一个「凭空造字幕」的入口（AGENTS.md §5）
    val isDebugBuild = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val scope = rememberCoroutineScope()
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        scope.launch {
            val raw = uri?.let { u ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(u)?.use { it.readBytes().decodeToString() }
                    }.getOrNull()
                }
            }
            viewModel.importTestSubtitles(raw)
        }
    }

    Column(modifier = Modifier.padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("字幕", style = MaterialTheme.typography.titleSmall)
            if (summary.count > 0) {
                Text(
                    buildString {
                        append("${summary.count} 句")
                        if (!summary.producedByAsr) append(" · 测试字幕，非 ASR 产物")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (summary.producedByAsr) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )
            }
        }

        when {
            summary.count == 0 -> {
                Text(
                    "还没有字幕。本地转录（Phase 6 的 sherpa-onnx）接入后这里会自动出现，之前不会放任何假字幕。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (isDebugBuild) {
                    TextButton(onClick = { pickSubtitle.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("导入测试字幕（SRT/VTT/TSV，仅调试）", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            else -> {
                // 点击任一句 = 跳到那句的起点（FR-706）
                window.before.forEach { SubtitleLine(it, current = false, onSeek = viewModel::seekTo) }
                window.current?.let { SubtitleLine(it, current = true, onSeek = viewModel::seekTo) }
                window.after.forEach { SubtitleLine(it, current = false, onSeek = viewModel::seekTo) }
            }
        }

        notice?.let {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::dismissSubtitleNotice) { Text("知道了", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun SubtitleLine(segment: SubtitleSegment, current: Boolean, onSeek: (Long) -> Unit) {
    Text(
        text = segment.text,
        style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (current) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f) else Color.Transparent,
            )
            .clickable(onClickLabel = "跳转到这句") { onSeek(segment.startMs) }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun TextButtonAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

private fun formatSpeed(speed: Float): String = if (speed == speed.toLong().toFloat()) "${speed.toLong()}x" else "${speed}x"

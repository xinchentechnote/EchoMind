package com.xinchen.echomind.ui.player

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.xinchen.echomind.ui.common.findActivity
import com.xinchen.echomind.ui.transcript.TranscriptDisplay

/**
 * 播放页（FR-302/303/308/309/310）。进度条、倍速、±15s 的步长全部读 [PlaybackPolicy]，
 * 与通知栏、统计口径是同一套常量。
 *
 * 布局（2026-10-24 用户口径）：**字幕在上、操作面板在下**。字幕卡吃掉所有剩余高度——大屏上不再有一截空白——
 * 并按量出来的高度把字幕窗口放大到能放下的句数（下限七句，FR-701）；当前句用大字。
 * 面板收紧：一行进度、一行走带键、一行倍速 + 单曲循环、一行统计。标题只在顶栏出现一次。
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
    val liveTranscript by viewModel.liveTranscript.collectAsStateWithLifecycle()
    val liveWindow by viewModel.liveWindow.collectAsStateWithLifecycle()

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

    // 播放常亮（FR-311，2026-10-30）：停在播放页且真在播时屏幕不灭——边听边看字幕的页面
    // 灭屏就没用了。判据是 isPlaying 而不是「页面在前」：暂停/播完就该让屏幕照常休眠；
    // 离开页面时 onDispose 摘掉 flag，别的页面不受影响。FLAG_KEEP_SCREEN_ON 只压这一扇 window，
    // 不需要任何权限，系统设置里的自动休眠在离开后被原样恢复。
    val activity = context.findActivity()
    DisposableEffect(activity, playback.isPlaying) {
        val window = activity?.window
        if (playback.isPlaying && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
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
                    liveTranscript = liveTranscript,
                    liveWindow = liveWindow,
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
    liveTranscript: PlayerTranscriptState,
    liveWindow: SubtitleWindow?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 字幕区：weight(1f) 吃掉面板之外的全部高度，大屏上不再留白
        Card(modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) {
            SubtitleSection(viewModel, subtitles, summary, notice, liveTranscript, liveWindow)
        }
        ControlPanel(playback = playback, state = state, viewModel = viewModel)
    }
}

/**
 * 操作面板（底部，收紧）：出错一行 → 进度条 + 两端时间 → 走带键 → 倍速与单曲循环 → 本首统计一行。
 * 走带键用小号文字按钮、播放键 60dp：面板高度约 240dp，剩下的全给字幕。
 */
@Composable
private fun ControlPanel(
    playback: PlaybackUiState,
    state: PlayerScreenState,
    viewModel: PlayerViewModel,
) {
    // 拖动期间用本地值：松手才 seek，否则每帧都在打断播放器并写库
    var dragTo by remember { mutableStateOf<Float?>(null) }
    val durationMs = playback.durationMs
    val canSeek = durationMs > 0
    val shownPosition = dragTo?.toLong() ?: playback.positionMs

    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
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
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(shownPosition), style = MaterialTheme.typography.labelSmall)
            if (playback.buffering && !playback.isPlaying) {
                Text("缓冲中…", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                if (canSeek) "-${formatDuration(durationMs - shownPosition)}" else "时长未知",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButtonAction("上一首", enabled = playback.hasPrevious, onClick = viewModel::previous)
            TextButtonAction("-15s", enabled = canSeek, onClick = viewModel::skipBackward)
            FilledTonalButton(
                onClick = viewModel::playPause,
                modifier = Modifier.size(60.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (playback.isPlaying) "暂停" else "播放", style = MaterialTheme.typography.titleSmall)
            }
            TextButtonAction("+15s", enabled = canSeek, onClick = viewModel::skipForward)
            TextButtonAction("下一首", enabled = playback.hasNext, onClick = viewModel::next)
        }

        // 倍速 + 单曲循环（FR-310）放一行；窄屏放不下就横向滚动，不换行占高度
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = playback.repeatOne,
                onClick = viewModel::toggleRepeatOne,
                label = { Text("单曲循环", style = MaterialTheme.typography.labelMedium) },
            )
            PlaybackPolicy.SPEED_STEPS.forEach { speed ->
                FilterChip(
                    selected = state.speed == speed,
                    onClick = { viewModel.setSpeed(speed) },
                    label = { Text(formatSpeed(speed), style = MaterialTheme.typography.labelMedium) },
                )
            }
        }

        Text(
            "本首累计收听 ${formatListenDuration(state.listenedMsTotal)} · 有效播放 ${state.playCount} 次 · 完播 ${state.completedCount} 次",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 字幕区（FR-701/702/706）。窗口大小随可用高度变：量出卡片高度 → 估能放几句 → 告诉 ViewModel 把半径放大
 * （下限七句）。估算按「一句最多两行」取整，宁少勿多——多估一句就会在底部被裁掉半句。
 *
 * 转录进行中（「首次播放实时转录并显示」）优先显示实时预览窗口，顶部给状态行；
 * 失败/取消给可执行文案（与转录页同一套 [TranscriptDisplay]）；没有字幕也没有转录时如实说「还没有」，
 * 并给一键「开始转录」。不显示任何占位假字幕。
 */
@Composable
private fun SubtitleSection(
    viewModel: PlayerViewModel,
    window: SubtitleWindow,
    summary: SubtitleSummary,
    notice: String?,
    liveTranscript: PlayerTranscriptState,
    liveWindow: SubtitleWindow?,
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        // 估能放几句（宁少勿多，多估一句就会在底部裁掉半句）：
        // 先按宽度估一句占几行——书解一句常到 45 字，bodyLarge 16sp 的汉字约 17dp 宽；
        // 普通句每行 24dp、当前句每行 30dp，各加 8dp 间距；头部与状态行预留 56dp
        val charsPerLine = (maxWidth.value / 17f).toInt().coerceAtLeast(8)
        val linesPerSentence = ((45 + charsPerLine - 1) / charsPerLine).coerceAtLeast(1)
        val contextRow = linesPerSentence * 24 + 8
        val currentRow = linesPerSentence * 30 + 8
        val contextLines = ((maxHeight.value - 56 - currentRow) / contextRow).toInt().coerceAtLeast(0)
        val radius = (contextLines / 2).coerceAtLeast(PlaybackPolicy.SUBTITLE_WINDOW_MIN_RADIUS)
        LaunchedEffect(radius) { viewModel.setWindowRadius(radius) }

        Column(modifier = Modifier.fillMaxSize()) {
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

            // 转录状态行（转录中/排队中）与失败/取消的降级文案：两者不并存，都是如实陈述
            liveTranscript.statusLine?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            liveTranscript.failureLine?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // 句子块：居中铺在剩余高度里；开头几句不足窗口时也不会挤在顶上。裁剪到自己的边界，估多了也不会盖住标题行
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    // 转录进行中：实时预览优先（它是「正在产出的最新结果」，转完由库里的正式字幕接管）
                    liveWindow != null -> SubtitleLines(liveWindow, onSeek = viewModel::seekTo)

                    summary.count == 0 -> {
                        Text(
                            "还没有字幕。" + TranscriptDisplay.SCOPE_NOTE + "任务管理在「转录」页。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 没有任务在跑/排队才给按钮：有的话状态行已经在上面说了
                        if (liveTranscript.statusLine == null) {
                            FilledTonalButton(onClick = viewModel::transcribeNow, modifier = Modifier.padding(top = 10.dp)) {
                                Text("开始转录这本书")
                            }
                        }
                        if (isDebugBuild) {
                            TextButton(onClick = { pickSubtitle.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 4.dp)) {
                                Text("导入测试字幕（SRT/VTT/TSV，仅调试）", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    else -> SubtitleLines(window, onSeek = viewModel::seekTo)
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
}

/** 窗口里的句子：前文、当前句（大字加粗）、后文。点任一句 = 跳到那句的起点（FR-706） */
@Composable
private fun SubtitleLines(window: SubtitleWindow, onSeek: (Long) -> Unit) {
    window.before.forEach { SubtitleLine(it, current = false, onSeek = onSeek) }
    window.current?.let { SubtitleLine(it, current = true, onSeek = onSeek) }
    window.after.forEach { SubtitleLine(it, current = false, onSeek = onSeek) }
}

@Composable
private fun SubtitleLine(segment: SubtitleSegment, current: Boolean, onSeek: (Long) -> Unit) {
    Text(
        text = segment.text,
        // 当前句明显大一号（用户口径，2026-10-24）：titleLarge 22sp 加粗 vs 上下文 bodyLarge 16sp
        style = if (current) {
            MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
        } else {
            MaterialTheme.typography.bodyLarge
        },
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
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatSpeed(speed: Float): String = if (speed == speed.toLong().toFloat()) "${speed.toLong()}x" else "${speed}x"

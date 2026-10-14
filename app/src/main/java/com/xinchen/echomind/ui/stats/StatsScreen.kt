package com.xinchen.echomind.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.data.db.RecentSessionRow
import com.xinchen.echomind.ui.common.formatDuration
import com.xinchen.echomind.ui.common.formatListenDuration
import com.xinchen.echomind.ui.common.formatRelativeTime

/**
 * 统计页（FR-411）。
 *
 * 规则说明由 [PlaybackPolicy] 常量渲染（FR-903）：改了阈值，这里的文案跟着变，
 * 不会出现「文档说 5 秒、页面写 10 秒」的两套口径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("统计") },
                actions = {
                    OutlinedButton(onClick = viewModel::recomputeAggregates, modifier = Modifier.padding(end = 12.dp)) {
                        Text(if (state.busy) "重算中…" else "重算统计")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("累计收听", style = MaterialTheme.typography.labelMedium)
                        Text(formatListenDuration(state.totals.totalListenedMs), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "有效播放 ${state.totals.totalPlayCount} 次 · 完播 ${state.totals.totalCompletedCount} 次",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { RulesCard() }
            item {
                Text("最近收听", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            if (state.recent.isEmpty()) {
                item {
                    Text(
                        "还没有已结算的收听会话。播放一会儿（≥ ${PlaybackPolicy.MIN_VALID_SESSION_LISTENED_MS / 1000} 秒）后这里才会有记录。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(state.recent, key = { it.id }) { row -> RecentRow(row) }
            }
        }
    }
}

@Composable
private fun RulesCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("统计口径", style = MaterialTheme.typography.titleSmall)
            Text(
                "· 一次有效播放：本场实际在播的墙上时间 ≥ ${PlaybackPolicy.MIN_VALID_SESSION_LISTENED_MS / 1000} 秒" +
                    "（暂停、缓冲、焦点丢失期间不计时）",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "· 累计收听时长：只算真在播的时间，倍速不放大（2x 听 10 分钟记 10 分钟）",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "· 完播：播放头走到最后 ${PlaybackPolicy.END_TOLERANCE_MS / 1000} 秒以内，" +
                    "且真实听过的音频推进量 ≥ ${(PlaybackPolicy.COMPLETED_CONSUMED_RATIO * 100).toInt()}%" +
                    "（直接拖到结尾不算听完）",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "· 进度落盘：最多每 ${PlaybackPolicy.POSITION_FLUSH_INTERVAL_MS / 1000} 秒一次，退出与跃迁时立即写",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RecentRow(row: RecentSessionRow) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatRelativeTime(row.startedAtMs, System.currentTimeMillis()) +
                    if (row.completed) " · 完播" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(formatDuration(row.listenedMs), style = MaterialTheme.typography.bodyMedium)
    }
}

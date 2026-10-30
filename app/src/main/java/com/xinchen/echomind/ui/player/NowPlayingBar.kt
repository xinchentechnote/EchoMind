package com.xinchen.echomind.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xinchen.echomind.domain.playback.PlaybackUiState
import com.xinchen.echomind.ui.common.formatDuration

/**
 * 「正在播放」条（FR-312，2026-10-30）：队列里有一本书时，常驻在底部标签上方，
 * 点整条回到播放页、点右侧按钮直接播放/暂停。播放页是带参数的独立路由，
 * 底部标签切换会把它从返回栈里 pop 掉——没有这条，切去别的页面就回不来了。
 *
 * 状态只读 [PlaybackUiState]（UI 不碰 ExoPlayer，AGENTS.md §3）；显示与播放页同源的
 * [PlaybackUiState.title]/[PlaybackUiState.positionMs]，所以两边永远不会是两套进度。
 * [PlaybackUiState.isIdle]（本进程还没播过任何书）时调用方应整体不渲染这条。
 */
@Composable
fun NowPlayingBar(
    state: PlaybackUiState,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClick = onOpenPlayer)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 进度与播放页同一份 StateFlow：时长未知时只给状态词，不编时间
                val status = if (state.isPlaying) "正在播放" else "已暂停"
                val sub = if (state.durationMs > 0) {
                    "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)} · $status"
                } else {
                    status
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val tint = MaterialTheme.colorScheme.primary
            IconButton(onClick = onTogglePlayPause) {
                if (state.isPlaying) {
                    // icons-core 里没有 Pause（在 extended 包），为一个图标引 30 MB 的依赖不值（AGENTS.md §3），
                    // 两根圆角竖条就是它的全部几何
                    PauseGlyph(tint = tint, modifier = Modifier.size(32.dp))
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        modifier = Modifier.size(32.dp),
                        tint = tint,
                    )
                }
            }
        }
    }
}

@Composable
private fun PauseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .semantics { contentDescription = "暂停" }
            .padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                Modifier
                    .width(5.dp)
                    .height(18.dp)
                    .background(tint, RoundedCornerShape(1.5.dp)),
            )
        }
    }
}

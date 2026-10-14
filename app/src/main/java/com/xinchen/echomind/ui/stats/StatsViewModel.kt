package com.xinchen.echomind.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.data.db.RecentSessionRow
import com.xinchen.echomind.data.repo.PlaybackRepository
import com.xinchen.echomind.data.repo.PlaybackStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUiState(
    val totals: PlaybackStats = PlaybackStats(0, 0, 0),
    val recent: List<RecentSessionRow> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

/** 统计页（FR-411）：数字全部来自 listening_session 真源，不读聚合缓存 */
class StatsViewModel(private val playbackRepository: PlaybackRepository) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { playbackRepository.stats().collect { s -> _state.update { it.copy(totals = s) } } }
        viewModelScope.launch {
            playbackRepository.recentSessions(RECENT_LIMIT).collect { rows -> _state.update { it.copy(recent = rows) } }
        }
    }

    /** 设置页/统计页共用的对账入口（FR-410/905） */
    fun recomputeAggregates() {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching { playbackRepository.recomputeAggregates() }
            _state.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) "已按会话历史重算聚合值" else "重算失败：${result.exceptionOrNull()?.message}",
                )
            }
        }
    }

    fun onMessageShown() = _state.update { it.copy(message = null) }

    companion object {
        const val RECENT_LIMIT = 30

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EchoMindApp
                StatsViewModel(app.graph.playbackRepository)
            }
        }
    }
}

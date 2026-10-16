package com.xinchen.echomind.domain.transcript

import com.xinchen.echomind.domain.model.SubtitleSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 一本书正在进行的转录：已切出的句子（时间升序）与已识别完的语音段数。 */
data class LiveTranscript(
    val audioId: String,
    val sentences: List<SubtitleSegment>,
    val doneSegments: Int,
)

/**
 * 转录过程中的**实时预览字幕**（「首次播放实时转录并显示」的内存半边）。
 *
 * 分工要钉死：`TranscriptCoordinator` 每识别完一个 VAD 段就切句写进这里，播放页读它把句子
 * 边转边显示出来；**数据库仍然只在收尾由 `commitSubtitles` 单事务整本提交**——预览是「正在产出的
 * 证据」，不是已交付的字幕，任何失败/取消都随 [clear] 一起消失，库里不会留下半本。
 *
 * 预览与正式字幕的一处已知差别（如实记录，不掩盖）：预览按**单个 VAD 段**切句，
 * 跨段合并过度切分的那一步只有收尾的整本切分才做得到——所以转完后正式字幕偶尔比预览少几句，
 * 那是合并生效了，不是句子丢了。
 *
 * 纯 Kotlin、无 Android 依赖（AGENTS.md §3）：协调器在 worker 的 IO 线程上调 [append]，
 * StateFlow 的 update 是原子的，不需要额外的锁。
 */
class LiveTranscriptStore {

    private val _live = MutableStateFlow<Map<String, LiveTranscript>>(emptyMap())

    /** audioId → 该书当前的实时预览；没有条目 = 没在转（或已收尾） */
    val live: StateFlow<Map<String, LiveTranscript>> = _live.asStateFlow()

    /**
     * 追加一个 VAD 段切出的句子。[sentences] 为空（整段没有可用时间）时只推进段计数——
     * 「识别了但没出字」也要如实反映在进度里。
     */
    fun append(audioId: String, sentences: List<SubtitleSegment>, doneSegments: Int) {
        _live.update { current ->
            val existing = current[audioId]
            val merged = (existing?.sentences.orEmpty() + sentences)
                .sortedBy { it.startMs }
                .mapIndexed { index, sentence -> sentence.copy(index = index) }
            current + (
                audioId to LiveTranscript(
                    audioId = audioId,
                    sentences = merged,
                    doneSegments = maxOf(existing?.doneSegments ?: 0, doneSegments),
                )
                )
        }
    }

    /** 收尾（提交/失败/取消）后清掉：字幕此后以数据库为准（提交场景 Room 流会紧接着发出新字幕） */
    fun clear(audioId: String) {
        _live.update { it - audioId }
    }
}

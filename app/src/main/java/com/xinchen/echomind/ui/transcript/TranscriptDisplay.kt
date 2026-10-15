package com.xinchen.echomind.ui.transcript

import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptJobStatus

/**
 * 转录页的文字（FR-507 / FR-508）。写成纯 Kotlin 是为了让「失败该给用户什么动作」这条能在 JVM 里断言
 * （AGENTS.md §6），页面只管排版。文案里出现的数字一律来自 `core/conf` 的常量，不另写死字符串（AGENTS.md §3）。
 */
object TranscriptDisplay {

    fun statusLabel(status: TranscriptJobStatus): String = when (status) {
        TranscriptJobStatus.Queued -> "排队中"
        TranscriptJobStatus.Running -> "转录中"
        TranscriptJobStatus.Success -> "已完成"
        TranscriptJobStatus.Failed -> "失败"
        TranscriptJobStatus.Cancelled -> "已取消"
    }

    /**
     * 进度那一行。⚠ 分母在 VAD 跑完之前恒为 0，此时 [TranscriptJobRow.progress] 是 null，
     * 只能如实说「已识别 N 段」——画一条 0% 的线就是假进度（FR-507）。
     *
     * 排队中的任务不给进度：它一段都没解。
     */
    fun progressLabel(row: TranscriptJobRow): String? {
        // progress 是计算属性：先落到局部量，别在判空和取值之间把 getter 跑两遍
        val fraction = row.progress
        return when {
            row.status == TranscriptJobStatus.Queued -> null
            row.status == TranscriptJobStatus.Success -> "共 ${row.totalSegments} 段全部识别"
            fraction == null -> "已识别 ${row.doneSegments} 段（总段数要等解码跑完才知道）"
            else -> "已识别 ${row.doneSegments}/${row.totalSegments} 段（${(fraction * 100).toInt()}%）"
        }
    }

    /** 只有还在占着引擎的任务能取消（终态行取消了没有意义，仓储那侧也会返回 false） */
    fun cancellable(status: TranscriptJobStatus): Boolean = status.isActive

    /** 失败与取消的历史行可以重开一轮；重试是**新建一行**，旧行留着当错误上下文 */
    fun retryable(status: TranscriptJobStatus): Boolean =
        status == TranscriptJobStatus.Failed || status == TranscriptJobStatus.Cancelled

    /**
     * 每条失败都给「你能做什么」（FR-508）。这里刻意**不给**「按字数补时间」「跳过坏块」这类动作：
     * 那是 AGENTS.md §5 禁止的伪造（`docs/asr-design.md` §8 第一行）。
     */
    fun failureGuide(failure: TranscriptFailure): String = when (failure) {
        TranscriptFailure.ModelMissing ->
            "模型没有就位。到「设置」页点「从安装包恢复」（模型随安装包内置，不需要网络），就位后回来点重试"

        TranscriptFailure.DecodeFailed ->
            "这个文件解不出音频：可能已不在原位置、格式不支持或已损坏。先回书架重扫（或在文件管理器里确认它还在），再点重试"

        TranscriptFailure.OutOfMemory ->
            "内存不够，任务被系统收回。关掉别的应用再点重试；一本几小时的书在这类机器上可能要分几次才跑得完"

        TranscriptFailure.PermissionRevoked ->
            "这个文件夹的读取授权已经失效。回书架对该文件夹重新授权，然后点重试"

        TranscriptFailure.OutputUnreliable ->
            "模型跑完了，但没给出够格当字幕的字（不合格块超过 ${AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT}%，" +
                "或一个带时间的字都没有）。可以直接重试；如果这段音频本来就没有语音，那是正常结果——" +
                "宁可失败也不按字数均分时间"

        TranscriptFailure.Interrupted ->
            "上一次运行被中断（应用被杀或系统重启），已经识别的部分没有保留。点重试从头再跑"

        TranscriptFailure.Cancelled ->
            "这一轮被你取消了。想重跑就点重试，会新建一行，这一行留着当记录"

        TranscriptFailure.Unknown ->
            "转录异常中断。可以先重试一次；连续失败请把这个文件的格式与时长告诉我，方便定位"
    }

    /** 入队那一行的按钮文字：只有 READY 会被仓储挡下来，才需要 force，也只有那时才说「覆盖」 */
    fun startButtonLabel(existing: ExistingAsr): String =
        if (existing == ExistingAsr.Ready) "覆盖重转" else "开始转录"

    fun startNeedsForce(existing: ExistingAsr): Boolean = existing == ExistingAsr.Ready

    /** 开始卡的说明。「整本替换」这件事必须在点下去之前说清楚：`commitSubtitles` 是单事务删旧插新（AGENTS.md §4） */
    fun startGuide(existing: ExistingAsr): String = when (existing) {
        ExistingAsr.None ->
            "用本机模型识别（不联网）。同一时刻只跑一本，排在后面的会等前面那本跑完"

        ExistingAsr.Ready ->
            "它已经有 ASR 字幕。重转会整本替换旧字幕——删旧和插新在同一个事务里，不会出现半本新半本旧"

        ExistingAsr.Expired ->
            "它还留着 ASR 字幕，但音频内容已经变过，旧字幕不再可信。这一轮跑完同样整本替换（删旧插新在一个事务里）"
    }

    /** 入队结局翻成一句话（FR-506 的「不重复识别」是正常业务分支，不是异常） */
    fun enqueueOutcomeLabel(outcome: TranscriptOutcomeCopy): String = when (outcome) {
        TranscriptOutcomeCopy.Scheduled -> "已开始排队；同一时刻只跑一本，排在后面的会接着开始"
        TranscriptOutcomeCopy.AlreadyActive -> "这本书已经在队列里了，不重复排队"
        TranscriptOutcomeCopy.AlreadyTranscribed ->
            // 走到这里说明页面用的还是旧状态（多半是刚刚跑完那一轮），此刻按钮上并没有「覆盖重转」，
            // 所以给的是「怎么把它变出来」的路径，而不是让用户去找一个不在的按钮
            "这本书已经有 ASR 字幕（可能是刚刚跑完的那一轮）。回书架再点一次「转录这本书」，按钮就会变成「${startButtonLabel(ExistingAsr.Ready)}」"
        TranscriptOutcomeCopy.BookGone -> "这本书已不在书架，任务没有建起来"
        TranscriptOutcomeCopy.WorkRefused -> "任务行已经结束，这次入队没有生效"
        TranscriptOutcomeCopy.JobGone -> "那一行任务查不到了（多半书已被移除），重新选一本书吧"
        TranscriptOutcomeCopy.Cancelled -> "已取消，这一轮不会写入字幕"
        TranscriptOutcomeCopy.AlreadySettled -> "这一行已经结束了（跑完、失败或被取消），没有东西可取消"
    }
}

/**
 * 这本书现在的 ASR 字幕情况，由 ViewModel 从 `audio_book.transcription_state` 翻出来。
 *
 * 翻成枚举而不是直接把 Room 的常量传给文案函数，是为了让这段文案留在 JVM 单测里可断言（AGENTS.md §6）——
 * `TranscriptDisplay` 一旦 import `AudioBookEntity` 就绑上 Android 侧了。
 */
enum class ExistingAsr { None, Ready, Expired }

/**
 * [TranscriptDisplay.enqueueOutcomeLabel] 的输入。
 *
 * 把文案与仓储的 sealed 类型隔开，为的是这条映射能在 JVM 单测里断言（AGENTS.md §6）——
 * `TranscriptViewModel` 要 Context 与 WorkManager，端不上测试台。
 */
enum class TranscriptOutcomeCopy {
    Scheduled,
    AlreadyActive,
    AlreadyTranscribed,
    BookGone,
    WorkRefused,
    JobGone,
    Cancelled,
    AlreadySettled,
}

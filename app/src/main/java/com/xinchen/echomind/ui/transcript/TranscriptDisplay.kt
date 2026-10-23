package com.xinchen.echomind.ui.transcript

import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptJobStatus
import com.xinchen.echomind.domain.model.TranscriptPhase

/**
 * 转录页的文字（FR-507 / FR-508）。写成纯 Kotlin 是为了让「失败该给用户什么动作」这条能在 JVM 里断言
 * （AGENTS.md §6），页面只管排版。文案里出现的数字一律来自 `core/conf` 的常量，不另写死字符串（AGENTS.md §3）。
 */
object TranscriptDisplay {

    const val SCOPE_NOTE: String =
        "当前只保证普通话听书/书解的字幕效果；英文音频与歌曲暂不保证识别质量。"

    fun statusLabel(status: TranscriptJobStatus): String = when (status) {
        TranscriptJobStatus.Queued -> "排队中"
        TranscriptJobStatus.Running -> "转录中"
        TranscriptJobStatus.Success -> "已完成"
        TranscriptJobStatus.Failed -> "失败"
        TranscriptJobStatus.Cancelled -> "已取消"
    }

    /**
     * 进度那一行（FR-507）。分母是**容器时长**：解码一开始就知道，所以百分比从第一秒起就是真的；
     * 容器没给时长（[TranscriptJobRow.progress] 为 null）时只报段数，不编百分比。
     * WMA 的转码阶段单独说清楚「在转 mp3」，否则用户会以为识别卡住了。
     *
     * 排队中的任务不给进度：它一段都没解。失败/取消的行只在真识别过东西时提一句，免得满屏「已识别 0 段」。
     */
    fun progressLabel(row: TranscriptJobRow): String? = when (row.status) {
        TranscriptJobStatus.Queued -> null
        TranscriptJobStatus.Success -> "共 ${row.totalSegments} 段全部识别"
        TranscriptJobStatus.Running -> runningPhrase(row)
        TranscriptJobStatus.Failed, TranscriptJobStatus.Cancelled ->
            if (row.doneSegments > 0) "中止前已识别 ${row.doneSegments} 段" else null
    }

    /**
     * RUNNING 行的一句话（转录页与播放页共用）：
     * 「WMA 转码中 34%（转成 mp3 后开始识别）」/「识别中 58% · 已识别 120 段」/ 分母未知时「已识别 N 段」。
     */
    fun runningPhrase(row: TranscriptJobRow): String {
        // progress 是计算属性：先落到局部量，别在判空和取值之间把 getter 跑两遍
        val percent = row.progress?.let { (it * 100).toInt().coerceIn(0, 99) }
        return when (row.phase) {
            TranscriptPhase.Transcoding ->
                if (percent != null) "WMA 转码中 $percent%（转成 mp3 后开始识别）" else "WMA 转码中…（转成 mp3 后开始识别）"

            TranscriptPhase.Recognizing ->
                if (percent != null) "识别中 $percent% · 已识别 ${row.doneSegments} 段" else "已识别 ${row.doneSegments} 段"

            null -> "准备模型与解码…"
        }
    }

    /** 只有还在占着引擎的任务能取消（终态行取消了没有意义，仓储那侧也会返回 false） */
    fun cancellable(status: TranscriptJobStatus): Boolean = status.isActive

    /** 失败与取消的行可以重开一轮；重试会用新的一行**顶掉**这一行（一本书一行） */
    fun retryable(status: TranscriptJobStatus): Boolean =
        status == TranscriptJobStatus.Failed || status == TranscriptJobStatus.Cancelled

    /** 已结束的行可以删；活动行要先取消（仓储那侧同样拒删 RUNNING） */
    fun deletable(status: TranscriptJobStatus): Boolean = !status.isActive

    /**
     * 删除前的确认文案。删成功记录会**连它产出的 ASR 字幕一起删**——这件事必须在点下去之前说清楚；
     * 删失败/取消记录不碰字幕（它们本来就没产出）。
     */
    fun deleteConfirmText(row: TranscriptJobRow): String = when (row.status) {
        TranscriptJobStatus.Success ->
            "这条记录和它识别出的 ${row.totalSegments} 段字幕会一起删除，播放页将不再显示字幕；" +
                "以后再转录会从头识别。确定删除？"

        else -> "只删这条记录，不影响这本书已有的字幕。确定删除？"
    }

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
            "这一轮被你取消了。想重跑就点重试，新的一轮会顶掉这条记录"

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
        TranscriptOutcomeCopy.Deleted -> "记录已删除"
        TranscriptOutcomeCopy.DeleteRefused -> "这一行还在跑或已经不存在，没有删除；进行中的先点取消"
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
    Deleted,
    DeleteRefused,
}

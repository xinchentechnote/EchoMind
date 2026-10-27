package com.xinchen.echomind.domain.model

/**
 * 转录任务（`docs/database-design.md` §6 的应用层视图）。
 *
 * 纯 Kotlin：UI 与协调器都只吃这几个类型，Room 实体不外泄（AGENTS.md §3）。
 */
enum class TranscriptJobStatus {
    Queued,
    Running,
    Success,
    Failed,
    Cancelled,
    ;

    /** 还在占用引擎与 CPU 的任务 */
    val isActive: Boolean get() = this == Queued || this == Running
}

/**
 * 失败原因。UI 靠它决定给哪句「你可以做什么」（重新准备模型 / 重试 / 重新授权），
 * 所以枚举值必须与 `TranscriptJobEntity.ErrorCode` 一一对应，转换写在仓储实现里。
 */
enum class TranscriptFailure {
    ModelMissing,
    DecodeFailed,
    OutOfMemory,
    PermissionRevoked,

    /**
     * 模型跑完了但结果不可信：要么一个带时间的字都没给，要么不合格而被丢掉的块超过了
     * `AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT`（`docs/asr-design.md` §8 第一行）。
     * 与 [Unknown] 的区别是这条有明确的可执行动作：换一段有语音的素材、或重试，
     * 而「补一份时间」不在选项里（AGENTS.md §5）。
     */
    OutputUnreliable,
    Cancelled,
    Interrupted,
    Unknown,
}

/**
 * RUNNING 任务的阶段（FR-507 的进度显示，2026-10-24）。
 *
 * 分母来自**容器时长**而不是 VAD 的段数：容器时长在解码一开始就知道，
 * 所以进度条不用再等「解码跑完才知道总段数」——那是旧口径长期 0% 的根源。
 * WMA 书先转 mp3 再识别（D44），多出一个转码阶段。
 */
enum class TranscriptPhase {
    /** WMA → mp3 转码中；[TranscriptJobRow.positionMs] 是已解出的音频位置 */
    Transcoding,

    /** 解码 + 识别中 */
    Recognizing,
}

/** 一次进度写入的快照（协调器节流后发出，worker 落库并刷通知）。 */
data class TranscriptProgress(
    val phase: TranscriptPhase,
    val positionMs: Long,
    val durationMs: Long,
    val doneSegments: Int,
) {
    /** 整数百分比（0..99，收尾前不给 100）；分母未知为 null */
    val percent: Int?
        get() = if (durationMs <= 0) null else (positionMs * 100 / durationMs).toInt().coerceIn(0, 99)
}

data class TranscriptJobRow(
    val id: String,
    val audioId: String,
    val modelId: String,
    /** 模型权重身份（SHA-256 前 12 位），字幕是这份权重跑出来的证据 */
    val modelVersion: String,
    val status: TranscriptJobStatus,
    /** VAD 跑完才知道，入队时 0；只在 SUCCESS 终态展示（「共 N 段全部识别」） */
    val totalSegments: Int,
    val doneSegments: Int,
    /** RUNNING 时的阶段；终态行恒为 null */
    val phase: TranscriptPhase?,
    /** 已解到的音频位置（毫秒）。进度分子，来自解码器实测位置，不是估算 */
    val positionMs: Long,
    /** 容器时长（毫秒）。进度分母；容器没给就是 0，此时不显示百分比（假进度比没有进度更糟） */
    val durationMs: Long,
    val failure: TranscriptFailure?,
    /** 面向用户的原文，来自异常消息，不做二次加工 */
    val errorMessage: String?,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val finishedAtMs: Long?,
    /** WorkManager 那一侧的请求 id；没进队列（或写入失败）时 null，取消就只能改库 */
    val workRequestId: String? = null,
) {
    /**
     * 进度显示用：分子分母都是**音频时间**（已解位置 / 容器时长），转录一开始就可知，
     * 所以 RUNNING 中不再有「分母未知」的空窗。分母为 0（容器没给时长）时返回 null——
     * 不显示百分比，也不编一个。
     */
    val progress: Float?
        get() {
            if (status != TranscriptJobStatus.Running || durationMs <= 0) return null
            return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }
}

/**
 * 用哪个模型跑这次任务。值来自 `asr/ModelCatalog`（那边提供 `ModelCatalog.transcriptRef`），
 * 由调用方传进来——`domain` 不许反向依赖 `asr`（AGENTS.md §3）。
 */
data class TranscriptModelRef(
    val id: String,
    /** 权重身份：`model.int8.onnx` 钉住的 SHA-256 前 12 位 */
    val version: String,
)

/** `enqueue`/`retry` 的结局。把「不重复识别」做成返回值而不是异常，因为它是正常业务分支（FR-506） */
sealed interface EnqueueOutcome {

    data class Created(val jobId: String) : EnqueueOutcome

    /** 这本书已经有任务在跑了，复用那一行（重复点按钮不该排两个队） */
    data class AlreadyActive(val jobId: String) : EnqueueOutcome

    /** 已经转好了且文件没变，除非 force 否则不再跑 */
    data object AlreadyTranscribed : EnqueueOutcome

    /** audioId 不在书架上（被删了），任务没处可建 */
    data object AudioNotFound : EnqueueOutcome

    /** retry 传的 jobId 查不到（多半是库被清了），建新行没处可抄 */
    data object JobNotFound : EnqueueOutcome
}

/**
 * 协调器跑一本书所需的最小信息（`docs/asr-design.md` §5 第 1 步的输入）。
 *
 * [uri] 是字符串而不是 `android.net.Uri`——`domain` 不许 import `android.*`（AGENTS.md §3），
 * 换算由 `asr/TranscriptCoordinator` 在 Android 侧做。
 * [needsTreeGrant] 标记这是 SAF 树授权下的 `content://` 路径：只有这种才谈得上「授权被撤销」，
 * `file://` 的测试素材不需要授权，拿它去查 grant 会得出「没授权」的假结论。
 * [treeUri] 是这本书所在**文件夹的树 uri**，查授权只能用它：SAF 的可持久化授权发在树上，
 * `grantedTreeUris()` 列出的也是树。而 [uri] 是 `DocumentFile.fromTreeUri(...).listFiles()` 给出的
 * **文档 uri**（`.../tree/primary%3AMusic` 底下另有 `.../document/primary%3AMusic%2F01.mp3`，
 * 两者 authority 与 path 都不同），拿文档 uri 去比必然得 false——真机上每一本 SAF 的书都会被误报
 * 「授权已失效」。树 uri 由所属文件夹查出（`imported_folder.tree_uri`）。
 */
data class TranscriptionTarget(
    val audioId: String,
    val uri: String,
    val title: String,
    /** 原始文件名：协调器据此选解码器（.wma 走 libwma，其余走 MediaCodec） */
    val fileName: String = "",
    val isFilePresent: Boolean,
    val needsTreeGrant: Boolean,
    val treeUri: String?,
    /**
     * 本机音频（MediaStore）书：读 uri 靠的是「音乐和音频」运行时权限而不是 SAF 树授权，
     * 协调器要在解码前检查它，权限被撤销时给出可操作的失败原因而不是笼统的「解码失败」。
     */
    val needsMediaPermission: Boolean = false,
)
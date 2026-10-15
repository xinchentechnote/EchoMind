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
    Cancelled,
    Interrupted,
    Unknown,
}

data class TranscriptJobRow(
    val id: String,
    val audioId: String,
    val modelId: String,
    /** 模型权重身份（SHA-256 前 12 位），字幕是这份权重跑出来的证据 */
    val modelVersion: String,
    val status: TranscriptJobStatus,
    /** VAD 跑完才知道，入队时 0 */
    val totalSegments: Int,
    val doneSegments: Int,
    val failure: TranscriptFailure?,
    /** 面向用户的原文，来自异常消息，不做二次加工 */
    val errorMessage: String?,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val finishedAtMs: Long?,
) {
    /** 进度显示用：分母还没出来时不显示百分比，而不是显示 0%（那是假进度） */
    val progress: Float?
        get() = if (totalSegments <= 0) null else (doneSegments.toFloat() / totalSegments).coerceIn(0f, 1f)
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

package com.xinchen.echomind.work

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.transcript.TranscriptRepository
import java.util.UUID

/**
 * 转录工作的入队与取消（FR-507）。
 *
 * WorkManager 的请求 id 会写回 `transcript_job.work_request_id`，所以取消能精确打到那一个请求；
 * 写不进去（行已经结束）就立刻把刚入队的请求撤掉——留着它只会白跑一遍推理。
 *
 * **唯一名是全局一个，不是每本书一个**（`docs/architecture.md` D36）：WorkManager 对同一唯一名下的工作
 * 严格排队，所以「同一时刻只有一个模型句柄」这条（AGENTS.md §3）由调度层保证，不需要进程内再加一把锁。
 * 两本书各自一个唯一名的版本会真的并发跑起两个 worker，各装一份 243 MB 的 int8 模型。
 *
 * **没有设 `setExpedited`**：加急请求受系统配额限制（每 App 每天几个），而转录一跑就是几十秒到几分钟，
 * 该走的路线是 `setForeground` 前台服务，不是加急。任务也不自动重试：`Result.failure` 之后由用户
 * 在转录页点重试（FR-507 的取舍：自动重跑会把「这台机器解不开这个文件」变成无限循环）。
 */
class TranscriptWorkScheduler(
    private val context: Context,
    private val repository: TranscriptRepository,
) {

    /** 入队并把请求 id 写回任务行。返回 false = 这一行已经不归这次入队管了（被取消或结算过） */
    suspend fun schedule(jobId: String, audioId: String): Boolean {
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<TranscriptWorker>()
            .setInputData(workDataOf(TranscriptWorker.KEY_JOB_ID to jobId, TranscriptWorker.KEY_AUDIO_ID to audioId))
            .addTag(TAG)
            .build()
        // APPEND_OR_REPLACE：有活着（未结束）的链就排在后面；链条已死（最后一次被取消）就自愈成新链。
        // 不能用 APPEND：WorkManager 的文档语义是「链条被取消后，APPEND 上去的新任务**连带取消**」——
        // 实测踩过（docs/testing.md §4 Phase 6g）：用户取消过一次转录之后，后面所有入队全部卡在
        // QUEUED 永远不跑（worker 根本不会被调度），而且这条只有真机/模拟器测得出来。
        // REPLACE 单独用不行：它会把正在跑的那本掀掉（D36 更严重的错）。
        // 原来 REPLACE 兜的「行已结束但请求还挂在队列里」这种残留也不再需要清：
        // 它轮到自己跑时，协调器发现那一行不是 QUEUED，直接 Skipped，队列自己干净。
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        if (!repository.attachWorkRequest(jobId, request.id.toString())) {
            Log.w(TAG, "任务 $jobId 写不回 work_request_id（行已结束），撤掉刚入队的请求 ${request.id}")
            workManager.cancelWorkById(request.id)
            return false
        }
        return true
    }

    /**
     * 取消：先改库再停 worker。顺序反了会出现「worker 已经停了，界面上还在跑」——
     * 库是唯一事实来源，worker 那边的守卫（`WHERE status IN ('QUEUED','RUNNING')`）保证后到的写入不会覆盖 CANCELLED。
     */
    suspend fun cancel(job: TranscriptJobRow): Boolean {
        val cancelled = repository.cancel(job.id)
        job.workRequestId?.let { workRequestId ->
            runCatching { UUID.fromString(workRequestId) }
                .onSuccess { WorkManager.getInstance(context).cancelWorkById(it) }
                .onFailure { Log.w(TAG, "work_request_id 不是 UUID，只能作罢：$workRequestId") }
        }
        return cancelled
    }

    companion object {
        private const val TAG = "transcript"

        /** 全局唯一名：WorkManager 在同名序列里严格排队，一次只跑一个 worker（见类注释与 D36） */
        const val UNIQUE_NAME = "transcript-series"
    }
}

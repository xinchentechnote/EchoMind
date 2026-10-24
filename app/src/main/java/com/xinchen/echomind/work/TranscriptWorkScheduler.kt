package com.xinchen.echomind.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.transcript.TranscriptRepository
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 转录工作的入队与取消（FR-507）。
 *
 * WorkManager 的请求 id 会写回 `transcript_job.work_request_id`，所以取消能精确打到那一个请求；
 * 写不进去（行已经结束）就立刻把刚入队的请求撤掉——留着它只会白跑一遍推理。
 *
 * **每个任务一条独立的唯一名**（`transcript-job-<jobId>`，D46，2026-10-24；此前是全局一条链 `transcript-series`
 * 串行跑）：并发上限不再由 WorkManager 的排队语义保证，而是由 `markRunning` 的 SQL 车道计数
 * （`AudioFormats.ASR_MAX_CONCURRENT_JOBS`）——抢不到车道的 worker 返回 `Result.retry()`，按下面的退避再来。
 * 唯一名用 KEEP：同一行被重复调度（界面连点）时第二次是空操作，不会起两个 worker 抢同一行。
 * 旧的全局链条不再使用；它曾因 `APPEND` 的「链被取消则后续连带取消」语义踩过雷（`docs/testing.md` §4 Phase 6g），
 * 按任务独立之后这个问题自然不存在。
 *
 * **没有设 `setExpedited`**：加急请求受系统配额限制（每 App 每天几个），而转录一跑就是几十秒到几分钟，
 * 该走的路线是 `setForeground` 前台服务，不是加急。任务也不自动重跑失败：失败由用户在转录页点重试
 * （FR-507 的取舍：自动重跑会把「这台机器解不开这个文件」变成无限循环）；`retry()` 只用于「车道满、稍后再来」。
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
            // 车道满时 worker 返回 retry：线性退避，首次 15 s（WorkManager 下限 10 s），每次多 15 s
            .setBackoffCriteria(BackoffPolicy.LINEAR, LANE_RETRY_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueNameOf(jobId), ExistingWorkPolicy.KEEP, request)
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

        /** 车道满时的重试退避（秒）。与 `AudioFormats.ASR_MAX_CONCURRENT_JOBS` 搭配：一本书跑完就会有人在 ≤15 s 内接上 */
        const val LANE_RETRY_BACKOFF_SECONDS = 15L

        /** 每个任务一条唯一名（D46）；并发数由 SQL 车道守卫限制，不靠 WorkManager 排队 */
        fun uniqueNameOf(jobId: String): String = "transcript-job-$jobId"
    }
}

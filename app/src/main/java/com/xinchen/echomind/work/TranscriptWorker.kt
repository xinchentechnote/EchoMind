package com.xinchen.echomind.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.xinchen.echomind.EchoMindApp
import com.xinchen.echomind.R
import com.xinchen.echomind.asr.TranscriptOutcome
import com.xinchen.echomind.domain.model.TranscriptFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * WorkManager 那一侧的壳：把一次 `TranscriptCoordinator.run` 装进可持久化的工作请求（FR-507）。
 *
 * 它**不含任何转录逻辑**，只做三件事：起前台通知、把取消位（`isStopped`）递进去、把结局翻译成 `Result`。
 * 库里那一行的状态一律由协调器写（守卫也在那边），worker 只在一个情况下亲自写库：
 * 协调器抛出它自己没预料到的异常（Room 挂了、native 层抛 RuntimeException）——那行还挂在 RUNNING，
 * 不背这个锅就会出现「任务永远显示运行中」，所以这里补一条 `FAILED(UNKNOWN)` 收口。
 *
 * ⚠ 前台服务类型是 `dataSync`，Android 14 起系统对它有 **6 小时**的累计上限（超了会抛
 * `ForegroundServiceStartNotAllowedException` 并被强制下线）。一小时的书解按实测 RTF 远够不着，
 * 但长书 + 慢机的量还没有真机数字，真撞到再按 `docs/roadmap.md` 里记录处理。
 */
class TranscriptWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure(missingInput(KEY_JOB_ID))
        val audioId = inputData.getString(KEY_AUDIO_ID) ?: return Result.failure(missingInput(KEY_AUDIO_ID))
        val graph = (applicationContext as EchoMindApp).graph
        val repository = graph.transcriptRepository
        val bookTitle = graph.audioRepository.transcriptionTarget(audioId)?.title
            ?: applicationContext.getString(R.string.app_name)
        val notificationId = jobId.hashCode()

        createChannel()
        try {
            setForeground(ForegroundInfo(notificationId, notification(bookTitle, null), FGS_TYPE))
        } catch (e: CancellationException) {
            // 请求在起前台之前就被取消了（用户点取消，或系统按配额回收）。
            // WorkManager 2.12 的 `Result` 没有 `cancelled()` 这一档，而用户取消走的是 cancelWorkById，
            // 那时本函数的返回值系统根本不采信，所以这里给 success 不会说谎。
            return Result.success()
        }

        val outcome: TranscriptOutcome
        try {
            outcome = withContext(Dispatchers.IO) {
                graph.transcriptCoordinator.run(
                    jobId = jobId,
                    isCancelled = { isStopped },
                    // 进度写库来自解码回调（不是 suspend 上下文），只能在这里同步等一次：
                    // 异步发出去两次写库会乱序，进度条反而可能倒退
                    onProgress = { done, total ->
                        runBlocking { repository.updateProgress(jobId, done, total) }
                        notificationManager().notify(notificationId, notification(bookTitle, done))
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "协调器抛出未预料到的异常，任务行按 UNKNOWN 收口", e)
            repository.markFailed(jobId, TranscriptFailure.Unknown, "转录异常中断：${e.javaClass.simpleName} ${e.message}")
            return Result.failure(Data.Builder().putString(KEY_ERROR, e.message ?: e.javaClass.simpleName).build())
        }

        return when (outcome) {
            is TranscriptOutcome.Committed -> {
                Log.i(TAG, "转录成功：句子 ${outcome.sentences}，段 ${outcome.segments}，" +
                    "丢弃块 ${outcome.rejectedChunks}，硬切边界 ${outcome.guessedBoundaries}")
                Result.success()
            }

            is TranscriptOutcome.Failed -> {
                Log.w(TAG, "转录失败（${outcome.failure}）：${outcome.message}")
                // 返回 success 而不是 failure：失败**已经写进库里那一行**（唯一事实来源，FR-507 的
                // 「不自动重试」由用户点重试体现）；WorkManager 的 Result.failure 会把唯一名链条判成
                // failed，后面 APPEND_OR_REPLACE 排上来的人会被连带处理，链条状态我们不需要它记。
                Result.success()
            }

            // 用户点了取消：库里那行已经是 CANCELLED（是 `cancel()` 改的，不是这里），worker 收工。
            // 这套 API 没有 Result.cancelled()，而取消路径上系统不采信返回值，给 success 不说谎
            TranscriptOutcome.Cancelled -> Result.success()

            // 这一行已经被别人（启动恢复 / 取消）结算掉了，重跑没有意义
            is TranscriptOutcome.Skipped -> {
                Log.i(TAG, "跳过：${outcome.reason}")
                Result.success()
            }
        }
    }

    private fun notificationManager(): NotificationManager =
        applicationContext.getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.notification_channel_transcript),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /**
     * 进度文案只显示「已识别 N 段」，不显示百分比：总段数要等解码跑完才知道，
     * 编一个分母就是假进度（FR-507，`TranscriptJobRow.progress` 在分母为 0 时给 null 是同一个理由）。
     */
    private fun notification(bookTitle: String, doneSegments: Int?) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            // 小图标暂时复用 App 图标：真正的「转录中」图标属于 Phase 7 的图标打磨
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(bookTitle)
            .setContentText(
                doneSegments?.let { applicationContext.getString(R.string.transcript_notification_progress, it) }
                    ?: applicationContext.getString(R.string.transcript_notification_preparing)
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun missingInput(key: String) =
        Data.Builder().putString(KEY_ERROR, "工作请求的输入数据缺少 $key").build()

    companion object {
        private const val TAG = "EchoMindTranscriptWork"
        const val KEY_JOB_ID = "jobId"
        const val KEY_AUDIO_ID = "audioId"
        const val KEY_ERROR = "error"
        const val CHANNEL_ID = "echomind_transcript"

        /** Android 14+ 前台服务必须显式声明类型；转录是「把音频同步成本机的一份字幕」，用 dataSync */
        private val FGS_TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
}

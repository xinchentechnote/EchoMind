package com.xinchen.echomind.data.repo

import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

/**
 * WMA 在后台逐本转码，不占用书架导入的 busy 生命周期。
 *
 * 输出先写 `.part`，转码成功后才原子替换正式 m4a；同一本书同一时刻最多一个任务。
 * [onPrepared] 返回 false 表示书已被删除或 URI 已变化，此时立即清掉刚生成的产物。
 */
class AsyncWmaImportPreparer(
    private val scope: CoroutineScope,
    private val store: WmaPlaybackStore,
    private val transcode: suspend (uri: String, output: java.io.File, ext: String) -> Long,
    /** 转码目标扩展名（D42："m4a" 默认 / "mp3" 用户可选），schedule 时取一次，任务内恒定 */
    private val targetExt: () -> String = { "m4a" },
    private val onPrepared: suspend (audioId: String, uri: String, durationMs: Long) -> Boolean,
    private val onFailure: (audioId: String, error: Throwable) -> Unit = { _, _ -> },
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WmaImportPreparer {

    private data class RunningJob(val generation: String, val job: Job)

    private val jobs = ConcurrentHashMap<String, RunningJob>()

    override fun schedule(audioId: String, uri: String, force: Boolean): Boolean = synchronized(jobs) {
        if (!force && store.isReady(audioId)) return false
        val running = jobs[audioId]
        if (!force && running?.job?.isActive == true) return false
        if (force) {
            // 同 URI 的内容可能已变化：旧产物与仍在阻塞式转码中的旧任务都必须失效。
            jobs.remove(audioId)?.job?.cancel()
            store.delete(audioId)
        } else {
            // 上次进程被系统杀掉时协程没有 finally 机会；新任务接管前清掉遗留临时文件。
            store.deleteParts(audioId)
        }

        val generation = UUID.randomUUID().toString()
        val ext = targetExt()
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            val part = store.partFileOf(audioId, generation, ext)
            store.prepareDir()
            try {
                val durationMs = transcode(uri, part, ext)
                coroutineContext.ensureActive()
                check(durationMs > 0) { "WMA 转码没有返回有效时长：$durationMs ms" }
                val committed = synchronized(jobs) {
                    if (jobs[audioId]?.generation == generation) {
                        store.commit(audioId, generation)
                        true
                    } else {
                        false
                    }
                }
                if (committed && !onPrepared(audioId, uri, durationMs)) {
                    synchronized(jobs) {
                        if (jobs[audioId]?.generation == generation) store.delete(audioId)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(audioId, error)
            } finally {
                part.delete()
                synchronized(jobs) {
                    if (jobs[audioId]?.generation == generation) jobs.remove(audioId)
                }
            }
        }
        jobs[audioId] = RunningJob(generation, job)
        job.start()
        true
    }

    override fun cancel(audioId: String) {
        synchronized(jobs) {
            jobs.remove(audioId)?.job?.cancel()
            store.delete(audioId)
        }
    }
}

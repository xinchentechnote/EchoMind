package com.xinchen.echomind.asr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.core.conf.PlaybackPolicy
import com.xinchen.echomind.core.time.TimeSource
import com.xinchen.echomind.data.repo.AudioRepository
import com.xinchen.echomind.data.repo.TreeGrantStore
import com.xinchen.echomind.domain.asr.AsrChunkPlanner
import com.xinchen.echomind.domain.asr.SentenceSplitter
import com.xinchen.echomind.domain.asr.TokenTimingMapper
import com.xinchen.echomind.domain.asr.TranscriptQuality
import com.xinchen.echomind.domain.model.RecognizedSegment
import com.xinchen.echomind.domain.model.SpeechSpan
import com.xinchen.echomind.domain.model.SubtitleSegment
import com.xinchen.echomind.domain.model.TokenTiming
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptJobStatus
import com.xinchen.echomind.domain.model.TranscriptModelRef
import com.xinchen.echomind.domain.model.TranscriptionTarget
import com.xinchen.echomind.domain.transcript.LiveTranscriptStore
import com.xinchen.echomind.domain.transcript.TranscriptRepository
import java.io.File

/**
 * 一轮转录从头跑到尾（`docs/asr-design.md` §5 的编排层，Phase 6 第 4b 项）。
 *
 * 六环串起来：`PcmDecoder` → `VadSegmenter` → `AsrChunkPlanner` → `SherpaOnnxEngine.recognize`
 * → `SentenceSplitter` → `commitSubtitles`。**全程阻塞**，调用方给后台线程；唯一的协程接缝是进度——
 * `PcmDecoder` 的 sink 不是 suspend，而「识别必须紧跟解码」这个顺序正是内存峰值受控的前提（NFR-05：
 * 攒着一整本书的 PCM 再识别，一小时音频就是 230 MB），所以进度用同步回调 [run] 的 [onProgress] 传出去。
 *
 * 三条不写在注释里就会被人悄悄改掉的规矩：
 * 1. **不合格的字直接丢，不补时间**（§8 第一行）。`TokenTimingMapper.Mismatch` 抛出来的那块没有可用时间，
 *    均分填洞是 AGENTS.md §5 禁止的行为；丢弃比例过高时整本标 `OUTPUT_UNRELIABLE`，而不是交一份满是洞的「成功」。
 * 2. **状态跃迁全靠仓储的守卫**。`markRunning` 返回 false 有两种：这一行已经被取消/已结算
 *    （进程被杀后 WorkManager 会重跑 worker，而库里那行已是 FAILED），或者**别的任务正占着唯一的车道**
 *    （D36：一份 int8 模型 243 MB，同时开两份不是慢而是 OOM）。两种都立刻收手、一行都不写，
 *    而且收手发生在 `SherpaOnnxEngine.open()` **之前**——这就是它排在装载前唯一的理由。
 * 3. **进度分母在 VAD 跑完之前是未知的**（段数只能数出来），所以 `markRunning` 先写 0，
 *    UI 那侧 `progress == null` 显示「已识别 N 段」而不是 0%（FR-507；假进度比没有进度更糟）。
 */
class TranscriptCoordinator(
    private val context: Context,
    private val audioRepository: AudioRepository,
    private val grants: TreeGrantStore,
    private val repository: TranscriptRepository,
    private val modelsRoot: File,
    private val clock: TimeSource,
    /**
     * 实时预览的落点（「首次播放实时转录并显示」）。每识别完一个 VAD 段就把该段切出的句子追加进去，
     * 播放页边转边显示；null（部分测试场景）= 不发布，其余行为不变。
     */
    private val liveStore: LiveTranscriptStore? = null,
) {

    /**
     * 跑 [jobId] 这一行。返回值只是给调用方（worker、测试）看的报告；
     * 库里该写的 SUCCESS/FAILED 在本函数里已经写完，不留「忘了调 markFailed」这种失误空间。
     *
     * @param isCancelled 每收到一块解码结果检查一次；返回 true 就停，任务状态由点取消的那一方改
     * @param onProgress 节流后的进度回调（间隔 ≥ [PlaybackPolicy.POSITION_FLUSH_INTERVAL_MS]，AGENTS.md §4）
     */
    suspend fun run(
        jobId: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (doneSegments: Int, totalSegments: Int) -> Unit = { _, _ -> },
    ): TranscriptOutcome {
        val job = repository.jobById(jobId)
        val target = job?.audioId?.let { audioRepository.transcriptionTarget(it) }
        if (job == null || target == null) {
            // 删书会级联带走任务行（database-design.md §6），所以这里通常是「用户边等边删」
            return TranscriptOutcome.Skipped("任务 $jobId 或它对应的书已经不在了")
        }
        if (job.status != TranscriptJobStatus.Queued) {
            return TranscriptOutcome.Skipped("任务 $jobId 的状态是 ${job.status}，不该再跑推理")
        }
        if (!target.isFilePresent) {
            return fail(jobId, TranscriptFailure.DecodeFailed, "文件已不在原位置：${target.title}")
        }
        if (target.needsTreeGrant) {
            // 查的是**树** uri：可持久化授权发在树上，文档 uri 拿去比必然 false（见 TranscriptionTarget.treeUri）
            val treeUri = target.treeUri
            if (treeUri == null || !grants.hasReadGrant(treeUri)) {
                return fail(jobId, TranscriptFailure.PermissionRevoked, "文件夹授权已失效，请重新授权后重试：${treeUri ?: target.uri}")
            }
        }
        val vadModel = vadModelFile() ?: return fail(
            jobId,
            TranscriptFailure.ModelMissing,
            "VAD 模型缺失或与清单不符（${ModelCatalog.SILERO_VAD_ID}），请先在模型页准备好",
        )

        // 「自动选模型」：任务行记的是 auto 时，用 SenseVoice 听开头 30 s 判语言，
        // 再把行上的 model_id/version 改写成最终模型（zh/yue → Paraformer，其余 → SenseVoice，D41）。
        // 探测放在 markRunning **之前**：行还没开始跑，改写才是合法的；判不出就落默认 Paraformer。
        val requested = TranscriptModelRef(id = job.modelId, version = job.modelVersion)
        val spec = if (requested.id == ModelCatalog.AUTO_MODEL_ID) {
            val probedText = probeTranscript(target, isCancelled)
            val resolved = ModelRouting.resolve(probedText)
            runCatching {
                repository.updateModel(
                    jobId,
                    TranscriptModelRef(id = resolved.id, version = ModelCatalog.refOf(resolved).version),
                )
            }
            resolved
        } else {
            ModelCatalog.specForFinal(requested)
        }

        if (!repository.markRunning(jobId, 0)) {
            return TranscriptOutcome.Skipped(
                "任务 $jobId 没能进入运行态（已被取消、已结算，或**别的任务正占着模型句柄**），本轮收手",
            )
        }

        val engine = try {
            SherpaOnnxEngine.open(modelsRoot, spec)
        } catch (e: AsrModelNotReadyException) {
            return fail(jobId, TranscriptFailure.ModelMissing, e.message ?: "识别模型不可用")
        }
        val run = try {
            Attempt(engine = engine, vadModel = vadModel)
        } catch (e: Exception) {
            engine.release()
            return fail(jobId, TranscriptFailure.ModelMissing, "VAD 打不开（${vadModel.path}）：${e.javaClass.simpleName} ${e.message}")
        }
        val outcome: TranscriptOutcome = try {
            run.recognizeAll(target, isCancelled, onProgress)
            if (run.wasCancelled) TranscriptOutcome.Cancelled else settle(jobId, run)
        } catch (e: PcmDecodeException) {
            fail(jobId, TranscriptFailure.DecodeFailed, e.message ?: "解码失败")
        } catch (e: OutOfMemoryError) {
            fail(jobId, TranscriptFailure.OutOfMemory, "内存不足，转录中止（${run.summaryLine()}）：${e.message}")
        } finally {
            run.releaseAll()
            // 实时预览退场，所有结局一致：提交场景库里已是新一轮字幕（Room 流紧随其后），失败/取消本就不该留
            liveStore?.clear(target.audioId)
        }
        return outcome
    }

    /** 解码 → VAD → 逐块识别。VAD 每吐一段就立刻识别完，所以同时在内存里的只有当前这一段（≤20 s ≈ 1.28 MB）。 */
    private fun Attempt.recognizeAll(
        target: TranscriptionTarget,
        isCancelled: () -> Boolean,
        onProgress: (Int, Int) -> Unit,
    ) {
        val sink = PcmChunkSink { samples, _ ->
            if (isCancelled()) {
                wasCancelled = true
                false
            } else {
                vad.push(samples).forEach { utterance -> consume(utterance, target, onProgress) }
                true
            }
        }
        // .wma 走自建 libwma（D40，系统无 WMA 解码器），其余走 MediaCodec；输出契约完全一致
        decodeSummary = if (target.fileName.endsWith(".wma", ignoreCase = true)) {
            WmaPcmDecoder().decode(context, Uri.parse(target.uri), sink)
        } else {
            PcmDecoder(context).decode(Uri.parse(target.uri), sink)
        }
        if (!wasCancelled) vad.finish().forEach { utterance -> consume(utterance, target, onProgress) }
    }

    /** 一个 VAD 段：切成 ≤20 s 的块逐块识别，字攒成 [RecognizedSegment]（一个段一组候选字，§6 第 1 条）。 */
    private fun Attempt.consume(utterance: VadUtterance, target: TranscriptionTarget, onProgress: (Int, Int) -> Unit) {
        val tokens = mutableListOf<TokenTiming>()
        for (chunk in AsrChunkPlanner.plan(listOf(utterance.span))) {
            totalChunks++
            val from = (chunk.startSample - utterance.startSample).toInt()
            val slice = utterance.samples.copyOfRange(from, from + chunk.sampleCount)
            try {
                tokens += engine.recognize(chunk, slice).tokens
            } catch (mismatch: TokenTimingMapper.Mismatch) {
                rejectedChunks++
                // 时间造不出来，这一块的字就不进字幕：只记账，不猜
                Log.w(TAG, "丢弃一块（模型输出不合格）：${mismatch.message}")
            }
            if (wasCancelled) return
        }
        if (tokens.isNotEmpty()) {
            val segment = RecognizedSegment(
                startMs = AsrChunkPlanner.msOf(utterance.startSample, AudioFormats.ASR_SAMPLE_RATE),
                endMs = AsrChunkPlanner.msOf(utterance.endSample, AudioFormats.ASR_SAMPLE_RATE),
                tokens = tokens,
            )
            segments += segment
        }
        doneSegments++
        if (tokens.isNotEmpty()) {
            publishLive(target, segments.last(), doneSegments)
        }
        reportProgress(onProgress)
    }

    /**
     * 一个段识别完就切句发进实时预览（「首次播放实时转录并显示」）。
     * 切句按**单个段**做：跨段合并过度切分只有收尾的整本切分做得到，所以预览偶尔比正式字幕略碎——
     * 已知差别，收尾以 `commitSubtitles` 的整本结果为准。
     */
    private fun publishLive(target: TranscriptionTarget, segment: RecognizedSegment, doneSegments: Int) {
        val store = liveStore ?: return
        val sentences = SentenceSplitter.split(listOf(segment)).map { draft ->
            SubtitleSegment(index = 0, startMs = draft.startMs, endMs = draft.endMs, text = draft.text)
        }
        store.append(target.audioId, sentences, doneSegments)
    }

    private fun Attempt.reportProgress(onProgress: (Int, Int) -> Unit) {
        // 段数分母在 VAD 跑完前会一直涨，写「N/N」只会画出永远 100% 的假进度，所以中途 total 一律给 0
        if (clock.monotonicMs() - lastProgressWriteMs < PlaybackPolicy.POSITION_FLUSH_INTERVAL_MS) return
        lastProgressWriteMs = clock.monotonicMs()
        onProgress(doneSegments, 0)
    }

    /** 切句 + 质量闸门 + 单事务入库（§5 第 6~7 步）。 */
    private suspend fun settle(jobId: String, run: Attempt): TranscriptOutcome {
        val drafts = SentenceSplitter.split(run.segments)
        val guessed = drafts.count { it.boundaryGuess }
        Log.i(
            TAG,
            "转录收口：${run.summaryLine()}；句子 ${drafts.size}（硬切边界 $guessed 句），" +
                "产出字幕的段 ${run.segments.size}/${run.doneSegments}",
        )
        if (drafts.isEmpty()) {
            return fail(
                jobId,
                TranscriptFailure.OutputUnreliable,
                "模型没有给出任何带时间的字（识别 ${run.totalChunks} 块，丢弃 ${run.rejectedChunks} 块）。" +
                    "如果这段音频本来就没有语音，这是正常结果",
            )
        }
        if (!TranscriptQuality.isUsable(totalChunks = run.totalChunks, rejectedChunks = run.rejectedChunks)) {
            return fail(
                jobId,
                TranscriptFailure.OutputUnreliable,
                "模型输出不合格而被丢弃的块过多：${run.rejectedChunks}/${run.totalChunks}" +
                    "（阈值 ${AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT}%）。不补时间、不入库",
            )
        }

        // FR-601：库里「索引序 = 时间序」是二分读字幕的前提。切句器的输出本来就单调（JVM 有性质断言），
        // 这一趟排序是把它从「实现细节」变成入库前的契约——以后谁改了切句也不至于写出乱序行。
        val segments = drafts.sortedBy { draft -> draft.startMs }.mapIndexed { index, draft ->
            SubtitleSegment(index = index, startMs = draft.startMs, endMs = draft.endMs, text = draft.text)
        }
        // 收尾这一次不节流：最后的数字必须落库，否则进度条停在 90% 而字幕已经在库里
        repository.updateProgress(jobId, doneSegments = run.doneSegments, totalSegments = run.doneSegments)
        val written = repository.commitSubtitles(jobId, segments)
        return TranscriptOutcome.Committed(
            sentences = written,
            segments = run.doneSegments,
            rejectedChunks = run.rejectedChunks,
            guessedBoundaries = guessed,
        )
    }

    /**
     * 语言探测（D41 自动路由）：SenseVoice 听开头 ≤30 s，把**转写文本**交给 ModelRouting 按字符系别路由
     * （模型自报的语言标签实测不可靠，见 D41 打样）。探测失败返回 null（落默认 Paraformer），
     * 探测挂了不该挡住一次本来合法的转录。
     */
    private suspend fun probeTranscript(target: TranscriptionTarget, isCancelled: () -> Boolean): String? {
        val engine = try {
            SherpaOnnxEngine.open(modelsRoot, ModelCatalog.senseVoice)
        } catch (e: Exception) {
            Log.w(TAG, "语言探测引擎装载失败，按默认模型继续：${e.message}")
            return null
        }
        val result: String? = try {
            val transcript = StringBuilder()
            val collected = mutableListOf<FloatArray>()
            var total = 0
            val limit = 30 * AudioFormats.ASR_SAMPLE_RATE
            PcmDecoder(context).decode(Uri.parse(target.uri)) { samples, _ ->
                if (isCancelled()) return@decode false
                collected += samples
                total += samples.size
                if (total >= limit) {
                    val flat = FloatArray(total)
                    var offset = 0
                    for (c in collected) {
                        System.arraycopy(c, 0, flat, offset, c.size)
                        offset += c.size
                    }
                    val chunks = AsrChunkPlanner.plan(listOf(SpeechSpan(startSample = 0, sampleCount = total)))
                    for (chunk in chunks) {
                        val from = chunk.startSample.toInt()
                        transcript.append(engine.recognize(chunk, flat.copyOfRange(from, from + chunk.sampleCount)).text)
                    }
                    false // 只听开头 30 s
                } else {
                    true
                }
            }
            Log.i(TAG, "语言探测：${target.fileName} → ${transcript.take(60)}")
            transcript.toString()
        } catch (e: Exception) {
            Log.w(TAG, "语言探测失败，按默认模型继续：${e.message}")
            null
        } finally {
            engine.release()
        }
        return result
    }

    /**
     * VAD 模型按清单核对后给出文件（0.64 MB，整份哈希算得起）。
     * 识别模型不在这里核对——243 MB 的整份 SHA-256 只该算一次，`SherpaOnnxEngine.open` 内部已经核对过。
     */
    private fun vadModelFile(): File? {
        val verification = ModelVerifier.verify(ModelCatalog.sileroVad, modelsRoot, clock.wallMs())
        if (!verification.isReady) {
            Log.w(TAG, "VAD 模型不合格：${verification.failures.joinToString { ModelManager.describeFailure(it) }}")
            return null
        }
        return File(ModelVerifier.dirOf(modelsRoot, ModelCatalog.sileroVad), ModelCatalog.SILERO_VAD_MODEL_FILE)
    }

    private suspend fun fail(jobId: String, failure: TranscriptFailure, message: String): TranscriptOutcome {
        Log.w(TAG, "转录失败（$failure）：$message")
        repository.markFailed(jobId, failure, message)
        return TranscriptOutcome.Failed(failure, message)
    }

    /**
     * 一次跑的中间状态。做成一个对象只为两件事：`release()` 成对（AGENTS.md §5），
     * 以及把「VAD 与引擎跟着这次跑走」写死——识别引擎全 App 同一时刻只许一个句柄，装载要花几秒，不能逐段开。
     */
    private class Attempt(val engine: SherpaOnnxEngine, vadModel: File) {
        val vad: VadSegmenter = VadSegmenter(vadModel)
        val segments = mutableListOf<RecognizedSegment>()
        var totalChunks = 0
        var rejectedChunks = 0
        var doneSegments = 0
        var wasCancelled = false
        var decodeSummary: PcmDecodeSummary? = null

        /** 0 = 「第一次检查就该写」：单调时钟是开机至今的毫秒数，真跑起来必然大于节流间隔。 */
        var lastProgressWriteMs = 0L

        fun releaseAll() {
            runCatching { vad.release() }
            runCatching { engine.release() }
        }

        fun summaryLine(): String {
            val summary = decodeSummary ?: return "解码没走完，段 $doneSegments 个，块 $totalChunks 个，丢弃 $rejectedChunks 个"
            return "解码 ${summary.outputSamples} 样本（${summary.sourceSampleRate} Hz/${summary.channelCount} ch → " +
                "${AudioFormats.ASR_SAMPLE_RATE} Hz，容器时长 ${summary.durationMs} ms），" +
                "语音段 $doneSegments 个，块 $totalChunks 个，丢弃 $rejectedChunks 个"
        }
    }

    companion object {
        private const val TAG = "EchoMindTranscript"
    }
}

/**
 * 一轮转录的结局报告（[TranscriptCoordinator.run] 的返回值）。
 *
 * 库里的状态在 run 里已经写完，这里的值只给 worker 翻译成 WorkManager 的 Result、
 * 给测试断言「到底走到哪一步」用；UI 不许读它（UI 只读 `TranscriptRepository` 的 Flow）。
 */
sealed interface TranscriptOutcome {

    /** 这一行不归我跑（行没了、状态不是 QUEUED、被取消或被重启结算过）：什么都没写 */
    data class Skipped(val reason: String) : TranscriptOutcome

    data object Cancelled : TranscriptOutcome

    /**
     * 字幕已入库。
     *
     * [rejectedChunks] 是「模型输出不合格而被丢掉的块」，[guessedBoundaries] 是硬切边界的句子数
     * （`SubtitleDraft.boundaryGuess`，不落库，见 `docs/asr-design.md` §5 第 6 步）——
     * 这两个数必须能被调用方看到，否则「成功」二字就把缺失藏起来了。
     */
    data class Committed(
        val sentences: Int,
        val segments: Int,
        val rejectedChunks: Int,
        val guessedBoundaries: Int,
    ) : TranscriptOutcome

    data class Failed(val failure: TranscriptFailure, val message: String) : TranscriptOutcome
}

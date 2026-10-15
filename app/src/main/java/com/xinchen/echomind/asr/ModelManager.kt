package com.xinchen.echomind.asr

import com.xinchen.echomind.core.time.TimeSource
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** 取回失败：候选源全试过，仍拿不到一份校验通过的文件。 */
class ModelDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 本地导入失败：文件拿得到，但对不上清单（大小或 SHA-256），或者读不出来。 */
class ModelImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** 正在下载的那个文件（一个模型包内串行取文件，所以同时只有一个）。 */
data class FileDownload(
    val fileName: String,
    val receivedBytes: Long,
    /** 服务端没给 Content-Length 时是 -1，UI 必须按「总数未知」显示，不许画成 0% */
    val totalBytes: Long,
)

/** 一个模型包在界面上的完整状态。UI 只读这一份，不自己拼路径、不自己算大小。 */
data class ModelStatus(
    val spec: ModelSpec,
    /** null 表示还没核对过（进程刚起来时就是 null） */
    val verification: ModelVerification?,
    val isDownloading: Boolean = false,
    /** 正在从本地文件导入。与下载分开标，因为界面上这两句话不能说成同一件 */
    val isImporting: Boolean = false,
    val download: FileDownload? = null,
    /**
     * 上一次**操作**（下载/导入/删除）失败的原因；就绪与否一律看 [verification]，
     * 这里不重复放核对结论，免得两个来源的说法不一致。
     */
    val error: String? = null,
) {
    val isReady: Boolean get() = verification?.isReady == true
}

/**
 * 模型的下载、校验、删除（FR-510，`docs/asr-design.md` §7）。
 *
 * 三条来自需求的硬规则：
 * 1. 先落 `.part`，**大小与 SHA-256 都对上才改名**，任何失败都删掉半截文件；
 *    否则下一次核对会看到一份「存在但坏掉」的模型，推理直接拿它跑。
 * 2. 只有核对通过的文件才算就绪，不做「大小差不多就用」。
 * 3. 每个取回成功的文件向 `download-provenance.properties` 追加来源（URL、校验值、字节数、时间、
 *    校验值是不是官方的），出问题时能在设备上还原这个文件是从哪来的。
 *
 * 断点续传只到**文件级**：已核对通过的文件跳过，中断的半截文件下次从头重下
 * （没用 HTTP Range：清单的哈希是整份文件的，半截比对没有意义）。
 */
class ModelManager(
    private val modelsRoot: File,
    private val timeSource: TimeSource,
    /** 要管理的模型包，默认就是清单全集；单测传假清单进来，才不至于逼着测试下 243 MB */
    private val specs: List<ModelSpec> = ModelCatalog.all,
    private val fetcher: ModelFetcher = HttpFetcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _statuses = MutableStateFlow(specs.map { ModelStatus(spec = it, verification = null) })
    val statuses: StateFlow<List<ModelStatus>> = _statuses.asStateFlow()

    /** 逐个模型包核对本机文件。243 MB 的哈希要一两秒，所以走 IO 调度器。 */
    suspend fun refresh(): List<ModelStatus> {
        val verifications = withContext(ioDispatcher) {
            val context = currentCoroutineContext()
            specs.map {
                context.ensureActive()
                ModelVerifier.verify(it, modelsRoot, timeSource.wallMs())
            }
        }
        return applyVerifications(verifications, clearError = false)
    }

    /**
     * 取回 [spec] 里所有还没合格的文件，返回最终核对结果。
     * 失败抛 [ModelDownloadException]，原因同时写进 [statuses] 的 `error`。
     */
    suspend fun download(spec: ModelSpec): ModelVerification = withContext(ioDispatcher) {
        val dir = ModelVerifier.dirOf(modelsRoot, spec)
        if (!dir.exists() && !dir.mkdirs()) throw ModelDownloadException("无法创建模型目录：$dir")
        updateStatus(spec.id) { it.copy(isDownloading = true, download = null, error = null) }
        try {
            for (fileSpec in spec.files) {
                if (ModelVerifier.isFileValid(File(dir, fileSpec.fileName), fileSpec)) continue
                fetchWithFallbacks(spec, dir, fileSpec)
            }
            val verification = ModelVerifier.verify(spec, modelsRoot, timeSource.wallMs())
            applyVerifications(listOf(verification), clearError = true)
            if (!verification.isReady) {
                throw ModelDownloadException(
                    "下载后仍不合格：${verification.failures.joinToString("；") { describeFailure(it) } }",
                )
            }
            verification
        } catch (e: CancellationException) {
            updateStatus(spec.id) { it.copy(isDownloading = false, download = null) }
            throw e
        } catch (e: IOException) {
            updateStatus(spec.id) { it.copy(isDownloading = false, download = null, error = e.message) }
            throw e
        }
    }

    /**
     * 从本地文件导入一个模型文件（FR-510 的「本地文件导入」那半边）。
     *
     * 规矩与下载**完全一致**：先落 `.part`、比大小、算整份 SHA-256、都过了才改名，任何失败都删掉半截文件。
     * 这条不能松——导入进来的文件下一步就会被 `SherpaOnnxEngine.open()` 装载，
     * 「名字对但内容坏」的模型比没有模型更糟（它会跑出看起来合理的错字幕）。
     *
     * [open] 由调用方给（Android 侧是 `contentResolver.openInputStream(uri)`），
     * 这个类因此不碰任何 `android.*`，导入路径也能在 JVM 单测里跑。
     * [source] 只用于写来源记录（如 SAF 的 uri），不参与任何判定。
     *
     * 与 [download] 的一处有意差别：**按单个文件结算**。导入成功一个就算成功，
     * 即使这个包还缺别的文件也不抛——用户可能就是把两个文件分两次从电脑拷过来。
     * 返回的 [ModelVerification] 里如实写着还缺什么。
     */
    suspend fun importFile(
        spec: ModelSpec,
        fileName: String,
        source: String,
        open: () -> InputStream,
    ): ModelVerification = withContext(ioDispatcher) {
        val fileSpec = spec.files.firstOrNull { it.fileName == fileName }
            ?: throw ModelImportException("清单 ${spec.id} 里没有文件 $fileName，不能往这个包里塞别的东西")
        val dir = ModelVerifier.dirOf(modelsRoot, spec)
        if (!dir.exists() && !dir.mkdirs()) throw ModelImportException("无法创建模型目录：$dir")
        val part = File(dir, "$fileName.part")
        val target = File(dir, fileName)
        updateStatus(spec.id) { it.copy(isImporting = true, download = null, error = null) }
        try {
            copyAndCheck(spec, dir, fileSpec, part, target, source, open)
            val verification = ModelVerifier.verify(spec, modelsRoot, timeSource.wallMs())
            applyVerifications(listOf(verification), clearError = true)
            verification
        } catch (e: CancellationException) {
            part.delete()
            updateStatus(spec.id) { it.copy(isImporting = false, download = null) }
            throw e
        } catch (e: IOException) {
            part.delete()
            updateStatus(spec.id) { it.copy(isImporting = false, download = null, error = e.message) }
            throw e
        }
    }

    private suspend fun copyAndCheck(
        spec: ModelSpec,
        dir: File,
        fileSpec: ModelFileSpec,
        part: File,
        target: File,
        source: String,
        open: () -> InputStream,
    ) {
        val context = currentCoroutineContext()
        part.delete()
        var copied = 0L
        var lastPublishAtMs = Long.MIN_VALUE
        try {
            open().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                part.outputStream().use { output ->
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = timeSource.monotonicMs()
                        if (lastPublishAtMs == Long.MIN_VALUE || now - lastPublishAtMs >= PROGRESS_PUBLISH_INTERVAL_MS) {
                            lastPublishAtMs = now
                            updateStatus(spec.id) {
                                it.copy(download = FileDownload(fileSpec.fileName, copied, fileSpec.expectedBytes))
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ModelImportException("读不出这个文件（${fileSpec.fileName}）：${e.message}", e)
        }
        if (copied != fileSpec.expectedBytes) {
            throw ModelImportException(
                "${fileSpec.fileName} 大小不符：清单要求 ${fileSpec.expectedBytes} B，这个文件是 $copied B。" +
                    "多半是选错了文件，或者拷贝时被截断",
            )
        }
        val actualSha256 = ModelVerifier.sha256(part)
        if (!actualSha256.equals(fileSpec.expectedSha256, ignoreCase = true)) {
            throw ModelImportException(
                "${fileSpec.fileName} SHA-256 不符：清单 ${fileSpec.expectedSha256}，这个文件 $actualSha256" +
                    if (fileSpec.hasOfficialChecksum) "" else "（清单值是本机钉住的，非官方）",
            )
        }
        target.delete()
        if (!part.renameTo(target)) throw ModelImportException("无法把 ${part.name} 改名为 ${target.name}：$target")
        appendProvenance(dir, spec, fileSpec, originKey = "local_import", origin = source)
    }

    /** 删掉整个模型包目录：只动 `filesDir/models/` 下这个包，字幕与收听历史一律不碰（FR-508）。 */
    suspend fun delete(spec: ModelSpec): ModelVerification = withContext(ioDispatcher) {
        val dir = ModelVerifier.dirOf(modelsRoot, spec)
        val leftovers = mutableListOf<String>()
        spec.files.forEach { fileSpec ->
            val file = File(dir, fileSpec.fileName)
            if (file.exists() && !file.delete()) leftovers += file.name
            File(dir, "${fileSpec.fileName}.part").delete()
        }
        File(dir, PROVENANCE_FILE_NAME).delete()
        if (dir.exists() && !dir.delete()) leftovers += dir.name
        val verification = ModelVerifier.verify(spec, modelsRoot, timeSource.wallMs())
        applyVerifications(listOf(verification), clearError = true)
        if (leftovers.isNotEmpty()) {
            updateStatus(spec.id) { it.copy(error = "删除失败：${leftovers.joinToString()}") }
        }
        verification
    }

    private suspend fun fetchWithFallbacks(spec: ModelSpec, dir: File, fileSpec: ModelFileSpec) {
        val reasons = mutableListOf<String>()
        var lastCause: Throwable? = null
        for (url in fileSpec.candidateUrls) {
            try {
                fetchAndInstall(spec, dir, fileSpec, url)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastCause = e
                reasons += "$url → ${e.message}"
            }
        }
        throw ModelDownloadException(
            "取回 ${fileSpec.fileName} 失败，试过 ${fileSpec.candidateUrls.size} 个地址：\n${reasons.joinToString("\n")}",
            lastCause,
        )
    }

    private suspend fun fetchAndInstall(spec: ModelSpec, dir: File, fileSpec: ModelFileSpec, url: String) {
        val part = File(dir, "${fileSpec.fileName}.part")
        val target = File(dir, fileSpec.fileName)
        part.delete()
        val context = currentCoroutineContext()
        var lastPublishAtMs = Long.MIN_VALUE
        try {
            fetcher.fetch(url, part) { received, total ->
                context.ensureActive()
                val now = timeSource.monotonicMs()
                val isFinalChunk = total >= 0L && received == total
                val elapsedSinceLastPublish = if (lastPublishAtMs == Long.MIN_VALUE) {
                    PROGRESS_PUBLISH_INTERVAL_MS
                } else {
                    now - lastPublishAtMs
                }
                if (elapsedSinceLastPublish >= PROGRESS_PUBLISH_INTERVAL_MS || isFinalChunk) {
                    lastPublishAtMs = now
                    updateStatus(spec.id) {
                        it.copy(download = FileDownload(fileSpec.fileName, received, total))
                    }
                }
            }
            if (part.length() != fileSpec.expectedBytes) {
                throw ModelDownloadException(
                    "${fileSpec.fileName} 大小不符：期望 ${fileSpec.expectedBytes} B，实收 ${part.length()} B",
                )
            }
            val actualSha256 = ModelVerifier.sha256(part)
            if (!actualSha256.equals(fileSpec.expectedSha256, ignoreCase = true)) {
                throw ModelDownloadException(
                    "${fileSpec.fileName} SHA-256 不符：清单 ${fileSpec.expectedSha256}，实收 $actualSha256" +
                        if (fileSpec.hasOfficialChecksum) "" else "（清单值是本机钉住的，非官方）",
                )
            }
            target.delete()
            if (!part.renameTo(target)) {
                throw ModelDownloadException("无法把 ${part.name} 改名为 ${target.name}：$target")
            }
            appendProvenance(dir, spec, fileSpec, originKey = "url", origin = url)
        } finally {
            part.delete()
        }
    }

    /**
     * 追加来源记录，`key=值` 平铺。[originKey] 是 `url`（下载）或 `local_import`（本地导入）——
     * 两者不能写成同一个键，否则事后在设备上分不清这份文件是从哪来的。
     * 不用 `java.util.Properties.store`：它的 Writer 重载与 `%02x` 之类 API 有 minSdk 坑，
     * 直接 `appendText`（UTF-8）最省事也最好核对。
     */
    private fun appendProvenance(dir: File, spec: ModelSpec, fileSpec: ModelFileSpec, originKey: String, origin: String) {
        val name = fileSpec.fileName
        val line = buildString {
            append("$name.$originKey=").append(origin).append('\n')
            append("$name.bytes=").append(fileSpec.expectedBytes).append('\n')
            append("$name.sha256=").append(fileSpec.expectedSha256).append('\n')
            append("$name.checksum=").append(if (fileSpec.hasOfficialChecksum) "official" else "pinned-by-first-download").append('\n')
            append("$name.model_id=").append(spec.id).append('\n')
            append("$name.fetched_at_ms=").append(timeSource.wallMs()).append('\n')
        }
        File(dir, PROVENANCE_FILE_NAME).appendText(line)
    }

    /**
     * 把核对结果写进状态。
     *
     * [clearError] 只在「一次操作做完了」时传 true：下载/删除结束后旧的失败原因就该翻篇，
     * 缺哪个文件一律由 [ModelVerification.failures] 说话，不重复塞进 `error`，
     * 否则删除成功后状态里会挂一条「model.onnx 不存在」这种看起来像报错的描述。
     */
    private fun applyVerifications(
        verifications: List<ModelVerification>,
        clearError: Boolean,
    ): List<ModelStatus> {
        val byId = verifications.associateBy { it.spec.id }
        _statuses.update { current ->
            current.map { status ->
                val verification = byId[status.spec.id] ?: return@map status
                status.copy(
                    verification = verification,
                    isDownloading = if (clearError) false else status.isDownloading,
                    download = null,
                    error = if (clearError) null else status.error,
                )
            }
        }
        return _statuses.value
    }

    private fun updateStatus(specId: String, transform: (ModelStatus) -> ModelStatus) {
        _statuses.update { list -> list.map { if (it.spec.id == specId) transform(it) else it } }
    }

    companion object {
        const val PROVENANCE_FILE_NAME = "download-provenance.properties"

        /** 243 MB 按 64 KB 分块会有 ~3700 次回调，全推给 StateFlow 会让订阅者疯狂重组 */
        private const val PROGRESS_PUBLISH_INTERVAL_MS = 250L

        /** 本地导入时的拷贝缓冲：与哈希计算同宽，243 MB 走内存流也只是一趟 */
        private const val COPY_BUFFER_BYTES = 64 * 1024

        fun describeFailure(failure: FileFailure): String = when (failure) {
            is FileFailure.Missing -> "${failure.fileName} 不存在"
            is FileFailure.SizeMismatch ->
                "${failure.fileName} 大小不符（期望 ${failure.expectedBytes} B，实际 ${failure.actualBytes} B）"

            is FileFailure.HashMismatch ->
                "${failure.fileName} SHA-256 不符（清单 ${failure.expectedSha256}，实际 ${failure.actualSha256}）"
        }
    }
}

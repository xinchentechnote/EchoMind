package com.xinchen.echomind.ui.settings

import com.xinchen.echomind.asr.FileFailure
import com.xinchen.echomind.asr.ModelFileSpec
import com.xinchen.echomind.asr.ModelRole
import com.xinchen.echomind.asr.ModelSpec
import com.xinchen.echomind.asr.ModelStatus
import com.xinchen.echomind.asr.ModelVerification
import com.xinchen.echomind.asr.FileDownload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模型不可用时该给用户什么动作」这条要在 JVM 里断言（FR-510 / FR-901，与 `TranscriptDisplayTest`
 * 同一个理由）。核心不是排版，是**不许在没证据时下结论**：没核对过就说「还没核对」，
 * 总大小未知就不报百分比，官方没给校验值就承认是本机钉的。
 */
class ModelDisplayTest {

    // -- 状态词 ------------------------------------------------------------------

    @Test
    fun `没核对过就明说还没核对`() {
        val status = ModelStatus(spec = spec(), verification = null)
        assertEquals("还没核对", ModelDisplay.statusLabel(status))
        // 还没核对 ≠ 不可用：文件可能都在，只是这一轮进程还没算完哈希，所以下载按钮照常给
        assertTrue(ModelDisplay.canDownload(status))
    }

    @Test
    fun `核对过了就绪与不合格措辞分开`() {
        val ready = ModelStatus(spec = spec(), verification = verification())
        assertEquals("已就绪", ModelDisplay.statusLabel(ready))
        assertNull(ModelDisplay.problemLabel(ready.verification))

        val broken = ModelStatus(
            spec = spec(),
            verification = verification(FileFailure.Missing("model.onnx")),
        )
        assertEquals("不可用", ModelDisplay.statusLabel(broken))
        assertEquals("model.onnx 不存在", ModelDisplay.problemLabel(broken.verification))
    }

    @Test
    fun `下载中与导入中的状态词优先于核对结论`() {
        val downloading = ModelStatus(
            spec = spec(),
            verification = verification(),
            isDownloading = true,
            download = FileDownload("model.onnx", 1, 10),
        )
        assertEquals("下载中", ModelDisplay.statusLabel(downloading))
        val importing = ModelStatus(spec = spec(), verification = verification(), isImporting = true)
        assertEquals("导入中", ModelDisplay.statusLabel(importing))
    }

    // -- 进度 --------------------------------------------------------------------

    @Test
    fun `总数未知只报已收字节不报百分比`() {
        val status = ModelStatus(
            spec = spec(),
            verification = null,
            isDownloading = true,
            download = FileDownload("model.onnx", receivedBytes = 100, totalBytes = -1),
        )
        val label = ModelDisplay.progressLabel(status)!!
        assertTrue(label, label.contains("已收 100 B"))
        assertTrue(label, label.contains("不报百分比"))
        assertFalse(label, label.contains("%"))
        assertNull(ModelDisplay.progressFraction(status))
    }

    @Test
    fun `总数已知给百分比且比例夹在0到1`() {
        val status = ModelStatus(
            spec = spec(),
            verification = null,
            isDownloading = true,
            download = FileDownload("model.onnx", receivedBytes = 512, totalBytes = 1024),
        )
        assertTrue(ModelDisplay.progressLabel(status)!!.contains("50%"))
        assertEquals(0.5f, ModelDisplay.progressFraction(status)!!)
    }

    @Test
    fun `没在下载就没有进度那一行`() {
        assertNull(ModelDisplay.progressLabel(ModelStatus(spec = spec(), verification = verification())))
    }

    // -- 按钮的有无与措辞 ----------------------------------------------------------

    @Test
    fun `就绪的包不给下载按钮忙时不给任何按钮`() {
        val ready = ModelStatus(spec = spec(), verification = verification())
        assertFalse(ModelDisplay.canDownload(ready))
        assertTrue(ModelDisplay.canImport(ready))
        assertTrue(ModelDisplay.canDelete(ready))

        val busy = ModelStatus(
            spec = spec(),
            verification = verification(FileFailure.Missing("model.onnx")),
            isDownloading = true,
        )
        assertFalse(ModelDisplay.canDownload(busy))
        assertFalse(ModelDisplay.canImport(busy))
        assertFalse(ModelDisplay.canDelete(busy))

        val broken = ModelStatus(
            spec = spec(),
            verification = verification(FileFailure.Missing("model.onnx")),
        )
        assertTrue(ModelDisplay.canDownload(broken))
    }

    @Test
    fun `部分文件在就说继续下载整包缺就说下载并带大小`() {
        val file = fileSpec(name = "model.onnx", bytes = 243L)
        val token = fileSpec(name = "tokens.txt", bytes = 75L)
        val spec = ModelSpec(id = "fake", displayName = "假模型", role = ModelRole.Recognizer, files = listOf(file, token))

        val wholeMissing = ModelStatus(spec = spec, verification = verification(failures = listOf(
            FileFailure.Missing(file.fileName), FileFailure.Missing(token.fileName),
        )))
        assertTrue(ModelDisplay.downloadButtonLabel(wholeMissing).startsWith("下载（"))
        assertTrue(ModelDisplay.downloadButtonLabel(wholeMissing).contains("318 B"))

        val partial = ModelStatus(spec = spec, verification = verification(failures = listOf(
            FileFailure.Missing(file.fileName),
        )))
        assertTrue(ModelDisplay.downloadButtonLabel(partial).startsWith("继续下载（"))
    }

    @Test
    fun `删除按钮的措辞跟着核对状态走`() {
        val ready = ModelStatus(spec = spec(), verification = verification())
        assertEquals("删除", ModelDisplay.deleteButtonLabel(ready))
        val verifiedBroken = ModelStatus(spec = spec(), verification = verification(FileFailure.Missing("model.onnx")))
        assertEquals("删除残留文件", ModelDisplay.deleteButtonLabel(verifiedBroken))
        val neverVerified = ModelStatus(spec = spec(), verification = null)
        assertEquals("删除", ModelDisplay.deleteButtonLabel(neverVerified))
    }

    // -- 诚实性标注 ----------------------------------------------------------------

    @Test
    fun `校验值来源官方与本机钉住分开说`() {
        val official = fileSpec(name = "model.onnx", bytes = 1, official = true)
        val pinned = fileSpec(name = "tokens.txt", bytes = 1, official = false)
        assertTrue(ModelDisplay.checksumLabel(official).contains("官方"))
        assertTrue(ModelDisplay.checksumLabel(pinned).contains("本机首次取回"))
        assertFalse(ModelDisplay.checksumLabel(pinned).contains("官方发布"))
    }

    @Test
    fun `来源只报主机名并交代备用地址数`() {
        val primary = fileSpec(
            name = "model.onnx",
            bytes = 1,
            url = "https://hf-mirror.com/a/model.onnx",
            backups = listOf("https://huggingface.co/a/model.onnx"),
        )
        assertEquals("来源 hf-mirror.com（另有 1 个备用地址）", ModelDisplay.sourceLabel(primary))

        val single = fileSpec(name = "vad.onnx", bytes = 1, url = "https://github.com/a/vad.onnx", backups = emptyList())
        assertEquals("来源 github.com", ModelDisplay.sourceLabel(single))

        val malformed = fileSpec(name = "x", bytes = 1, url = "::not-a-url::", backups = emptyList())
        assertEquals("来源见模型清单", ModelDisplay.sourceLabel(malformed))
    }

    @Test
    fun `删除确认文案说清删的是什么不动什么后果是什么`() {
        val spec = ModelSpec(
            id = "fake",
            displayName = "Paraformer 中文离线",
            role = ModelRole.Recognizer,
            files = listOf(fileSpec(name = "model.onnx", bytes = 243_371_218L)),
        )
        val text = ModelDisplay.deleteConfirmText(ModelStatus(spec = spec, verification = verification()))
        assertTrue(text, text.contains("Paraformer 中文离线"))
        assertTrue(text, text.contains("232 MB")) // 与清单字节数对得上（243371218 B），不是写死的好听数字
        assertTrue(text, text.contains("字幕与收听历史一律不动"))
        assertTrue(text, text.contains("转录会失败"))
    }

    @Test
    fun `导入成功的文案按包齐不齐分开说`() {
        val ready = verification()
        assertTrue(ModelDisplay.importSucceededLabel(ready, "model.onnx").contains("这个包现在齐了"))
        val missingMore = verification(FileFailure.Missing("tokens.txt"))
        val label = ModelDisplay.importSucceededLabel(missingMore, "model.onnx")
        assertTrue(label, label.contains("还差"))
        assertTrue(label, label.contains("tokens.txt 不存在"))
    }

    @Test
    fun `开头一段把离线边界说清`() {
        val note = ModelDisplay.SECTION_NOTE
        assertTrue(note, note.contains("不调任何在线识别接口"))
        assertTrue(note, note.contains("需要联网"))
        assertTrue(note, note.contains("两个包都就绪了才能开始转录"))
    }

    @Test
    fun `两个角色各有一句人话`() {
        assertTrue(ModelDisplay.roleLabel(ModelRole.Recognizer).contains("识别"))
        assertTrue(ModelDisplay.roleLabel(ModelRole.VoiceActivityDetector).contains("语音段"))
    }

    // -- 假件 --------------------------------------------------------------------

    private fun spec() = ModelSpec(
        id = "fake",
        displayName = "假模型（仅测试）",
        role = ModelRole.Recognizer,
        files = listOf(fileSpec(name = "model.onnx", bytes = 243L)),
    )

    private fun verification(vararg failures: FileFailure) = verification(failures.toList())

    private fun verification(failures: List<FileFailure>): ModelVerification {
        val s = spec()
        return ModelVerification(spec = s, dir = java.io.File("/tmp/fake-models/${s.id}"), failures = failures, verifiedAtMs = 1_000L)
    }

    private fun fileSpec(
        name: String,
        bytes: Long,
        url: String = "https://hf-mirror.com/a/$name",
        backups: List<String> = emptyList(),
        official: Boolean = true,
    ): ModelFileSpec = ModelFileSpec(
        fileName = name,
        url = url,
        backupUrls = backups,
        expectedBytes = bytes,
        officialSha256 = if (official) "a".repeat(64) else null,
        pinnedSha256 = "b".repeat(64),
    )
}

package com.xinchen.echomind.ui.settings

import com.xinchen.echomind.asr.FileDownload
import com.xinchen.echomind.asr.FileFailure
import com.xinchen.echomind.asr.ModelFileSpec
import com.xinchen.echomind.asr.ModelRole
import com.xinchen.echomind.asr.ModelSpec
import com.xinchen.echomind.asr.ModelStatus
import com.xinchen.echomind.asr.ModelVerification
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模型不可用时该给用户什么动作」这条要在 JVM 里断言（FR-510/FR-511/FR-901，与 `TranscriptDisplayTest`
 * 同一个理由）。核心不是排版，是**不许在没证据时下结论**：没核对过就说「还没核对」，
 * 总大小未知就不报百分比，官方没给校验值就承认是本机钉的。
 * 模型内置后这一节的动词是「恢复」——这里钉住**没有「下载」这个词**（谁加回来谁红）。
 */
class ModelDisplayTest {

    // -- 状态词 ------------------------------------------------------------------

    @Test
    fun `没核对过就明说还没核对`() {
        val status = ModelStatus(spec = spec(), verification = null)
        assertEquals("还没核对", ModelDisplay.statusLabel(status))
        // 还没核对 ≠ 不可用：文件可能都在，只是这一轮进程还没算完哈希，所以恢复按钮照常给
        assertTrue(ModelDisplay.canRestore(status))
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
    fun `恢复中与下载中的状态词优先于核对结论`() {
        val restoring = ModelStatus(spec = spec(), verification = verification(), isImporting = true)
        assertEquals("恢复中", ModelDisplay.statusLabel(restoring))
        val downloading = ModelStatus(
            spec = spec(),
            verification = verification(),
            isDownloading = true,
            download = FileDownload("model.onnx", 1, 10),
        )
        assertEquals("下载中", ModelDisplay.statusLabel(downloading))
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
    fun `恢复中的进度行说的是从安装包恢复`() {
        val status = ModelStatus(
            spec = spec(),
            verification = null,
            isImporting = true,
            download = FileDownload("model.onnx", receivedBytes = 512, totalBytes = 1024),
        )
        val label = ModelDisplay.progressLabel(status)!!
        assertTrue(label, label.startsWith("正在从安装包恢复"))
        assertTrue(label, label.contains("50%"))
        assertEquals(0.5f, ModelDisplay.progressFraction(status)!!)
    }

    @Test
    fun `没在恢复就没有进度那一行`() {
        assertNull(ModelDisplay.progressLabel(ModelStatus(spec = spec(), verification = verification())))
    }

    // -- 按钮的有无与措辞 ----------------------------------------------------------

    @Test
    fun `就绪的包不给恢复按钮忙时不给任何按钮`() {
        val ready = ModelStatus(spec = spec(), verification = verification())
        assertFalse(ModelDisplay.canRestore(ready))
        assertTrue(ModelDisplay.canDelete(ready))

        val busy = ModelStatus(
            spec = spec(),
            verification = verification(FileFailure.Missing("model.onnx")),
            isImporting = true,
        )
        assertFalse(ModelDisplay.canRestore(busy))
        assertFalse(ModelDisplay.canDelete(busy))

        val broken = ModelStatus(
            spec = spec(),
            verification = verification(FileFailure.Missing("model.onnx")),
        )
        assertTrue(ModelDisplay.canRestore(broken))
        assertTrue(ModelDisplay.restoreButtonLabel(broken).startsWith("从安装包恢复（"))
        assertFalse(ModelDisplay.restoreButtonLabel(broken).contains("下载"))
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
    fun `删除确认文案说清删的是什么以及恢复不需要网络`() {
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
        assertTrue(text, text.contains("从安装包恢复"))
        assertTrue(text, text.contains("不需要网络"))
    }

    @Test
    fun `恢复与删除的收尾文案都不承诺联网`() {
        val ready = ModelStatus(spec = spec(), verification = verification())
        assertTrue(ModelDisplay.restoreSucceededLabel(ready).contains("已从安装包恢复并核对通过"))
        val deleted = ModelDisplay.deletedLabel(ready)
        assertTrue(deleted, deleted.contains("从安装包恢复"))
        assertFalse(deleted, deleted.contains("下载"))
    }

    @Test
    fun `开头一段把离线边界说清且不提下载`() {
        val note = ModelDisplay.SECTION_NOTE
        assertTrue(note, note.contains("随安装包内置"))
        assertTrue(note, note.contains("不需要网络"))
        assertTrue(note, note.contains("飞行模式"))
        assertTrue(note, note.contains("两个包都就绪了才能开始转录"))
        assertFalse(note, note.contains("下载"))
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
        return ModelVerification(spec = s, dir = File("/tmp/fake-models/${s.id}"), failures = failures, verifiedAtMs = 1_000L)
    }

    private fun fileSpec(
        name: String,
        bytes: Long,
        official: Boolean = true,
    ): ModelFileSpec = ModelFileSpec(
        fileName = name,
        url = "https://hf-mirror.com/a/$name",
        backupUrls = emptyList(),
        expectedBytes = bytes,
        officialSha256 = if (official) "a".repeat(64) else null,
        pinnedSha256 = "b".repeat(64),
    )
}

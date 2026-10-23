package com.xinchen.echomind.ui.transcript

import com.xinchen.echomind.core.conf.AudioFormats
import com.xinchen.echomind.domain.model.TranscriptFailure
import com.xinchen.echomind.domain.model.TranscriptJobRow
import com.xinchen.echomind.domain.model.TranscriptJobStatus
import com.xinchen.echomind.domain.model.TranscriptPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 转录页文案（FR-507 / FR-508）。
 *
 * 这一层值得单测的不是中文措辞，而是三条诚实性规矩：**分母未知时不许出现百分比**、
 * **每种失败都得给得出一个动作**、**提示里提到的按钮真存在**。
 * 页面本身（Compose）要设备实测，这里只把纯逻辑那半边钉住。
 */
class TranscriptDisplayTest {

    @Test
    fun `产品范围只承诺普通话听书并明确排除未达标内容`() {
        val note = TranscriptDisplay.SCOPE_NOTE
        assertTrue(note, note.contains("普通话听书"))
        assertTrue(note, note.contains("英文"))
        assertTrue(note, note.contains("歌曲"))
        assertTrue(note, note.contains("暂不保证"))
    }

    private fun row(
        status: TranscriptJobStatus,
        totalSegments: Int = 0,
        doneSegments: Int = 0,
        phase: TranscriptPhase? = null,
        positionMs: Long = 0,
        durationMs: Long = 0,
    ) = TranscriptJobRow(
        id = "job-1",
        audioId = "audio-1",
        modelId = "model-under-test",
        modelVersion = "0123456789ab",
        status = status,
        totalSegments = totalSegments,
        doneSegments = doneSegments,
        phase = phase,
        positionMs = positionMs,
        durationMs = durationMs,
        failure = null,
        errorMessage = null,
        createdAtMs = 1_700_000_000_000,
        startedAtMs = null,
        finishedAtMs = null,
    )

    @Test
    fun `排队中的任务一行进度都不给`() {
        // 排队 = 一段都没解，给了数字就是凭空造出来的
        assertNull(TranscriptDisplay.progressLabel(row(TranscriptJobStatus.Queued, totalSegments = 12, doneSegments = 0)))
    }

    @Test
    fun `容器没给时长时只报段数不报百分比`() {
        val label = TranscriptDisplay.progressLabel(
            row(TranscriptJobStatus.Running, doneSegments = 3, phase = TranscriptPhase.Recognizing, positionMs = 5_000, durationMs = 0),
        )
        assertEquals("已识别 3 段", label)
        assertFalse("分母未知时不许出现百分号，那是假进度", label!!.contains("%"))
    }

    @Test
    fun `识别阶段按音频位置给百分比并带段数`() {
        assertEquals(
            "识别中 30% · 已识别 3 段",
            TranscriptDisplay.progressLabel(
                row(TranscriptJobStatus.Running, doneSegments = 3, phase = TranscriptPhase.Recognizing, positionMs = 30_000, durationMs = 100_000),
            ),
        )
    }

    @Test
    fun `收尾前百分比封顶 99`() {
        // 位置可能略超容器时长（解码器末尾垫静音）：进度条不许在 SUCCESS 落库前先喊 100%
        val label = TranscriptDisplay.progressLabel(
            row(TranscriptJobStatus.Running, doneSegments = 9, phase = TranscriptPhase.Recognizing, positionMs = 100_500, durationMs = 100_000),
        )
        assertEquals("识别中 99% · 已识别 9 段", label)
    }

    @Test
    fun `WMA 转码阶段说清在转 mp3`() {
        val known = TranscriptDisplay.progressLabel(
            row(TranscriptJobStatus.Running, phase = TranscriptPhase.Transcoding, positionMs = 61_000, durationMs = 2_060_956),
        )
        assertEquals("WMA 转码中 2%（转成 mp3 后开始识别）", known)
        val unknown = TranscriptDisplay.progressLabel(
            row(TranscriptJobStatus.Running, phase = TranscriptPhase.Transcoding, positionMs = 61_000, durationMs = 0),
        )
        assertEquals("WMA 转码中…（转成 mp3 后开始识别）", unknown)
        assertFalse(unknown!!.contains("%"))
    }

    @Test
    fun `刚进运行态还没有阶段时说准备中`() {
        val label = TranscriptDisplay.progressLabel(row(TranscriptJobStatus.Running))
        assertEquals("准备模型与解码…", label)
    }

    @Test
    fun `成功那一行说的是全部识别而不是百分比`() {
        // 成功时 done==total，显示 100% 只是把库里那两个字段抄一遍，没有信息量
        assertEquals(
            "共 7 段全部识别",
            TranscriptDisplay.progressLabel(row(TranscriptJobStatus.Success, totalSegments = 7, doneSegments = 7)),
        )
    }

    @Test
    fun `失败行只在真识别过东西时才提段数`() {
        assertNull(TranscriptDisplay.progressLabel(row(TranscriptJobStatus.Failed, doneSegments = 0)))
        assertEquals("中止前已识别 12 段", TranscriptDisplay.progressLabel(row(TranscriptJobStatus.Cancelled, doneSegments = 12)))
    }

    @Test
    fun `进度分数只在运行中且分母已知时存在`() {
        assertNull(row(TranscriptJobStatus.Running, positionMs = 10, durationMs = 0).progress)
        assertNull(row(TranscriptJobStatus.Success, positionMs = 10, durationMs = 100).progress)
        assertEquals(0.25f, row(TranscriptJobStatus.Running, positionMs = 25, durationMs = 100).progress!!, 0.0001f)
        assertEquals(1f, row(TranscriptJobStatus.Running, positionMs = 250, durationMs = 100).progress!!, 0.0001f)
    }

    @Test
    fun `五种状态各有各的标签不许重名`() {
        val labels = TranscriptJobStatus.values().map { TranscriptDisplay.statusLabel(it) }
        assertEquals("状态数与标签数要对得上", labels.size, labels.distinct().size)
        assertTrue(labels.all { it.isNotBlank() })
    }

    @Test
    fun `只有还在占着引擎的任务能取消`() {
        assertTrue(TranscriptDisplay.cancellable(TranscriptJobStatus.Queued))
        assertTrue(TranscriptDisplay.cancellable(TranscriptJobStatus.Running))
        assertFalse(TranscriptDisplay.cancellable(TranscriptJobStatus.Success))
        assertFalse(TranscriptDisplay.cancellable(TranscriptJobStatus.Failed))
        assertFalse(TranscriptDisplay.cancellable(TranscriptJobStatus.Cancelled))
    }

    @Test
    fun `失败与被取消的行给重试跑完的不给`() {
        assertTrue(TranscriptDisplay.retryable(TranscriptJobStatus.Failed))
        assertTrue(TranscriptDisplay.retryable(TranscriptJobStatus.Cancelled))
        assertFalse(TranscriptDisplay.retryable(TranscriptJobStatus.Queued))
        assertFalse(TranscriptDisplay.retryable(TranscriptJobStatus.Running))
        assertFalse(TranscriptDisplay.retryable(TranscriptJobStatus.Success))
    }

    @Test
    fun `只有已结束的行能删`() {
        assertTrue(TranscriptDisplay.deletable(TranscriptJobStatus.Success))
        assertTrue(TranscriptDisplay.deletable(TranscriptJobStatus.Failed))
        assertTrue(TranscriptDisplay.deletable(TranscriptJobStatus.Cancelled))
        assertFalse(TranscriptDisplay.deletable(TranscriptJobStatus.Queued))
        assertFalse(TranscriptDisplay.deletable(TranscriptJobStatus.Running))
    }

    @Test
    fun `删成功记录前必须说清字幕会一起没`() {
        val success = TranscriptDisplay.deleteConfirmText(row(TranscriptJobStatus.Success, totalSegments = 40, doneSegments = 40))
        assertTrue(success, success.contains("字幕") && success.contains("删除"))
        assertTrue(success, success.contains("40"))
        val failed = TranscriptDisplay.deleteConfirmText(row(TranscriptJobStatus.Failed))
        assertTrue(failed, failed.contains("不影响"))
    }

    @Test
    fun `每种失败都给得出一个动作`() {
        // FR-508：降级不能只说一句「失败了」。八种失败都要落到用户真能按下去的那一下
        TranscriptFailure.values().forEach { failure ->
            val guide = TranscriptDisplay.failureGuide(failure)
            assertTrue("$failure 的文案是空的", guide.isNotBlank())
            assertTrue("$failure 的文案没给可执行的动作：$guide", guide.contains("重试"))
        }
    }

    @Test
    fun `不合格占比那句话跟着常量走`() {
        // 阈值只许一处定义（AGENTS.md §3）：改了 AudioFormats 这里必须跟着变，不许 UI 自己抄一份 20
        val guide = TranscriptDisplay.failureGuide(TranscriptFailure.OutputUnreliable)
        assertTrue(
            "文案里的占比应与常量一致，实际：$guide",
            guide.contains("${AudioFormats.ASR_MAX_REJECTED_CHUNK_PERCENT}%"),
        )
    }

    @Test
    fun `已有字幕的提示指向真存在的按钮`() {
        // 「请点『覆盖重转』」要是按钮实际叫别的名字，用户就找不到出口了
        assertTrue(
            TranscriptDisplay.enqueueOutcomeLabel(TranscriptOutcomeCopy.AlreadyTranscribed)
                .contains(TranscriptDisplay.startButtonLabel(ExistingAsr.Ready)),
        )
    }

    @Test
    fun `只有 READY 需要 force 才谈得上覆盖`() {
        // EXPIRED 的旧字幕同样会被替换，但仓储不会挡它，所以按钮仍叫「开始转录」——
        // 替换这件事由 startGuide 说，不靠按钮标题去暗示
        assertTrue(TranscriptDisplay.startNeedsForce(ExistingAsr.Ready))
        assertFalse(TranscriptDisplay.startNeedsForce(ExistingAsr.Expired))
        assertFalse(TranscriptDisplay.startNeedsForce(ExistingAsr.None))
        assertEquals("覆盖重转", TranscriptDisplay.startButtonLabel(ExistingAsr.Ready))
        assertEquals("开始转录", TranscriptDisplay.startButtonLabel(ExistingAsr.Expired))
        assertEquals("开始转录", TranscriptDisplay.startButtonLabel(ExistingAsr.None))
    }

    @Test
    fun `过期字幕要说清会被替换掉`() {
        val guide = TranscriptDisplay.startGuide(ExistingAsr.Expired)
        assertTrue(guide.contains("旧"))
        assertTrue("必须交代整本替换：$guide", guide.contains("替换"))
        assertFalse("EXPIRED 不该被说成需要用户确认覆盖", guide.contains("覆盖重转"))
    }

    @Test
    fun `每种入队结局都给出一句话`() {
        TranscriptOutcomeCopy.values().forEach { outcome ->
            assertTrue("$outcome 没有文案", TranscriptDisplay.enqueueOutcomeLabel(outcome).isNotBlank())
        }
    }
}

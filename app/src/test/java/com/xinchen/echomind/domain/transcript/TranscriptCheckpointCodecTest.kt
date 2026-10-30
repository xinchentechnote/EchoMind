package com.xinchen.echomind.domain.transcript

import com.xinchen.echomind.domain.model.RecognizedSegment
import com.xinchen.echomind.domain.model.TokenTiming
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 断点编解码（FR-512）：无损往返是「续跑不丢字」的前提，损坏输入必须报废而不是读出假数据。 */
class TranscriptCheckpointCodecTest {

    private fun checkpoint(
        tokens: String = "那些花儿",
        segmentCount: Int = 2,
    ) = TranscriptCheckpoint(
        audioId = "audio-1",
        uri = "content://docs/01.mp3",
        fileLastModifiedMs = 1_724_000_000_000,
        modelId = "paraformer-zh",
        modelVersion = "abc123def456",
        resumeSample = 944_288L,
        totalChunks = 24,
        rejectedChunks = 1,
        doneSegments = 12,
        segments = (0 until segmentCount).map { s ->
            RecognizedSegment(
                startMs = 1_000L * s,
                endMs = 1_000L * s + 500,
                tokens = tokens.mapIndexed { i, ch ->
                    TokenTiming(token = ch.toString(), startMs = 1_000L * s + i * 100, endMs = 1_000L * s + i * 100 + 90)
                },
            )
        },
        savedAtWallMs = 1_700_000_000_000,
    )

    private fun roundTrip(original: TranscriptCheckpoint): TranscriptCheckpoint {
        val bytes = ByteArrayOutputStream().also { out ->
            TranscriptCheckpointCodec.write(original, DataOutputStream(out))
        }.toByteArray()
        return TranscriptCheckpointCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `写读往返每个字段都原样回来`() {
        val original = checkpoint()
        val restored = roundTrip(original)
        assertEquals(original, restored)
    }

    @Test
    fun `token 文本含换行与生僻字符也能无损往返`() {
        // writeUTF 是修改版 UTF-8：识别模型吐出什么文本都得原样落盘，不许被编码层吃掉
        val restored = roundTrip(checkpoint(tokens = "换\n行\t制表\u00A0不换行空格·「书名号」𝄞"))
        assertEquals(checkpoint(tokens = "换\n行\t制表\u00A0不换行空格·「书名号」𝄞"), restored)
    }

    @Test
    fun `空段列表也往返`() {
        // 断点没有完整段时根本不写盘（协调器守卫），但编解码自身不该对空列表装死；
        // doneSegments 与段列表是两个独立字段，各自原样往返
        val restored = roundTrip(checkpoint(segmentCount = 0))
        assertTrue(restored.segments.isEmpty())
        assertEquals(12, restored.doneSegments)
    }

    @Test
    fun `截断的字节流报错而不是读出半份断点`() {
        val bytes = ByteArrayOutputStream().also { out ->
            TranscriptCheckpointCodec.write(checkpoint(), DataOutputStream(out))
        }.toByteArray()
        assertThrows(IOException::class.java) {
            TranscriptCheckpointCodec.read(DataInputStream(ByteArrayInputStream(bytes, 0, bytes.size - 5)))
        }
    }

    @Test
    fun `不认识 magic 的文件直接报废`() {
        val garbage = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1)
        assertThrows(IllegalStateException::class.java) {
            TranscriptCheckpointCodec.read(DataInputStream(ByteArrayInputStream(garbage)))
        }
    }

    @Test
    fun `未来的格式版本按报废处理`() {
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).let { d ->
                // 复制 write 的头两个 int（magic 与版本），把版本抬到未知的 99
                d.writeInt(0x45434D42)
                d.writeInt(99)
            }
        }.toByteArray()
        assertThrows(IllegalStateException::class.java) {
            TranscriptCheckpointCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }
}

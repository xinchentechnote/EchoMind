package com.xinchen.echomind.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清单的「诚实性」底线测试（AGENTS.md §5、FR-510）：
 * 这里钉住的是**本机逐字节复核过**的数值，谁改了就必须同时改文档并重新实测，
 * 否则这条测试会红。
 */
class ModelCatalogTest {

    @Test
    fun `全部下载地址都是 https`() {
        ModelCatalog.all.flatMap { it.files }.forEach { fileSpec ->
            fileSpec.candidateUrls.forEach { url ->
                assertTrue("明文 http 不可接受：$url", url.startsWith("https://"))
            }
        }
    }

    @Test
    fun `校验值是 64 位小写十六进制`() {
        ModelCatalog.all.flatMap { it.files }.forEach { fileSpec ->
            listOfNotNull(fileSpec.officialSha256, fileSpec.pinnedSha256).forEach { sha ->
                assertTrue("$fileSpec 的校验值不像 sha256：$sha", HEX_64.matches(sha))
            }
        }
    }

    @Test
    fun `大小都是正数`() {
        ModelCatalog.all.flatMap { it.files }.forEach {
            assertTrue("${it.fileName} 大小非法：${it.expectedBytes}", it.expectedBytes > 0L)
        }
    }

    @Test
    fun `paraformer 的两个文件钉在本机实测值上`() {
        val model = ModelCatalog.paraformer.files[0]
        assertEquals("model.int8.onnx", model.fileName)
        assertEquals(243_371_218L, model.expectedBytes)
        assertEquals(
            "f36a0433bcf096bd6d6f11b80a3ac8bed110bdca632fe0d731df8d1a84475945",
            model.expectedSha256,
        )
        assertEquals(
            "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main/model.int8.onnx",
            model.url,
        )

        val tokens = ModelCatalog.paraformer.files[1]
        assertEquals("tokens.txt", tokens.fileName)
        assertEquals(75_756L, tokens.expectedBytes)
        assertEquals(
            "59aba8873a2ed1e122c25fee421e25f283b63290efbde85c1f01a853d83cb6e6",
            tokens.expectedSha256,
        )
    }

    @Test
    fun `silero vad 钉在官方 release 的 digest 上`() {
        val vad = ModelCatalog.sileroVad.files.single()
        assertEquals("silero_vad.onnx", vad.fileName)
        assertEquals(643_854L, vad.expectedBytes)
        assertEquals(
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            vad.expectedSha256,
        )
        assertTrue(vad.hasOfficialChecksum)
    }

    /**
     * `tokens.txt` 官方没发校验值（HF API 的 sha256 字段为空，`docs/asr-design.md` §3），
     * 所以它必须是清单里唯一 `hasOfficialChecksum == false` 的文件，UI 才能如实标注。
     */
    @Test
    fun `没有官方校验值的文件名单是封闭的且都本机钉住`() {
        // 名单是**封闭**的：新增模型带非官方校验值时这里要连着改，防止「来源不明」的文件混进来（AGENTS.md §5）
        val withoutOfficial = ModelCatalog.all.flatMap { it.files }.filterNot { it.hasOfficialChecksum }
        // whisperBase 不进 all → 其三份非官方校验值文件也退出这份名单
        assertEquals(
            listOf("tokens.txt", "model.int8.onnx", "tokens.txt"),
            withoutOfficial.map { it.fileName },
        )
        withoutOfficial.forEach { file ->
            assertNull("${file.fileName} 官方值应为 null", file.officialSha256)
            assertEquals("${file.fileName} 期望值必须来自本机钉住", file.pinnedSha256, file.expectedSha256)
        }
    }

    @Test
    fun `中文书解模型仍是带时间戳的zh型号`() {
        // 中文主场景的型号不许被悄悄换掉：官方只有 zh-2023-09-14 带逐字时间戳（docs/asr-design.md §1-A4）
        assertEquals("paraformer-zh-2023-09-14-int8", ModelCatalog.PARAFORMER_ID)
        assertEquals(ModelKind.PARAFORMER, ModelCatalog.paraformer.kind)
        ModelCatalog.paraformer.files.forEach {
            assertTrue("${it.url} 不是 zh-2023-09-14 的仓库", it.url.contains("sherpa-onnx-paraformer-zh-2023-09-14"))
        }
    }

    @Test
    fun `whisper是多语种识别模型且时间戳开关打开`() {
        val whisper = ModelCatalog.whisperBase
        assertEquals(ModelKind.WHISPER, whisper.kind)
        assertEquals(ModelRole.Recognizer, whisper.role)
        whisper.files.forEach {
            assertTrue("${it.url} 不是 whisper-base 的仓库", it.url.contains("sherpa-onnx-whisper-base"))
        }
        // 哈希是本机下载实测钉住的（hf-mirror，官方对 int8 导出不发布校验值）
        assertTrue(whisper.files.all { !it.hasOfficialChecksum })
    }

    @Test
    fun `id 唯一且 byId 能取回同一个包`() {
        val ids = ModelCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ModelCatalog.sileroVad, ModelCatalog.byId(ModelCatalog.SILERO_VAD_ID))
        // whisperBase 不进 all（D41：无 token 时间戳，出不了字幕）；下载列表 = 三件
        assertEquals(
            listOf(ModelCatalog.paraformer, ModelCatalog.sileroVad, ModelCatalog.senseVoice),
            ModelCatalog.all,
        )
    }

    @Test
    fun `byId 对未知 id 直接抛错而不是返回 null`() {
        assertThrows(IllegalArgumentException::class.java) { ModelCatalog.byId("whisper-tiny") }
    }

    @Test
    fun `总大小是各文件之和`() {
        assertEquals(243_371_218L + 75_756L, ModelCatalog.paraformer.totalBytes)
        assertEquals(643_854L, ModelCatalog.sileroVad.totalBytes)
        assertEquals(29_120_534L + 130_672_026L + 816_730L, ModelCatalog.whisperBase.totalBytes)
    }

    private companion object {
        val HEX_64 = Regex("^[0-9a-f]{64}$")
    }
}

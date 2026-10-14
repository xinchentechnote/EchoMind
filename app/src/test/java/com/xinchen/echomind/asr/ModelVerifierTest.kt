package com.xinchen.echomind.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 核对逻辑的四种结论（存在 / 缺失 / 长度不对 / 内容不对）都必须各自命中，
 * 因为设置页要按这几种情况给不同的可执行提示（FR-510）。
 */
class ModelVerifierTest {

    @JvmField
    @Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private lateinit var modelsRoot: File

    @Before
    fun setUp() {
        modelsRoot = folder.newFolder("models")
    }

    @Test
    fun `文件不存在时报 Missing 而不是崩溃`() {
        val spec = specOf(fileSpecOf("model.onnx", "abc".toByteArray()))
        val result = ModelVerifier.verify(spec, modelsRoot, verifiedAtMs = 1_000L)
        assertFalse(result.isReady)
        assertEquals(
            listOf(FileFailure.Missing("model.onnx")),
            result.failures,
        )
        assertEquals(1_000L, result.verifiedAtMs)
        assertEquals(File(modelsRoot, spec.id), result.dir)
    }

    @Test
    fun `长度不对时先报 SizeMismatch 不算哈希`() {
        val fileSpec = fileSpecOf("model.onnx", "abc".toByteArray(), expectedBytes = 99L)
        val spec = specOf(fileSpec)
        write(File(modelsRoot, spec.id), fileSpec.fileName, "abc".toByteArray())

        val result = ModelVerifier.verify(spec, modelsRoot, verifiedAtMs = 2_000L)
        assertEquals(listOf(FileFailure.SizeMismatch("model.onnx", 99L, 3L)), result.failures)
    }

    @Test
    fun `长度相同但内容不同时报 HashMismatch`() {
        val content = "hello-asr".toByteArray()
        // 清单里的期望哈希指向另一份内容，长度故意一致，专门触发「只有哈希能发现」的那条分支
        val fileSpec = fileSpecOf("model.onnx", content, expectedSha256 = sha256Hex("other-hex!".toByteArray()))
        val spec = specOf(fileSpec)
        write(File(modelsRoot, spec.id), fileSpec.fileName, content)

        val result = ModelVerifier.verify(spec, modelsRoot, verifiedAtMs = 3_000L)
        val failure = result.failures.single() as FileFailure.HashMismatch
        assertEquals("model.onnx", failure.fileName)
        assertEquals(sha256Hex("other-hex!".toByteArray()), failure.expectedSha256)
        assertEquals(sha256Hex(content), failure.actualSha256)
    }

    @Test
    fun `清单里一个文件都不合格时逐个都列出来`() {
        val spec = specOf(
            fileSpecOf("model.onnx", "a".toByteArray()),
            fileSpecOf("tokens.txt", "b".toByteArray()),
        )
        val result = ModelVerifier.verify(spec, modelsRoot, verifiedAtMs = 4_000L)
        assertEquals(
            listOf(FileFailure.Missing("model.onnx"), FileFailure.Missing("tokens.txt")),
            result.failures,
        )
    }

    @Test
    fun `多放一个没在清单里的文件不影响就绪判断`() {
        val content = "model-bytes".toByteArray()
        val fileSpec = fileSpecOf("model.onnx", content)
        val spec = specOf(fileSpec)
        val dir = write(File(modelsRoot, spec.id), fileSpec.fileName, content)
        File(dir, ModelManager.PROVENANCE_FILE_NAME).writeText("whatever\n")

        assertTrue(ModelVerifier.verify(spec, modelsRoot, verifiedAtMs = 5_000L).isReady)
    }

    @Test
    fun `校验值比对不分大小写`() {
        val content = "mixed-case".toByteArray()
        val fileSpec = fileSpecOf("model.onnx", content, expectedSha256 = sha256Hex(content).uppercase())
        val spec = specOf(fileSpec)
        write(File(modelsRoot, spec.id), fileSpec.fileName, content)

        assertTrue(ModelVerifier.verify(spec, modelsRoot, verifiedAtMs = 6_000L).isReady)
    }

    /** SHA-256("abc") 的标准测试向量，钉住哈希实现没跑偏。 */
    @Test
    fun `sha256 与公开测试向量一致`() {
        val file = File(folder.newFolder("digest"), "abc.bin")
        file.writeBytes("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ModelVerifier.sha256(file),
        )
    }

    @Test
    fun `isFileValid 与 checkFile 的结论一致`() {
        val content = "valid-check".toByteArray()
        val fileSpec = fileSpecOf("model.onnx", content)
        val dir = write(File(modelsRoot, "valid"), fileSpec.fileName, content)
        val good = File(dir, fileSpec.fileName)
        assertTrue(ModelVerifier.isFileValid(good, fileSpec))
        assertNull(ModelVerifier.checkFile(good, fileSpec))

        val truncated = File(dir, "truncated.onnx")
        truncated.writeBytes("valid-ch".toByteArray())
        assertFalse(ModelVerifier.isFileValid(truncated, fileSpec))
        assertTrue(ModelVerifier.checkFile(truncated, fileSpec) is FileFailure.SizeMismatch)
    }

    /** 目录不存在时是 Missing（不是抛异常），设置页第一次进来就是这个状态 */
    @Test
    fun `模型目录都还没建时也能核对`() {
        val spec = specOf(fileSpecOf("model.onnx", "abc".toByteArray()))
        val result = ModelVerifier.verify(spec, File(modelsRoot, "not-created-yet"), verifiedAtMs = 7_000L)
        assertEquals(listOf(FileFailure.Missing("model.onnx")), result.failures)
    }

    private fun write(dir: File, fileName: String, content: ByteArray): File {
        dir.mkdirs()
        File(dir, fileName).writeBytes(content)
        return dir
    }
}

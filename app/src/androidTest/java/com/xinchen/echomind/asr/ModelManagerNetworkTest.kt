package com.xinchen.echomind.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xinchen.echomind.core.time.AndroidTimeSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 用**真 URL、真 socket、真权限**跑一遍 `ModelManager.download`（FR-510 的设备侧证明）。
 *
 * 为什么必须是仪器化测试：JVM 单测里的 `HttpServer` 只证明了解析逻辑，
 * 证明不了三件只有设备才知道的事——`AndroidManifest` 有没有 `INTERNET` 权限、
 * Android 的 `HttpURLConnection` 实现在 TLS/重定向/HTTP 版本上和 host JDK 差多少、
 * 文件能不能真写进 `filesDir` 并改名。这些任一不成立，「模型可下载」就是假话。
 *
 * 只下 `tokens.txt`（75 KB）：243 MB 那份不进测试，跑一次要占满模拟器磁盘，
 * 而它的字节数与哈希已在宿主机上逐字节核对过（`docs/asr-design.md` §3）。
 * `silero_vad.onnx` 只有 GitHub 一个源，本机从 2026-10-15 起对 `github.com:443` 持续超时
 * （`SocketTimeoutException ... after 15000ms`），所以这里不放它的用例——
 * 网络不可达会让整条套件变红，而变红的原因与代码无关，这种失败没有价值（`docs/testing.md` §4 记了这次实测）。
 *
 * 下载目录是 `filesDir/instrumented-models`，与正式路径 `filesDir/models` 隔离，
 * 测试跑完删干净，不留半截模型给推理层误用。
 */
@RunWith(AndroidJUnit4::class)
class ModelManagerNetworkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var modelsRoot: File

    @Before
    fun cleanModelsRoot() {
        modelsRoot = File(context.filesDir, "instrumented-models")
        modelsRoot.deleteRecursively()
    }

    @After
    fun removeDownloadedFiles() {
        modelsRoot.deleteRecursively()
    }

    private fun managerFor(vararg specs: ModelSpec) = ModelManager(
        modelsRoot = modelsRoot,
        timeSource = AndroidTimeSource(),
        specs = specs.toList(),
    )

    /** 把清单里那一个真实文件包成单文件模型包，id 沿用真实的，这样落盘路径与正式版一致。 */
    private fun singleFilePackage(fileSpec: ModelFileSpec, specId: String): ModelSpec = ModelSpec(
        id = specId,
        displayName = "仪器化测试：${fileSpec.fileName}",
        role = ModelRole.Recognizer,
        files = listOf(fileSpec),
    )

    @Test
    fun tokensTxt_fromRealHfMirrorUrl_landsHashedAndPinned() {
        val fileSpec = ModelCatalog.paraformer.files.first { it.fileName == "tokens.txt" }
        val spec = singleFilePackage(fileSpec, ModelCatalog.PARAFORMER_ID)
        val manager = managerFor(spec)

        val verification = runBlocking { manager.download(spec) }

        assertTrue(
            "下载后仍不合格：${verification.failures.joinToString { ModelManager.describeFailure(it) }}",
            verification.isReady,
        )
        val target = File(modelsRoot, "${spec.id}/tokens.txt")
        assertTrue(target.isFile)
        assertEquals(fileSpec.expectedBytes, target.length())
        // 自己算一遍，不复用 download 内部的比对结果——两处独立才说明哈希值本身是对的
        assertEquals(fileSpec.expectedSha256, ModelVerifier.sha256(target))

        val provenance = File(modelsRoot, "${spec.id}/${ModelManager.PROVENANCE_FILE_NAME}").readText()
        assertTrue(provenance, provenance.contains("tokens.txt.url=${fileSpec.url}"))
        assertTrue(provenance, provenance.contains("tokens.txt.bytes=${fileSpec.expectedBytes}"))
        assertTrue(provenance, provenance.contains("tokens.txt.sha256=${fileSpec.expectedSha256}"))
        // tokens.txt 官方没发布校验值，来源必须如实标成本机钉住
        assertTrue(provenance, provenance.contains("tokens.txt.checksum=pinned-by-first-download"))

        val status = manager.statuses.value.first { it.spec.id == spec.id }
        assertTrue(status.isReady)
        assertFalse(status.isDownloading)
        assertNull(status.error)
        // 半截文件必须清掉
        assertFalse(File(modelsRoot, "${spec.id}/tokens.txt.part").exists())
    }

    @Test
    fun alreadyValidFile_isNotRequestedAgain() {
        val fileSpec = ModelCatalog.paraformer.files.first { it.fileName == "tokens.txt" }
        val spec = singleFilePackage(fileSpec, ModelCatalog.PARAFORMER_ID)
        val manager = managerFor(spec)
        runBlocking { manager.download(spec) }
        val downloadedAtMs = File(modelsRoot, "${spec.id}/tokens.txt").lastModified()

        // 第二次 download 走「已合格就跳过」分支：文件不该被重写，mtime 不该变
        runBlocking { manager.download(spec) }

        assertEquals(downloadedAtMs, File(modelsRoot, "${spec.id}/tokens.txt").lastModified())
    }

    @Test
    fun missingRemoteFile_failsWithHttpStatusAndLeavesNoArtifact() {
        val fakeSpec = ModelSpec(
            id = "does-not-exist",
            displayName = "仪器化测试：不存在的远端文件",
            role = ModelRole.Recognizer,
            files = listOf(
                ModelFileSpec(
                    fileName = "not-a-real-file-9f3a2b.bin",
                    // 宿主机实测 404（docs/asr-design.md §3），清单里没有也不打算有
                    url = "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14" +
                        "/resolve/main/not-a-real-file-9f3a2b.bin",
                    backupUrls = emptyList(),
                    expectedBytes = 1L,
                    officialSha256 = null,
                    pinnedSha256 = "0".repeat(64),
                ),
            ),
        )
        val manager = managerFor(fakeSpec)

        val error = runCatching { runBlocking { manager.download(fakeSpec) } }.exceptionOrNull()
        assertNotNull("404 本该抛错，却下载成功了", error)
        assertTrue("${error?.message}", error is ModelDownloadException)
        assertTrue("${error?.message}", error!!.message!!.contains("HTTP 404"))

        val dir = File(modelsRoot, fakeSpec.id)
        assertFalse(File(dir, "not-a-real-file-9f3a2b.bin").exists())
        assertFalse(File(dir, "not-a-real-file-9f3a2b.bin.part").exists())
        val status = manager.statuses.value.first { it.spec.id == fakeSpec.id }
        assertFalse(status.isDownloading)
        assertFalse(status.isReady)
        assertNotNull(status.error)
    }

    @Test
    fun refreshBeforeAnyDownload_reportsMissingNotCrash() {
        val spec = singleFilePackage(
            ModelCatalog.sileroVad.files.single(),
            ModelCatalog.SILERO_VAD_ID,
        )
        val manager = managerFor(spec)

        val statuses = runBlocking { manager.refresh() }

        val verification = statuses.single().verification
        assertNotNull(verification)
        assertFalse(verification!!.isReady)
        assertEquals(1, verification.failures.size)
        assertEquals("silero_vad.onnx", verification.failures.single().fileName)
    }
}

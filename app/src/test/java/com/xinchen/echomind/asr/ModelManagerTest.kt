package com.xinchen.echomind.asr

import com.sun.net.httpserver.HttpServer
import com.xinchen.echomind.core.time.FakeTimeSource
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「下载 → 校验 → 落盘 → 记来源」这条管线的行为（FR-510，`docs/asr-design.md` §7）。
 *
 * 全部用假清单（`example.invalid`）与假时钟；只有最后两条起本机 `127.0.0.1` 临时 HTTP 服务，
 * 走真实 `HttpURLConnection` 那条路。不打外部地址：真实清单的数值由 `ModelCatalogTest` 钉住，
 * 「源站现在下不下得动」属于设备联网验收，不在单测里假装跑过。
 */
class ModelManagerTest {

    @JvmField
    @Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private lateinit var modelsRoot: File
    private val time = FakeTimeSource(monotonic = 5_000L, wall = 1_760_000_000_000L)

    @Before
    fun setUp() {
        modelsRoot = folder.newFolder("models")
    }

    /** ioDispatcher 用 Unconfined：下载循环就在测试线程上跑，不引入真实线程时序 */
    private fun manager(fetcher: ModelFetcher, vararg specs: ModelSpec): ModelManager = ModelManager(
        modelsRoot = modelsRoot,
        timeSource = time,
        specs = specs.toList(),
        fetcher = fetcher,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun statusOf(subject: ModelManager, spec: ModelSpec): ModelStatus =
        subject.statuses.value.first { it.spec.id == spec.id }

    private fun installedFile(spec: ModelSpec, name: String): File =
        File(ModelVerifier.dirOf(modelsRoot, spec), name)

    private fun provenanceText(spec: ModelSpec): String =
        File(ModelVerifier.dirOf(modelsRoot, spec), ModelManager.PROVENANCE_FILE_NAME).readText()

    @Test
    fun `下载成功后文件落地并写好来源记录`() {
        val content = "model-bytes-0123456789".toByteArray()
        val fileSpec = fileSpecOf("model.onnx", content)
        val spec = specOf(fileSpec)
        val fetcher = RecordingFetcher(bodies = mapOf(fileSpec.url to content))

        val verification = runBlocking { manager(fetcher, spec).download(spec) }

        assertTrue(verification.failures.toString(), verification.isReady)
        assertArrayEquals(content, installedFile(spec, "model.onnx").readBytes())
        assertEquals(listOf(fileSpec.url), fetcher.requestedUrls)
        assertFalse(verification.dir.list()!!.any { it.endsWith(".part") })

        val provenance = provenanceText(spec)
        assertTrue(provenance, provenance.contains("model.onnx.url=${fileSpec.url}"))
        assertTrue(provenance, provenance.contains("model.onnx.bytes=${content.size}"))
        assertTrue(provenance, provenance.contains("model.onnx.sha256=${sha256Hex(content)}"))
        assertTrue(provenance, provenance.contains("model.onnx.checksum=official"))
        assertTrue(provenance, provenance.contains("model.onnx.model_id=${spec.id}"))
        assertTrue(provenance, provenance.contains("model.onnx.fetched_at_ms=${time.wallMs()}"))
        assertEquals(time.wallMs(), verification.verifiedAtMs)
    }

    /** 无官方校验值的文件要在来源记录里写明是本机钉住的（AGENTS.md §5） */
    @Test
    fun `没有官方校验值时来源记录如实标注`() {
        val content = "tokens".toByteArray()
        val fileSpec = fileSpecOf("tokens.txt", content, officialSha256 = null)
        val spec = specOf(fileSpec)

        runBlocking { manager(RecordingFetcher(bodies = mapOf(fileSpec.url to content)), spec).download(spec) }

        assertTrue(provenanceText(spec), provenanceText(spec).contains("tokens.txt.checksum=pinned-by-first-download"))
    }

    @Test
    fun `哈希不符时不留成品也不留半截文件`() {
        val content = "expect-byte".toByteArray()
        val tampered = "actual-byte".toByteArray()   // 长度故意一致，只有哈希能发现
        val fileSpec = fileSpecOf("model.onnx", content)
        val spec = specOf(fileSpec)

        val error = expectFailure {
            manager(RecordingFetcher(bodies = mapOf(fileSpec.url to tampered)), spec).download(spec)
        }

        assertTrue(error.message!!, error.message!!.contains("SHA-256 不符"))
        assertFalse(installedFile(spec, "model.onnx").exists())
        assertFalse(installedFile(spec, "model.onnx.part").exists())
    }

    @Test
    fun `清单值是本机钉的时报错要说明这一点`() {
        val content = "expect-byte".toByteArray()
        val fileSpec = fileSpecOf("model.onnx", content, officialSha256 = null)
        val spec = specOf(fileSpec)

        val error = expectFailure {
            manager(RecordingFetcher(bodies = mapOf(fileSpec.url to "actual-byte".toByteArray())), spec).download(spec)
        }

        assertTrue(error.message!!, error.message!!.contains("本机钉住的，非官方"))
    }

    @Test
    fun `大小不符时报错并说明期望与实际`() {
        val fileSpec = fileSpecOf("model.onnx", "1234567890".toByteArray(), expectedBytes = 99L)
        val spec = specOf(fileSpec)

        val error = expectFailure {
            manager(RecordingFetcher(bodies = mapOf(fileSpec.url to "1234567890".toByteArray())), spec).download(spec)
        }

        assertTrue(error.message!!, error.message!!.contains("期望 99 B，实收 10 B"))
        assertFalse(installedFile(spec, "model.onnx").exists())
    }

    @Test
    fun `首选地址失败时依次试备用地址`() {
        val content = "fallback-ok".toByteArray()
        val primary = "https://example.invalid/primary"
        val backup = "https://example.invalid/backup"
        val fileSpec = fileSpecOf("model.onnx", content, url = primary, backupUrls = listOf(backup))
        val spec = specOf(fileSpec)
        val fetcher = RecordingFetcher(bodies = mapOf(backup to content), errors = mapOf(primary to "首选源 503"))

        runBlocking { manager(fetcher, spec).download(spec) }

        assertEquals(listOf(primary, backup), fetcher.requestedUrls)
        assertTrue(provenanceText(spec), provenanceText(spec).contains("model.onnx.url=$backup"))
    }

    @Test
    fun `所有地址都失败时把每个原因都列出来`() {
        val primary = "https://example.invalid/primary"
        val backup = "https://example.invalid/backup"
        val fileSpec = fileSpecOf("model.onnx", "abc".toByteArray(), url = primary, backupUrls = listOf(backup))
        val spec = specOf(fileSpec)
        val fetcher = RecordingFetcher(errors = mapOf(primary to "首选源炸了", backup to "备用源也炸了"))

        val error = expectFailure { manager(fetcher, spec).download(spec) }

        val message = error.message!!
        assertTrue(message, message.contains("首选源炸了"))
        assertTrue(message, message.contains("备用源也炸了"))
        assertTrue(message, message.contains("试过 2 个地址"))
    }

    @Test
    fun `已经合格的文件不重复下载`() {
        val content = "already-here".toByteArray()
        val fileSpec = fileSpecOf("model.onnx", content)
        val spec = specOf(fileSpec)
        installedFile(spec, "model.onnx").run {
            parentFile!!.mkdirs()
            writeBytes(content)
        }
        val fetcher = RecordingFetcher(bodies = mapOf(fileSpec.url to content))

        val verification = runBlocking { manager(fetcher, spec).download(spec) }

        assertTrue(verification.isReady)
        assertEquals(emptyList<String>(), fetcher.requestedUrls)
    }

    @Test
    fun `多文件包中断后再点下载只补缺的那个`() {
        val modelContent = "model".toByteArray()
        val tokensContent = "tokens".toByteArray()
        val model = fileSpecOf("model.onnx", modelContent)
        val tokens = fileSpecOf("tokens.txt", tokensContent)
        val spec = specOf(model, tokens)
        installedFile(spec, "model.onnx").run {
            parentFile!!.mkdirs()
            writeBytes(modelContent)
        }

        // 第一轮：词表那个地址报错，模拟「模型已到、词表没到」
        expectFailure {
            manager(
                RecordingFetcher(bodies = mapOf(tokens.url to tokensContent), errors = mapOf(tokens.url to "断了")),
                spec,
            ).download(spec)
        }
        assertTrue(installedFile(spec, "model.onnx").exists())
        assertFalse(installedFile(spec, "tokens.txt").exists())

        val retry = RecordingFetcher(bodies = mapOf(tokens.url to tokensContent))
        runBlocking { manager(retry, spec).download(spec) }

        assertEquals(listOf(tokens.url), retry.requestedUrls)
        val provenance = provenanceText(spec)
        assertFalse(provenance, provenance.contains("model.onnx.url="))
        assertEquals(1, provenance.lines().count { it.startsWith("tokens.txt.url=") })
    }

    @Test
    fun `失败会把原因写进状态且结束下载态`() {
        val fileSpec = fileSpecOf("model.onnx", "abc".toByteArray())
        val spec = specOf(fileSpec)
        val subject = manager(RecordingFetcher(errors = mapOf(fileSpec.url to "网络断了")), spec)

        expectFailure { subject.download(spec) }

        val status = statusOf(subject, spec)
        assertTrue(status.error!!, status.error.contains("网络断了"))
        assertFalse(status.isDownloading)
        assertFalse(status.isReady)
        assertNull(status.download)
    }

    @Test
    fun `刷新读本机文件给出核对结果`() {
        val content = "on-disk".toByteArray()
        val fileSpec = fileSpecOf("model.onnx", content)
        val spec = specOf(fileSpec)
        val subject = manager(RecordingFetcher(bodies = mapOf(fileSpec.url to content)), spec)

        // 刚建好还没核对过：verification 为 null
        assertNull(statusOf(subject, spec).verification)

        runBlocking { subject.download(spec) }
        assertTrue(statusOf(subject, spec).isReady)
        assertNull(statusOf(subject, spec).error)

        installedFile(spec, "model.onnx").delete()
        val refreshed = runBlocking { subject.refresh() }
        val afterRefresh = refreshed.first { it.spec.id == spec.id }
        assertFalse(afterRefresh.isReady)
        assertEquals(listOf(FileFailure.Missing("model.onnx")), afterRefresh.verification!!.failures)
    }

    @Test
    fun `进度按假时钟节流但尾包一定推`() {
        val content = "0123456789abcdef".toByteArray()
        val total = content.size.toLong()
        val fileSpec = fileSpecOf("model.onnx", content)
        val spec = specOf(fileSpec)
        // StateFlow 是合并式的：同步跑完的下载里，收集器只能看到最后一个值。
        // 所以「每次回调之后状态里是什么」必须在回调内部就地读，测试才有意义。
        var subject: ModelManager? = null
        val published = mutableListOf<Long?>()
        fun readPublished() {
            published += subject!!.statuses.value.first { it.spec.id == spec.id }.download?.receivedBytes
        }
        val fetcher = ModelFetcher { _, target, onProgress ->
            target.writeBytes(content)
            onProgress(4L, total)
            readPublished()
            time.advanceMonotonic(100L)
            onProgress(8L, total)
            readPublished()
            time.advanceMonotonic(200L)
            onProgress(12L, total)
            readPublished()
            onProgress(total, total)
            readPublished()
        }
        subject = manager(fetcher, spec)

        runBlocking { subject.download(spec) }

        // 4 首发；8 距上次只有 100ms 被压掉（状态里还是 4）；12 到了 250ms 间隔；16 是尾包必须推
        assertEquals(listOf(4L, 4L, 12L, total), published)
        assertNull(statusOf(requireNotNull(subject), spec).download)
    }

    @Test
    fun `服务端没报总数时进度里是 -1`() {
        val content = "no-length".toByteArray()
        val fileSpec = fileSpecOf("model.onnx", content)
        val spec = specOf(fileSpec)
        var subject: ModelManager? = null
        var observedTotalBytes: Long? = null
        val fetcher = ModelFetcher { _, target, onProgress ->
            target.writeBytes(content)
            onProgress(content.size.toLong(), -1L)
            observedTotalBytes = subject!!.statuses.value.first { it.spec.id == spec.id }.download?.totalBytes
        }
        subject = manager(fetcher, spec)

        val verification = runBlocking { subject.download(spec) }

        assertTrue(verification.isReady)
        // -1 要原样进状态：UI 靠它区分「总数未知」和「0 B」，偷偷当成 0 会画出一条假进度条
        assertEquals(-1L, observedTotalBytes)
        assertNull(statusOf(requireNotNull(subject), spec).download)
    }

    @Test
    fun `删除只动这个包的目录`() {
        val content = "to-delete".toByteArray()
        val fileSpec = fileSpecOf("model.onnx", content)
        val spec = specOf(fileSpec)
        val subject = manager(RecordingFetcher(bodies = mapOf(fileSpec.url to content)), spec)
        runBlocking { subject.download(spec) }
        val unrelated = File(modelsRoot, "keep-me.txt").also { it.writeText("留着") }

        val verification = runBlocking { subject.delete(spec) }

        assertFalse(installedFile(spec, "model.onnx").exists())
        assertFalse(File(ModelVerifier.dirOf(modelsRoot, spec), ModelManager.PROVENANCE_FILE_NAME).exists())
        assertEquals("留着", unrelated.readText())
        assertFalse(verification.isReady)
        assertEquals(listOf(FileFailure.Missing("model.onnx")), verification.failures)
        val status = statusOf(subject, spec)
        assertFalse(status.isReady)
        assertNull(status.error)
    }

    @Test
    fun `真实 HttpURLConnection：跟随相对 Location 重定向后校验落盘`() {
        val content = "http-model-body".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.onnx") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        server.createContext("/go") { exchange ->
            // 相对 Location，正是 GitHub release / hf-mirror 的真实形状：必须由 HttpFetcher 按当前地址解析
            exchange.responseHeaders.add("Location", "/model.onnx")
            exchange.sendResponseHeaders(302, -1L)
            exchange.close()
        }
        server.start()
        try {
            val fileSpec = fileSpecOf("model.onnx", content, url = "http://127.0.0.1:${server.address.port}/go")
            val spec = specOf(fileSpec, id = "http-model")

            val verification = runBlocking { manager(HttpFetcher, spec).download(spec) }

            assertTrue(verification.failures.toString(), verification.isReady)
            assertArrayEquals(content, installedFile(spec, "model.onnx").readBytes())
            assertTrue(provenanceText(spec), provenanceText(spec).contains("model.onnx.url=http://127.0.0.1:${server.address.port}/go"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `真实 HttpURLConnection：404 时报错且不落地任何文件`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model.onnx") { exchange ->
            val body = "not found".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val fileSpec = fileSpecOf(
                "model.onnx",
                "whatever".toByteArray(),
                url = "http://127.0.0.1:${server.address.port}/model.onnx",
            )
            val spec = specOf(fileSpec, id = "http-404")

            val error = expectFailure { manager(HttpFetcher, spec).download(spec) }

            assertTrue(error.message!!, error.message!!.contains("HTTP 404"))
            assertFalse(installedFile(spec, "model.onnx").exists())
            assertFalse(installedFile(spec, "model.onnx.part").exists())
        } finally {
            server.stop(0)
        }
    }

    /** 断言下载抛异常并把它取出来（`runBlocking` 里的挂起调用没法直接交给 `assertThrows`） */
    private fun expectFailure(block: suspend () -> Unit): ModelDownloadException {
        var thrown: ModelDownloadException? = null
        try {
            runBlocking { block() }
        } catch (e: ModelDownloadException) {
            thrown = e
        }
        assertNotNull("应该抛 ModelDownloadException", thrown)
        return thrown!!
    }

    private class RecordingFetcher(
        bodies: Map<String, ByteArray> = emptyMap(),
        private val errors: Map<String, String> = emptyMap(),
    ) : ModelFetcher {
        var bodies: Map<String, ByteArray> = bodies
        val requestedUrls = mutableListOf<String>()

        override fun fetch(url: String, target: File, onProgress: (Long, Long) -> Unit) {
            requestedUrls += url
            errors[url]?.let { throw IOException(it) }
            val body = bodies[url] ?: throw IOException("测试没准备这个地址：$url")
            target.parentFile?.mkdirs()
            target.writeBytes(body)
            onProgress(body.size.toLong(), body.size.toLong())
        }
    }
}

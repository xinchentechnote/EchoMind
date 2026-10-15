package com.xinchen.echomind.ui.settings

import com.xinchen.echomind.asr.ModelDownloadException
import com.xinchen.echomind.asr.ModelFetcher
import com.xinchen.echomind.asr.ModelManager
import com.xinchen.echomind.asr.ModelRole
import com.xinchen.echomind.asr.ModelSpec
import com.xinchen.echomind.asr.fileSpecOf
import com.xinchen.echomind.asr.sha256Hex
import com.xinchen.echomind.core.time.FakeTimeSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 设置页 ViewModel 的行为：它只许「调 [ModelManager]、把结局翻成一句文案」，
 * 这些用例钉住的就是这句话——状态从 manager 的流里来，文件对不对由 manager 与清单裁决。
 *
 * 直接用**真 [ModelManager]**（假 fetcher + 临时目录 + 假时钟）而不是再造一层假接口：
 * VM 的全部价值就在于它调的参数能让 manager 真的动起来，用假接口测等于什么都没测。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @JvmField
    @Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var modelsRoot: File
    private val time = FakeTimeSource(monotonic = 5_000L, wall = 1_760_000_000_000L)

    private val modelBytes = "model-bytes-0123456789".toByteArray()
    private val tokenBytes = "tokens-bytes".toByteArray()
    private val spec = ModelSpec(
        id = "fake-recognizer",
        displayName = "假识别模型（仅测试）",
        role = ModelRole.Recognizer,
        files = listOf(
            fileSpecOf(name = "model.onnx", content = modelBytes),
            fileSpecOf(name = "tokens.txt", content = tokenBytes, officialSha256 = null),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        modelsRoot = folder.root
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun manager(fetcher: ModelFetcher): ModelManager = ModelManager(
        modelsRoot = modelsRoot,
        timeSource = time,
        specs = listOf(spec),
        fetcher = fetcher,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun viewModel(
        fetcher: ModelFetcher,
        streams: Map<String, ByteArray> = emptyMap(),
    ): SettingsViewModel = SettingsViewModel(
        modelManager = manager(fetcher),
        openStream = { uri -> streams[uri]?.let { ByteArrayInputStream(it) } },
    )

    // -- 核对 --------------------------------------------------------------------

    @Test
    fun `初始核对把已经在位的包标成就绪`() = runTest {
        installValidFiles()
        val vm = viewModel(fetcher = failingFetcher())
        advanceUntilIdle()

        val status = vm.state.value.models.single()
        assertNotNull(status.verification)
        assertTrue(status.verification!!.isReady)
    }

    // -- 下载 --------------------------------------------------------------------

    @Test
    fun `下载成功后给成功文案且状态就绪`() = runTest {
        val vm = viewModel(fetcher = fakeFetcher())
        advanceUntilIdle()

        vm.download(spec.id)
        advanceUntilIdle()

        val status = vm.state.value.models.single()
        assertTrue(status.verification!!.isReady)
        // 大小是清单里两个文件字节数之和（22 + 12），文案不许另编一个数
        assertEquals("已取回并核对通过：假识别模型（仅测试）（34 B）", vm.state.value.message)
    }

    @Test
    fun `下载失败把可执行的原因放进消息`() = runTest {
        val vm = viewModel(fetcher = failingFetcher())
        advanceUntilIdle()

        vm.download(spec.id)
        advanceUntilIdle()

        val message = vm.state.value.message!!
        assertTrue(message, message.startsWith("操作失败："))
        assertTrue(message, message.contains("model.onnx"))
        // manager 也在包状态里留了同一份原因，卡片上的错误行有据可读
        assertNotNull(vm.state.value.models.single().error)
    }

    // -- 本地导入 ------------------------------------------------------------------

    @Test
    fun `导入按文件名对号入座并报还缺什么`() = runTest {
        val uri = "content://fake/1"
        val vm = viewModel(
            fetcher = failingFetcher(),
            streams = mapOf(uri to modelBytes),
        )
        advanceUntilIdle()

        vm.importPicked(spec.id, uri, displayName = "model.onnx")
        advanceUntilIdle()

        val message = vm.state.value.message!!
        assertTrue(message, message.contains("model.onnx"))
        assertTrue(message, message.contains("还差"))
        assertTrue(message, message.contains("tokens.txt"))
    }

    @Test
    fun `导错文件如实拒绝并列出包要的文件名`() = runTest {
        val vm = viewModel(fetcher = failingFetcher())
        advanceUntilIdle()

        vm.importPicked(spec.id, uri = "content://fake/2", displayName = "别的文件.bin")
        advanceUntilIdle()

        val message = vm.state.value.message!!
        assertTrue(message, message.contains("不是这个包要的文件"))
        assertTrue(message, message.contains("model.onnx"))
        assertTrue(message, message.contains("tokens.txt"))
    }

    @Test
    fun `打不开的文件如实报错不假装导入成功`() = runTest {
        val vm = viewModel(fetcher = failingFetcher(), streams = emptyMap())
        advanceUntilIdle()

        vm.importPicked(spec.id, uri = "content://fake/3", displayName = "model.onnx")
        advanceUntilIdle()

        val message = vm.state.value.message!!
        assertTrue(message, message.contains("操作失败"))
        assertTrue(message, message.contains("读不出"))
    }

    @Test
    fun `两个文件分两次导入齐了就是就绪`() = runTest {
        val streams = mapOf(
            "content://fake/a" to modelBytes,
            "content://fake/b" to tokenBytes,
        )
        val vm = viewModel(fetcher = failingFetcher(), streams = streams)
        advanceUntilIdle()

        vm.importPicked(spec.id, "content://fake/a", "model.onnx")
        advanceUntilIdle()
        vm.importPicked(spec.id, "content://fake/b", "tokens.txt")
        advanceUntilIdle()

        assertTrue(vm.state.value.models.single().verification!!.isReady)
        assertTrue(vm.state.value.message!!.contains("这个包现在齐了"))
    }

    // -- 删除 --------------------------------------------------------------------

    @Test
    fun `删除后回未就绪并给收尾文案`() = runTest {
        installValidFiles()
        val vm = viewModel(fetcher = failingFetcher())
        advanceUntilIdle()

        vm.deleteConfirmed(spec.id)
        advanceUntilIdle()

        val status = vm.state.value.models.single()
        assertTrue(!status.verification!!.isReady)
        assertTrue(vm.state.value.message!!, vm.state.value.message!!.contains("播放不受影响"))
        assertTrue(status.spec.files.all { !File(dirOf(status.spec.id), it.fileName).exists() })
    }

    // -- 假件与脚手架 ---------------------------------------------------------------

    private fun dirOf(specId: String) = File(modelsRoot, specId)

    /** 把两个文件按清单写进临时目录，模拟「文件已经在位」的设备 */
    private fun installValidFiles() {
        val dir = dirOf(spec.id)
        dir.mkdirs()
        File(dir, "model.onnx").writeBytes(modelBytes)
        File(dir, "tokens.txt").writeBytes(tokenBytes)
    }

    private fun fakeFetcher(): ModelFetcher = ModelFetcher { url, target, onProgress ->
        val body = when {
            url.endsWith("model.onnx") -> modelBytes
            url.endsWith("tokens.txt") -> tokenBytes
            else -> throw IOException("意外地址：$url")
        }
        target.parentFile?.mkdirs()
        target.writeBytes(body)
        onProgress(body.size.toLong(), body.size.toLong())
    }

    private fun failingFetcher(): ModelFetcher = ModelFetcher { url, _, _ ->
        throw ModelDownloadException("取回失败（测试假件）：$url")
    }
}

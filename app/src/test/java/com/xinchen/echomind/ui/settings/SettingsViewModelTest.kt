package com.xinchen.echomind.ui.settings

import com.xinchen.echomind.asr.ModelFetcher
import com.xinchen.echomind.asr.ModelManager
import com.xinchen.echomind.asr.ModelRole
import com.xinchen.echomind.asr.ModelSpec
import com.xinchen.echomind.asr.fileSpecOf
import com.xinchen.echomind.core.time.FakeTimeSource
import java.io.ByteArrayInputStream
import java.io.File
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
import org.junit.Assert.assertFalse
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
 * 模型内置后这一页的动作是「从安装包恢复」，直接用**真 [ModelManager]**（假 assets + 临时目录 +
 * 假时钟）而不是再造一层假接口：VM 的全部价值就在于它调的参数能让 manager 真的动起来，
 * 用假接口测等于什么都没测。
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

    private fun manager(): ModelManager = ModelManager(
        modelsRoot = modelsRoot,
        timeSource = time,
        specs = listOf(spec),
        fetcher = unusedFetcher(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun viewModel(
        assets: Map<String, ByteArray> = emptyMap(),
        mp3Target: Boolean = false,
    ): SettingsViewModel = SettingsViewModel(
        modelManager = manager(),
        openAsset = { path -> assets[path]?.let { ByteArrayInputStream(it) } },
        appSettings = FakeAppSettings(mp3Target),
    )

    /** 内存版设置：测试里可读写，验证「设置项落进 AppSettings 并被转码路径读到」 */
    private class FakeAppSettings(var mp3: Boolean = false) : com.xinchen.echomind.data.repo.AppSettings {
        override var wmaTranscodeToMp3: Boolean
            get() = mp3
            set(value) { mp3 = value }
    }

    /** assets 布局与生产一致：models/<specId>/<fileName> */
    private fun assetPathOf(fileName: String) = "models/${spec.id}/$fileName"

    private fun fullAssets(): Map<String, ByteArray> = mapOf(
        assetPathOf("model.onnx") to modelBytes,
        assetPathOf("tokens.txt") to tokenBytes,
    )

    // -- 核对 --------------------------------------------------------------------

    @Test
    fun `初始核对把已经在位的包标成就绪`() = runTest {
        installValidFiles()
        val vm = viewModel()
        advanceUntilIdle()

        val status = vm.state.value.models.single()
        assertNotNull(status.verification)
        assertTrue(status.verification!!.isReady)
    }

    // -- 从安装包恢复 ---------------------------------------------------------------

    @Test
    fun `恢复把缺的文件从assets就位并给成功文案`() = runTest {
        val vm = viewModel(assets = fullAssets())
        advanceUntilIdle()

        vm.restore(spec.id)
        advanceUntilIdle()

        val status = vm.state.value.models.single()
        assertTrue(status.verification!!.isReady)
        assertEquals(
            "已从安装包恢复并核对通过：假识别模型（仅测试）（34 B）",
            vm.state.value.message,
        )
        // 就位的文件内容与 assets 一致，磁盘上不留半截 .part
        val dir = File(modelsRoot, spec.id)
        assertTrue(File(dir, "model.onnx").readBytes().contentEquals(modelBytes))
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun `assets缺文件如实报错且不假装成功`() = runTest {
        val vm = viewModel(assets = emptyMap())
        advanceUntilIdle()

        vm.restore(spec.id)
        advanceUntilIdle()

        val message = vm.state.value.message!!
        assertTrue(message, message.contains("安装包里没有"))
        assertTrue(message, message.contains("models/${spec.id}/model.onnx"))
        assertFalse(vm.state.value.models.single().verification!!.isReady)
    }

    @Test
    fun `内容坏掉的assets过不了校验目标文件不被替换`() = runTest {
        val corrupted = "corrupted-bytes".toByteArray()
        val vm = viewModel(assets = mapOf(assetPathOf("model.onnx") to corrupted))
        advanceUntilIdle()

        vm.restore(spec.id)
        advanceUntilIdle()

        val message = vm.state.value.message!!
        assertTrue(message, message.contains("操作失败"))
        assertTrue(message, message.contains("大小不符"))
        // .part 规矩：没过校验就不许改名，目录里不能冒出一个内容坏的正式文件
        val dir = File(modelsRoot, spec.id)
        assertFalse(File(dir, "model.onnx").exists())
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun `删除后从安装包恢复不需要网络也能回到就绪`() = runTest {
        installValidFiles()
        val vm = viewModel(assets = fullAssets())
        advanceUntilIdle()

        vm.deleteConfirmed(spec.id)
        advanceUntilIdle()
        assertFalse(vm.state.value.models.single().verification!!.isReady)

        vm.restore(spec.id)
        advanceUntilIdle()
        assertTrue(vm.state.value.models.single().verification!!.isReady)
    }

    // -- 转码目标设置（D42）--------------------------------------------------------

    @Test
    fun `设置项写进AppSettings并被VM状态回读`() {
        val settings = FakeAppSettings(mp3 = false)
        val vm = SettingsViewModel(
            modelManager = manager(),
            openAsset = { null },
            appSettings = settings,
        )
        org.junit.Assert.assertFalse(vm.wmaTargetMp3.value)

        vm.onWmaTargetMp3Selected(true)
        assertTrue(settings.wmaTranscodeToMp3)

        vm.onWmaTargetMp3Selected(false)
        assertFalse(settings.wmaTranscodeToMp3)
    }

    // -- 删除 --------------------------------------------------------------------

    @Test
    fun `删除后回未就绪并给收尾文案`() = runTest {
        installValidFiles()
        val vm = viewModel()
        advanceUntilIdle()

        vm.deleteConfirmed(spec.id)
        advanceUntilIdle()

        val status = vm.state.value.models.single()
        assertTrue(!status.verification!!.isReady)
        assertTrue(vm.state.value.message!!, vm.state.value.message!!.contains("从安装包恢复"))
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

    /** 产品路径已无在线下载；这个假件任何调用都直接失败，钉住「本套件不依赖网络」 */
    private fun unusedFetcher(): ModelFetcher = ModelFetcher { url, _, _ ->
        throw AssertionError("这些用例不该走网络下载：$url")
    }
}

package com.xinchen.echomind.data.repo

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncWmaImportPreparerTest {

    @Test
    fun `成功前不暴露半截产物且完成后原子就位`() = runTest {
        val store = WmaPlaybackStore(Files.createTempDirectory("wma-async").toFile())
        store.prepareDir()
        store.partFileOf("a", "stale-process").writeText("stale")
        val prepared = mutableListOf<Triple<String, String, Long>>()
        val preparer = AsyncWmaImportPreparer(
            scope = this,
            store = store,
            transcode = { _, outputFile, _ -> outputFile.writeText("complete"); 12_345L },
            onPrepared = { id, uri, duration -> prepared += Triple(id, uri, duration); true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(preparer.schedule("a", "content://book.wma"))
        assertFalse("后台任务尚未执行时不能暴露正式文件", store.isReady("a"))
        assertFalse("新任务调度时应清理上次进程遗留的 part", store.partFileOf("a", "stale-process").exists())
        advanceUntilIdle()

        assertTrue(store.isReady("a"))
        assertEquals("complete", store.fileOf("a").readText())
        assertTrue(store.fileOf("a").parentFile?.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertEquals(listOf(Triple("a", "content://book.wma", 12_345L)), prepared)
    }

    @Test
    fun `失败清理part且不生成可播放产物`() = runTest {
        val store = WmaPlaybackStore(Files.createTempDirectory("wma-failure").toFile())
        val failures = mutableListOf<Throwable>()
        val preparer = AsyncWmaImportPreparer(
            scope = this,
            store = store,
            transcode = { _, outputFile, _ ->
                outputFile.writeText("partial")
                error("codec stuck")
            },
            onPrepared = { _, _, _ -> true },
            onFailure = { _, error -> failures += error },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(preparer.schedule("a", "content://book.wma"))
        advanceUntilIdle()

        assertFalse(store.isReady("a"))
        assertTrue(store.fileOf("a").parentFile?.listFiles().orEmpty().none { it.name.endsWith(".part") })
        assertEquals("codec stuck", failures.single().message)
    }

    @Test
    fun `同一本WMA不会重复调度且书已删除时不留产物`() = runTest {
        val store = WmaPlaybackStore(Files.createTempDirectory("wma-stale").toFile())
        var transcodes = 0
        val preparer = AsyncWmaImportPreparer(
            scope = this,
            store = store,
            transcode = { _, outputFile, _ -> transcodes++; outputFile.writeText("done"); 1_000L },
            onPrepared = { _, _, _ -> false },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(preparer.schedule("a", "content://book.wma"))
        assertFalse(preparer.schedule("a", "content://book.wma"))
        advanceUntilIdle()

        assertEquals(1, transcodes)
        assertFalse(store.isReady("a"))
    }

    @Test
    fun `取消后快速重导入时旧任务不能覆盖新产物`() = runTest {
        val store = WmaPlaybackStore(Files.createTempDirectory("wma-generation").toFile())
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val preparedUris = mutableListOf<String>()
        val preparer = AsyncWmaImportPreparer(
            scope = this,
            store = store,
            transcode = { uri, output, _ ->
                if (uri.endsWith("old.wma")) {
                    oldStarted.complete(Unit)
                    // 模拟不响应协程取消的厂商 MediaCodec：cancel 后仍会把旧文件写完。
                    withContext(NonCancellable) { releaseOld.await() }
                    output.writeText("old")
                } else {
                    output.writeText("new")
                }
                1_000L
            },
            onPrepared = { _, uri, _ -> preparedUris += uri; true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(preparer.schedule("a", "content://old.wma"))
        runCurrent()
        oldStarted.await()
        preparer.cancel("a")
        assertTrue(preparer.schedule("a", "content://new.wma"))
        runCurrent()

        assertEquals("new", store.fileOf("a").readText())
        releaseOld.complete(Unit)
        advanceUntilIdle()

        assertEquals("new", store.fileOf("a").readText())
        assertEquals(listOf("content://new.wma"), preparedUris)
        assertTrue(store.fileOf("a").parentFile?.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun `内容变化强制重调度会让仍在运行的旧任务失效`() = runTest {
        val store = WmaPlaybackStore(Files.createTempDirectory("wma-force").toFile())
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val preparer = AsyncWmaImportPreparer(
            scope = this,
            store = store,
            transcode = { uri, output, _ ->
                if (uri == "same-uri-old-content") {
                    oldStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOld.await() }
                    output.writeText("old")
                } else {
                    output.writeText("new")
                }
                1_000L
            },
            onPrepared = { _, _, _ -> true },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        assertTrue(preparer.schedule("a", "same-uri-old-content"))
        runCurrent()
        oldStarted.await()
        assertTrue(preparer.schedule("a", "same-uri-new-content", force = true))
        runCurrent()
        assertEquals("new", store.fileOf("a").readText())

        releaseOld.complete(Unit)
        advanceUntilIdle()

        assertEquals("new", store.fileOf("a").readText())
    }
}

package com.xinchen.echomind.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 共享引擎池的引用计数语义（D46）。真引擎要 Android 与 243 MB 模型，这里用假句柄把「同一模型只装一份、
 * 最后一路才卸」钉在 JVM 里；设备上的并发端到端在 `TranscriptCoordinatorTest`。
 */
class RefCountedPoolTest {

    private class Handle(val name: String) {
        var closed = false
    }

    private fun pool(opens: AtomicInteger = AtomicInteger()) = RefCountedPool<String, Handle>(
        open = { key -> opens.incrementAndGet(); Handle(key) },
        close = { it.closed = true },
    )

    @Test
    fun `同一个 key 多路持有只打开一次且最后一路释放才关闭`() {
        val pool = pool()
        val a = pool.acquire("paraformer")
        val b = pool.acquire("paraformer")
        val c = pool.acquire("paraformer")

        assertSame(a.value, b.value)
        assertSame(b.value, c.value)
        assertEquals(1, pool.opened)
        assertEquals(3, pool.activeLeases("paraformer"))

        a.release()
        b.release()
        assertFalse("还有一路在用，不能关", a.value.closed)
        assertEquals(0, pool.closed)

        c.release()
        assertTrue(a.value.closed)
        assertEquals(1, pool.closed)
        assertEquals(0, pool.activeLeases("paraformer"))
    }

    @Test
    fun `释放是幂等的且关掉后再要会重新打开`() {
        val pool = pool()
        val lease = pool.acquire("k")
        lease.release()
        lease.release()
        assertEquals(1, pool.closed)

        val again = pool.acquire("k")
        assertEquals(2, pool.opened)
        assertFalse(again.value.closed)
        again.release()
        assertEquals(2, pool.closed)
    }

    @Test
    fun `不同 key 各自一份互不影响`() {
        val pool = pool()
        val p = pool.acquire("paraformer")
        val s = pool.acquire("sense-voice")
        assertEquals(2, pool.opened)
        p.release()
        assertTrue(p.value.closed)
        assertFalse(s.value.closed)
        s.release()
    }

    @Test
    fun `打开失败不入池不计数`() {
        var fail = true
        val pool = RefCountedPool<String, Handle>(
            open = { if (fail) error("模型不合格") else Handle(it) },
            close = { it.closed = true },
        )
        val thrown = runCatching { pool.acquire("k") }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(0, pool.opened)
        assertEquals(0, pool.activeLeases("k"))

        fail = false
        pool.acquire("k").release()
        assertEquals(1, pool.opened)
    }

    @Test
    fun `多线程同时首次申请同一模型也只装一份`() {
        val opens = AtomicInteger()
        val pool = pool(opens)
        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val executor = Executors.newFixedThreadPool(threads)
        val leases = java.util.Collections.synchronizedList(mutableListOf<RefCountedPool<String, Handle>.Lease>())
        repeat(threads) {
            executor.execute {
                ready.countDown()
                go.await()
                leases += pool.acquire("paraformer")
                done.countDown()
            }
        }
        ready.await(5, TimeUnit.SECONDS)
        go.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("并发首次申请必须只装一份", 1, opens.get())
        assertEquals(threads, pool.activeLeases("paraformer"))
        leases.forEach { it.release() }
        assertEquals(1, pool.closed)
    }
}
